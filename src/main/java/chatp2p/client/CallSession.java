package chatp2p.client;

import chatp2p.protocol.MessageType;
import chatp2p.protocol.Protocol;
import chatp2p.protocol.ProtocolMessage;
import com.github.sarxos.webcam.Webcam;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

/** One direct TCP voice or video call. Audio and JPEG frames never pass through the server. */
public final class CallSession implements AutoCloseable {
    public enum State { RINGING, CONNECTED, ENDED }

    public interface Listener {
        void changed(CallSession call);
        void videoFrame(CallSession call, BufferedImage image, boolean local);
        void ended(CallSession call);
    }

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16_000, 16, 1, true, false);
    private static final int MAX_MEDIA_FRAME = 128 * 1024;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final String peerName;
    private final String localName;
    private final boolean video;
    private final boolean incoming;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch decision = new CountDownLatch(1);
    private final ExecutorService mediaWorkers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "p2p-call-media");
        thread.setDaemon(true);
        return thread;
    });
    private volatile State state = State.RINGING;
    private volatile boolean accepted;
    private volatile boolean muted;
    private volatile String status = "Đang đổ chuông";
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speaker;
    private volatile Webcam webcam;

    CallSession(Socket socket, String localName, String peerName, boolean video,
                boolean incoming, Listener listener) throws IOException {
        this.socket = socket;
        this.localName = localName;
        this.peerName = peerName;
        this.video = video;
        this.incoming = incoming;
        this.listener = listener;
        this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public String peerName() { return peerName; }
    public boolean video() { return video; }
    public boolean incoming() { return incoming; }
    public State state() { return state; }
    public String status() { return status; }
    public boolean muted() { return muted; }
    public void setMuted(boolean value) { muted = value; listener.changed(this); }

    public void accept() {
        if (!incoming || state != State.RINGING) return;
        accepted = true;
        decision.countDown();
    }

    public void reject() {
        if (!incoming || state != State.RINGING) return;
        decision.countDown();
    }

    void handleIncoming(ProtocolMessage invite) {
        listener.changed(this);
        try {
            if (!decision.await(60, TimeUnit.SECONDS) || !accepted) {
                Protocol.writeMessage(output,
                        ProtocolMessage.responseTo(invite, MessageType.CALL_REJECT).build());
                status = "Cuộc gọi bị từ chối hoặc hết thời gian chờ";
                return;
            }
            Protocol.writeMessage(output,
                    ProtocolMessage.responseTo(invite, MessageType.CALL_ACCEPT).build());
            connected();
            readMedia();
        } catch (Exception failure) {
            status = failure.getMessage() == null ? "Cuộc gọi bị gián đoạn" : failure.getMessage();
        } finally {
            close();
        }
    }

    void handleOutgoing() {
        listener.changed(this);
        try {
            Protocol.writeMessage(output, ProtocolMessage.builder(MessageType.CALL_INVITE)
                    .field("sender", localName).field("receiver", peerName)
                    .field("video", Boolean.toString(video)).build());
            socket.setSoTimeout(60_000);
            ProtocolMessage reply = Protocol.readMessage(input);
            if (reply.type() == MessageType.CALL_REJECT) {
                status = "Người nhận đã từ chối cuộc gọi";
                return;
            }
            if (reply.type() != MessageType.CALL_ACCEPT) {
                throw new IOException("Phản hồi cuộc gọi không hợp lệ");
            }
            connected();
            readMedia();
        } catch (Exception failure) {
            status = failure.getMessage() == null ? "Cuộc gọi bị gián đoạn" : failure.getMessage();
        } finally {
            close();
        }
    }

    private void connected() throws IOException {
        socket.setSoTimeout(0);
        state = State.CONNECTED;
        status = video ? "Đang gọi video P2P" : "Đang gọi thoại P2P";
        listener.changed(this);
        mediaWorkers.execute(this::captureAudio);
        if (video) mediaWorkers.execute(this::captureVideo);
    }

    private void captureAudio() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
            microphone = line;
            line.open(AUDIO_FORMAT);
            line.start();
            byte[] pcm = new byte[3_200];
            while (!closed.get() && state == State.CONNECTED) {
                int count = line.read(pcm, 0, pcm.length);
                if (count > 0 && !muted) sendMedia(1, pcm, count);
            }
        } catch (Exception failure) {
            if (!closed.get()) {
                status = "Không mở được microphone: " + failure.getMessage();
                listener.changed(this);
            }
        } finally {
            TargetDataLine line = microphone;
            if (line != null) { line.stop(); line.close(); }
        }
    }

    private void captureVideo() {
        try {
            Webcam camera = Webcam.getDefault();
            if (camera == null) throw new IOException("Không tìm thấy camera");
            webcam = camera;
            camera.open();
            while (!closed.get() && state == State.CONNECTED) {
                BufferedImage image = camera.getImage();
                if (image != null) {
                    BufferedImage scaled = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = scaled.createGraphics();
                    graphics.drawImage(image, 0, 0, 320, 240, null);
                    graphics.dispose();
                    listener.videoFrame(this, scaled, true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    ImageIO.write(scaled, "jpg", bytes);
                    byte[] frame = bytes.toByteArray();
                    if (frame.length <= MAX_MEDIA_FRAME) sendMedia(2, frame, frame.length);
                }
                Thread.sleep(200);
            }
        } catch (Exception failure) {
            if (!closed.get()) {
                status = "Không mở được camera: " + failure.getMessage();
                listener.changed(this);
            }
        } finally {
            Webcam camera = webcam;
            if (camera != null && camera.isOpen()) camera.close();
        }
    }

    private void sendMedia(int type, byte[] data, int length) throws IOException {
        synchronized (output) {
            if (closed.get()) return;
            output.writeByte(type);
            output.writeInt(length);
            output.write(data, 0, length);
            output.flush();
        }
    }

    private void readMedia() throws IOException {
        while (!closed.get()) {
            int type;
            try { type = input.readUnsignedByte(); }
            catch (EOFException ended) { return; }
            int length = input.readInt();
            if (type == 0 && length == 0) return;
            if ((type != 1 && type != 2) || length < 1 || length > MAX_MEDIA_FRAME) {
                throw new IOException("Khung media không hợp lệ");
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            if (type == 1) playAudio(bytes);
            else {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) listener.videoFrame(this, image, false);
            }
        }
    }

    private void playAudio(byte[] pcm) {
        try {
            SourceDataLine line = speaker;
            if (line == null) {
                line = (SourceDataLine) AudioSystem.getLine(
                        new DataLine.Info(SourceDataLine.class, AUDIO_FORMAT));
                line.open(AUDIO_FORMAT);
                line.start();
                speaker = line;
            }
            line.write(pcm, 0, pcm.length);
        } catch (Exception failure) {
            status = "Không phát được âm thanh: " + failure.getMessage();
            listener.changed(this);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (status.startsWith("Đang")) status = "Cuộc gọi đã kết thúc";
        state = State.ENDED;
        decision.countDown();
        // Closing the socket immediately unblocks both peers even if a media write stalls.
        try { socket.close(); } catch (IOException ignored) { }
        TargetDataLine mic = microphone;
        if (mic != null) { mic.stop(); mic.close(); }
        SourceDataLine audio = speaker;
        if (audio != null) { audio.stop(); audio.close(); }
        mediaWorkers.shutdownNow();
        listener.changed(this);
        listener.ended(this);
    }
}
