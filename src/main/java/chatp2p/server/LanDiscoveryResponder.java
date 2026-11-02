package chatp2p.server;

import chatp2p.network.LanDiscoveryProtocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Replies to UDP broadcast probes so clients can find the TCP server in a LAN. */
final class LanDiscoveryResponder implements AutoCloseable {
    private final int tcpPort;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DatagramSocket socket;
    private volatile Thread thread;

    LanDiscoveryResponder(int tcpPort, Logger logger) {
        this.tcpPort = tcpPort;
        this.logger = logger;
    }

    void start() throws SocketException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        DatagramSocket listener = new DatagramSocket(null);
        try {
            listener.setReuseAddress(true);
            listener.setBroadcast(true);
            listener.bind(new InetSocketAddress("0.0.0.0", LanDiscoveryProtocol.port()));
            socket = listener;
            thread = new Thread(this::receiveLoop, "udp-broadcast-discovery");
            thread.setDaemon(true);
            thread.start();
            logger.info("UDP broadcast discovery listening on port "
                    + LanDiscoveryProtocol.port());
        } catch (SocketException failure) {
            running.set(false);
            listener.close();
            throw failure;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[LanDiscoveryProtocol.MAX_PACKET_BYTES];
        while (running.get()) {
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);
                String requestId;
                try {
                    requestId = LanDiscoveryProtocol.parseRequest(
                            request.getData(), request.getOffset(), request.getLength());
                } catch (IllegalArgumentException unrelatedPacket) {
                    continue;
                }
                byte[] response = LanDiscoveryProtocol.response(requestId, tcpPort);
                socket.send(new DatagramPacket(
                        response, response.length, request.getAddress(), request.getPort()));
            } catch (SocketException closed) {
                if (running.get()) {
                    logger.warning("UDP discovery socket error: " + closed.getMessage());
                }
            } catch (IOException failure) {
                if (running.get()) {
                    logger.warning("UDP discovery error: " + failure.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        DatagramSocket current = socket;
        if (current != null) {
            current.close();
        }
        Thread currentThread = thread;
        if (currentThread != null) {
            currentThread.interrupt();
        }
    }
}
