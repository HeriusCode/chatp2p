package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Per-client TCP listener that receives direct peer chat connections. */
final class PeerServer implements AutoCloseable {
    private final String localUsername;
    private final Consumer<ChatMessage> chatListener;
    private final FileTransferManager fileTransferManager;
    private final Consumer<String> errorListener;
    private final ExecutorService acceptExecutor;
    private final ExecutorService handlerPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;

    PeerServer(
            String localUsername,
            Consumer<ChatMessage> chatListener,
            FileTransferManager fileTransferManager,
            Consumer<String> errorListener) {
        this.localUsername = localUsername;
        this.chatListener = chatListener;
        this.fileTransferManager = fileTransferManager;
        this.errorListener = errorListener;
        this.acceptExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "peer-accept-" + localUsername);
            thread.setDaemon(true);
            return thread;
        });
        this.handlerPool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "peer-handler-" + localUsername);
            thread.setDaemon(true);
            return thread;
        });
    }

    int start(int requestedPort) throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Peer server is already running");
        }
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(requestedPort));
            serverSocket = listener;
            acceptExecutor.execute(this::acceptLoop);
            return listener.getLocalPort();
        } catch (IOException failure) {
            running.set(false);
            listener.close();
            throw failure;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket peer = serverSocket.accept();
                peer.setSoTimeout(15_000);
                peer.setTcpNoDelay(true);
                handlerPool.execute(() -> handle(peer));
            } catch (SocketException closed) {
                if (running.get()) {
                    errorListener.accept("Peer listener socket error: " + closed.getMessage());
                }
            } catch (IOException failure) {
                if (running.get()) {
                    errorListener.accept("Peer listener error: " + failure.getMessage());
                }
            }
        }
    }

    private void handle(Socket socket) {
        boolean fileTransfer = false;
        try (socket;
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream()));
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream()))) {
            ProtocolMessage request = Protocol.readMessage(input);
            if (request.type() == MessageType.CHAT) {
                ChatMessage chat = new ChatMessage(
                        UUID.fromString(request.requiredField("messageId")),
                        request.requiredField("sender"),
                        request.requiredField("receiver"),
                        request.requiredField("content"),
                        Instant.parse(request.requiredField("timestamp")));
                if (!localUsername.equals(chat.receiver())) {
                    sendError(output, request, "WRONG_RECEIVER");
                    return;
                }
                chatListener.accept(chat);
                Protocol.writeMessage(output, ProtocolMessage.responseTo(request, MessageType.CHAT)
                        .field("status", "RECEIVED")
                        .build());
            } else if (request.type() == MessageType.FILE_REQUEST) {
                fileTransfer = true;
                socket.setSoTimeout(0);
                fileTransferManager.receive(request, input, output);
            } else {
                sendError(output, request, "UNSUPPORTED_PEER_MESSAGE");
            }
        } catch (Exception failure) {
            // File-transfer failures are already rendered inside the conversation.
            if (!fileTransfer) {
                errorListener.accept("Peer connection error: " + failure.getMessage());
            }
        }
    }

    private void sendError(DataOutputStream output, ProtocolMessage request, String code)
            throws IOException {
        Protocol.writeMessage(output, ProtocolMessage.responseTo(request, MessageType.ERROR)
                .field("code", code)
                .field("message", code)
                .build());
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Listener is already closing.
            }
        }
        acceptExecutor.shutdownNow();
        handlerPool.shutdownNow();
    }
}
