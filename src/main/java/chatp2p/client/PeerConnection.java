package chatp2p.client;

import chatp2p.model.ChatMessage;
import chatp2p.model.PeerInfo;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Opens short-lived direct TCP connections for peer chat messages. */
final class PeerConnection {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private PeerConnection() {
    }

    static void sendChat(PeerInfo peer, ChatMessage message) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.host(), peer.port()), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            try (DataOutputStream output = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream input = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()))) {
                ProtocolMessage wireMessage = ProtocolMessage.builder(MessageType.CHAT)
                        .field("messageId", message.messageId().toString())
                        .field("sender", message.sender())
                        .field("receiver", message.receiver())
                        .field("content", message.content())
                        .field("timestamp", message.timestamp().toString())
                        .build();
                Protocol.writeMessage(output, wireMessage);
                ProtocolMessage response = Protocol.readMessage(input);
                if (response.type() == MessageType.ERROR) {
                    throw new IOException("Peer rejected chat: " + response.field("message"));
                }
                if (response.type() != MessageType.CHAT
                        || !"RECEIVED".equals(response.field("status"))) {
                    throw new IOException("Invalid chat acknowledgement from peer");
                }
            }
        }
    }
}
