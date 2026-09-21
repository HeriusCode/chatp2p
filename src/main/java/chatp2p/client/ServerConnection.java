package chatp2p.client;

import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns one TCP connection and its single background reader. */
final class ServerConnection implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;

    private final Socket socket = new Socket();
    private final Map<UUID, CompletableFuture<ProtocolMessage>> pending = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "client-server-listener");
        thread.setDaemon(true);
        return thread;
    });
    private final Object writeLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(false);
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile Consumer<ProtocolMessage> eventListener = ignored -> { };

    void connect(String host, int port) throws IOException {
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(READ_TIMEOUT_MILLIS);
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        open.set(true);
        readerExecutor.execute(this::readLoop);
    }

    void setEventListener(Consumer<ProtocolMessage> listener) {
        eventListener = listener == null ? ignored -> { } : listener;
    }

    ProtocolMessage request(ProtocolMessage request, Duration timeout) throws IOException {
        ensureOpen();
        CompletableFuture<ProtocolMessage> response = new CompletableFuture<>();
        if (pending.putIfAbsent(request.correlationId(), response) != null) {
            throw new IOException("Duplicate correlation ID: " + request.correlationId());
        }

        try {
            send(request);
            ProtocolMessage message = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (message.type() == MessageType.ERROR) {
                throw new IOException("Server error " + message.field("code") + ": "
                        + message.field("message"));
            }
            return message;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for server", interrupted);
        } catch (TimeoutException timeoutFailure) {
            throw new IOException("Timed out waiting for " + request.type() + " response", timeoutFailure);
        } catch (ExecutionException failedResponse) {
            Throwable cause = failedResponse.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Server connection failed", cause);
        } finally {
            pending.remove(request.correlationId());
        }
    }

    void send(ProtocolMessage message) throws IOException {
        ensureOpen();
        synchronized (writeLock) {
            Protocol.writeMessage(output, message);
        }
    }

    boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (!open.getAndSet(false)) {
            readerExecutor.shutdownNow();
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best effort; all pending calls are failed below.
        }
        IOException closed = new IOException("Server connection closed");
        pending.values().forEach(future -> future.completeExceptionally(closed));
        pending.clear();
        readerExecutor.shutdownNow();
    }

    private void readLoop() {
        try {
            while (open.get()) {
                ProtocolMessage message = Protocol.readMessage(input);
                CompletableFuture<ProtocolMessage> request = pending.get(message.correlationId());
                if (request != null) {
                    request.complete(message);
                } else {
                    eventListener.accept(message);
                }
            }
        } catch (EOFException eof) {
            failPending(new IOException("Server closed the connection", eof));
        } catch (IOException failure) {
            if (open.get()) {
                failPending(failure);
            }
        } catch (RuntimeException listenerFailure) {
            failPending(new IOException("Connection listener failed", listenerFailure));
        } finally {
            close();
        }
    }

    private void failPending(IOException failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("Not connected to server");
        }
    }
}

