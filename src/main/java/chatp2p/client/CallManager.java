package chatp2p.client;

import chatp2p.model.PeerInfo;
import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Restricts each client to one active direct call. */
final class CallManager implements AutoCloseable {
    private final String username;
    private final CallSession.Listener listener;
    private final AtomicReference<CallSession> active = new AtomicReference<>();
    private final ExecutorService setup = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "p2p-call-setup");
        thread.setDaemon(true);
        return thread;
    });

    CallManager(String username, CallSession.Listener listener) {
        this.username = username;
        this.listener = new CallSession.Listener() {
            public void changed(CallSession call) {
                if (call.state() == CallSession.State.ENDED) active.compareAndSet(call, null);
                listener.changed(call);
            }
            public void videoFrame(CallSession call, java.awt.image.BufferedImage image,
                                   boolean local) {
                listener.videoFrame(call, image, local);
            }
            public void ended(CallSession call) {
                active.compareAndSet(call, null);
                listener.ended(call);
            }
        };
    }

    CallSession start(PeerInfo peer, boolean video) throws IOException {
        if (active.get() != null) throw new IOException("Đang có một cuộc gọi khác");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(peer.host(), peer.port()), 5_000);
            socket.setTcpNoDelay(true);
            CallSession call = new CallSession(socket, username, peer.username(), video, false, listener);
            if (!active.compareAndSet(null, call)) {
                call.close();
                throw new IOException("Đang có một cuộc gọi khác");
            }
            setup.execute(call::handleOutgoing);
            return call;
        } catch (IOException failure) {
            socket.close();
            throw failure;
        }
    }

    void incoming(ProtocolMessage invite, Socket socket, DataInputStream input,
                  DataOutputStream output) throws IOException {
        if (!username.equals(invite.requiredField("receiver"))) {
            Protocol.writeMessage(output, ProtocolMessage.responseTo(invite, MessageType.CALL_REJECT)
                    .field("reason", "Wrong receiver").build());
            return;
        }
        CallSession call = new CallSession(socket, username, invite.requiredField("sender"),
                Boolean.parseBoolean(invite.requiredField("video")), true, listener);
        if (!active.compareAndSet(null, call)) {
            Protocol.writeMessage(output, ProtocolMessage.responseTo(invite, MessageType.CALL_REJECT)
                    .field("reason", "Busy").build());
            return;
        }
        call.handleIncoming(invite);
    }

    @Override public void close() {
        CallSession call = active.getAndSet(null);
        if (call != null) call.close();
        setup.shutdownNow();
    }
}
