package chatp2p.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Multi-client TCP server for the central control connection. */
public final class ChatServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 5000;
    public static final int DEFAULT_MAX_CLIENTS = 64;
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 120_000;

    private final int configuredPort;
    private final ExecutorService clientPool;
    private final Logger logger;
    private final ServerEventListener eventListener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong connectionIds = new AtomicLong();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile IOException startupFailure;

    public ChatServer(int port) throws IOException {
        this(port, DEFAULT_MAX_CLIENTS, ServerLogger.create(), ServerEventListener.noOp());
    }

    public ChatServer(int port, ServerEventListener eventListener) throws IOException {
        this(port, DEFAULT_MAX_CLIENTS, ServerLogger.create(), eventListener);
    }

    public ChatServer(int port, int maxClients, Logger logger) {
        this(port, maxClients, logger, ServerEventListener.noOp());
    }

    public ChatServer(
            int port, int maxClients, Logger logger, ServerEventListener eventListener) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        if (maxClients < 1) {
            throw new IllegalArgumentException("maxClients must be positive");
        }
        this.configuredPort = port;
        this.clientPool = Executors.newFixedThreadPool(maxClients, runnable -> {
            Thread thread = new Thread(runnable, "server-client-handler");
            thread.setDaemon(false);
            return thread;
        });
        this.logger = Objects.requireNonNull(logger, "logger");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
    }

    /** Binds the port and runs the accept loop until close() is called. */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }

        try (ServerSocket listener = new ServerSocket()) {
            serverSocket = listener;
            listener.setReuseAddress(true);
            listener.bind(new java.net.InetSocketAddress(configuredPort));
            logger.info("Server started on port " + listener.getLocalPort());
            publish(ServerEvent.server(
                    ServerEvent.Type.SERVER_STARTED,
                    "Server started on port " + listener.getLocalPort()));
            started.countDown();

            while (running.get()) {
                try {
                    Socket clientSocket = listener.accept();
                    configure(clientSocket);
                    activeSockets.add(clientSocket);
                    long connectionId = connectionIds.incrementAndGet();
                    logger.info("Connection " + connectionId + " accepted from "
                            + clientSocket.getRemoteSocketAddress());
                    publish(ServerEvent.client(
                            ServerEvent.Type.CONNECTION_ACCEPTED,
                            connectionId,
                            "",
                            clientSocket.getRemoteSocketAddress().toString(),
                            "TCP connection accepted"));
                    clientPool.execute(() -> {
                        try {
                            new ClientHandler(connectionId, clientSocket, logger, eventListener).run();
                        } finally {
                            activeSockets.remove(clientSocket);
                        }
                    });
                } catch (SocketException socketClosed) {
                    if (running.get()) {
                        throw socketClosed;
                    }
                }
            }
        } catch (IOException failure) {
            if (serverSocket == null || !serverSocket.isBound()) {
                startupFailure = failure;
                started.countDown();
            }
            publish(ServerEvent.server(ServerEvent.Type.ERROR, failure.getMessage()));
            throw failure;
        } finally {
            running.set(false);
            closeServerSocket();
            started.countDown();
            logger.info("Server accept loop stopped");
            publish(ServerEvent.server(ServerEvent.Type.SERVER_STOPPED, "Server stopped"));
        }
    }

    public void awaitStarted(Duration timeout) throws IOException, InterruptedException {
        if (!started.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IOException("Timed out waiting for server startup");
        }
        if (startupFailure != null) {
            throw startupFailure;
        }
        if (serverSocket == null || !serverSocket.isBound()) {
            throw new IOException("Server did not bind a port");
        }
    }

    public int boundPort() {
        ServerSocket listener = serverSocket;
        if (listener == null || !listener.isBound()) {
            throw new IllegalStateException("Server has not started");
        }
        return listener.getLocalPort();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int activeConnectionCount() {
        return activeSockets.size();
    }

    public long totalConnectionCount() {
        return connectionIds.get();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false) && serverSocket == null) {
            clientPool.shutdownNow();
            return;
        }
        closeServerSocket();
        closeActiveSockets();
        clientPool.shutdown();
        try {
            if (!clientPool.awaitTermination(5, TimeUnit.SECONDS)) {
                clientPool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            clientPool.shutdownNow();
        }
        logger.info("Server stopped");
    }

    private void configure(Socket socket) throws SocketException {
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(CLIENT_READ_TIMEOUT_MILLIS);
    }

    private void closeServerSocket() {
        ServerSocket listener = serverSocket;
        if (listener != null && !listener.isClosed()) {
            try {
                listener.close();
            } catch (IOException failure) {
                logger.log(Level.WARNING, "Could not close server socket", failure);
            }
        }
    }

    private void closeActiveSockets() {
        for (Socket socket : activeSockets) {
            try {
                socket.close();
            } catch (IOException failure) {
                logger.log(Level.FINE, "Could not close client socket", failure);
            }
        }
        activeSockets.clear();
    }

    private void publish(ServerEvent event) {
        try {
            eventListener.onEvent(event);
        } catch (RuntimeException listenerFailure) {
            logger.log(Level.WARNING, "Server event listener failed", listenerFailure);
        }
    }
}
