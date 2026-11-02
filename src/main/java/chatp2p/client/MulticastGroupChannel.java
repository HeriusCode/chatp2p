package chatp2p.client;

import chatp2p.model.GroupMessage;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Real UDP multicast channel for LAN group chat, paired with server fallback delivery. */
final class MulticastGroupChannel implements AutoCloseable {
    private static final String GROUP_ADDRESS = "239.255.42.99";
    private static final int PORT = 5051;
    private static final int MAX_PACKET_BYTES = 16 * 1024;

    private final Consumer<GroupMessage> listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<NetworkInterface> joinedInterfaces = new ArrayList<>();
    private volatile MulticastSocket socket;
    private volatile Thread thread;

    MulticastGroupChannel(Consumer<GroupMessage> listener) {
        this.listener = listener;
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        MulticastSocket channel = new MulticastSocket(null);
        try {
            channel.setReuseAddress(true);
            channel.bind(new InetSocketAddress("0.0.0.0", PORT));
            channel.setTimeToLive(1);
            InetAddress group = InetAddress.getByName(GROUP_ADDRESS);
            for (NetworkInterface network : multicastInterfaces()) {
                try {
                    channel.joinGroup(new InetSocketAddress(group, PORT), network);
                    joinedInterfaces.add(network);
                } catch (IOException ignored) {
                    // Another active adapter may still support multicast.
                }
            }
            if (joinedInterfaces.isEmpty()) {
                throw new IOException("No multicast-capable network interface");
            }
            socket = channel;
            thread = new Thread(this::receiveLoop, "udp-multicast-group-chat");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException failure) {
            running.set(false);
            channel.close();
            throw failure;
        }
    }

    void send(GroupMessage message) throws IOException {
        MulticastSocket current = socket;
        if (!running.get() || current == null) {
            throw new IOException("Multicast channel is unavailable");
        }
        byte[] data = encode(message);
        InetAddress group = InetAddress.getByName(GROUP_ADDRESS);
        IOException lastFailure = null;
        boolean sent = false;
        for (NetworkInterface network : List.copyOf(joinedInterfaces)) {
            try {
                synchronized (current) {
                    current.setNetworkInterface(network);
                    current.send(new DatagramPacket(data, data.length, group, PORT));
                }
                sent = true;
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        if (!sent) {
            throw lastFailure == null ? new IOException("Could not send multicast packet") : lastFailure;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET_BYTES];
        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                listener.accept(decode(packet.getData(), packet.getOffset(), packet.getLength()));
            } catch (SocketException closed) {
                if (running.get()) {
                    // Server fallback remains active; terminate the broken channel.
                    running.set(false);
                }
            } catch (IOException | RuntimeException ignored) {
                // Malformed/unrelated UDP datagrams are ignored without killing chat.
            }
        }
    }

    private static byte[] encode(GroupMessage message) throws IOException {
        ProtocolMessage wire = ProtocolMessage.builder(MessageType.GROUP_CHAT)
                .field("messageId", message.messageId().toString())
                .field("groupName", message.groupName())
                .field("sender", message.sender())
                .field("content", message.content())
                .field("timestamp", message.timestamp().toString())
                .field("transport", "UDP_MULTICAST")
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            Protocol.writeMessage(output, wire);
        }
        if (bytes.size() > MAX_PACKET_BYTES) {
            throw new IOException("Multicast message exceeds UDP packet limit");
        }
        return bytes.toByteArray();
    }

    private static GroupMessage decode(byte[] data, int offset, int length) throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(data, offset, length))) {
            ProtocolMessage message = Protocol.readMessage(input);
            if (message.type() != MessageType.GROUP_CHAT
                    || !"UDP_MULTICAST".equals(message.field("transport"))) {
                throw new IOException("Not a multicast group message");
            }
            return new GroupMessage(
                    UUID.fromString(message.requiredField("messageId")),
                    message.requiredField("groupName"),
                    message.requiredField("sender"),
                    message.requiredField("content"),
                    Instant.parse(message.requiredField("timestamp")));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid multicast group message", failure);
        }
    }

    private static List<NetworkInterface> multicastInterfaces() throws SocketException {
        List<NetworkInterface> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (network.isUp() && network.supportsMulticast() && !network.isVirtual()) {
                result.add(network);
            }
        }
        return result;
    }

    @Override
    public void close() {
        running.set(false);
        MulticastSocket current = socket;
        if (current != null) {
            InetSocketAddress group;
            try {
                group = new InetSocketAddress(InetAddress.getByName(GROUP_ADDRESS), PORT);
                for (NetworkInterface network : List.copyOf(joinedInterfaces)) {
                    try {
                        current.leaveGroup(group, network);
                    } catch (IOException ignored) {
                        // Socket close below is authoritative.
                    }
                }
            } catch (IOException ignored) {
                // Socket close below is authoritative.
            }
            current.close();
        }
        joinedInterfaces.clear();
        Thread currentThread = thread;
        if (currentThread != null) {
            currentThread.interrupt();
        }
    }
}
