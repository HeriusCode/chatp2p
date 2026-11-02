package chatp2p.client;

import chatp2p.network.LanDiscoveryProtocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Finds a ChatP2P server by sending UDP broadcast probes on active LAN adapters. */
public final class LanServerDiscovery {
    private static final long LOCAL_PREFERENCE_GRACE_MILLIS = 300;

    private LanServerDiscovery() {
    }

    public static ServerEndpoint discover(Duration timeout) throws IOException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        String requestId = UUID.randomUUID().toString();
        byte[] request = LanDiscoveryProtocol.request(requestId);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (int attempt = 0; attempt < 3; attempt++) {
                for (InetAddress target : broadcastTargets()) {
                    socket.send(new DatagramPacket(
                            request, request.length, target, LanDiscoveryProtocol.port()));
                }
            }

            byte[] response = new byte[LanDiscoveryProtocol.MAX_PACKET_BYTES];
            long deadline = System.nanoTime() + timeout.toNanos();
            ServerEndpoint firstLanServer = null;
            while (System.nanoTime() < deadline) {
                long remainingMillis = Math.max(
                        1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                socket.setSoTimeout((int) Math.min(remainingMillis, Integer.MAX_VALUE));
                DatagramPacket packet = new DatagramPacket(response, response.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException timeoutFailure) {
                    break;
                }

                try {
                    ServerEndpoint endpoint = new ServerEndpoint(
                            packet.getAddress().getHostAddress(),
                            LanDiscoveryProtocol.parseResponse(
                                    packet.getData(), packet.getLength(), requestId));
                    if (isLocalAddress(packet.getAddress())) {
                        return endpoint;
                    }
                    if (firstLanServer == null) {
                        firstLanServer = endpoint;
                        long graceDeadline = System.nanoTime()
                                + Duration.ofMillis(LOCAL_PREFERENCE_GRACE_MILLIS).toNanos();
                        deadline = Math.min(deadline, graceDeadline);
                    }
                } catch (IllegalArgumentException invalidResponse) {
                    // Ignore unrelated UDP traffic and keep waiting for a valid responder.
                }
            }
            if (firstLanServer != null) {
                return firstLanServer;
            }
            throw new IOException("Không tìm thấy ChatP2P Server trong mạng LAN");
        }
    }

    private static boolean isLocalAddress(InetAddress address) throws IOException {
        return address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null;
    }

    private static Set<InetAddress> broadcastTargets() throws IOException {
        Set<InetAddress> targets = new LinkedHashSet<>();
        targets.add(InetAddress.getByName("127.0.0.1"));
        targets.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null) {
                    targets.add(address.getBroadcast());
                }
            }
        }
        return targets;
    }

    public record ServerEndpoint(String host, int port) {
    }
}
