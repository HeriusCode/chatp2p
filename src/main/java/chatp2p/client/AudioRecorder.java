package chatp2p.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/** Records a microphone clip as a mono 16 kHz WAV file using only Java Sound. */
public final class AudioRecorder implements AutoCloseable {
    private static final AudioFormat FORMAT =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000, 16, 1, 2, 16_000, false);

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicReference<IOException> writerFailure = new AtomicReference<>();
    private volatile TargetDataLine line;
    private volatile Thread writerThread;
    private volatile Path outputFile;

    public synchronized Path start() throws IOException {
        if (!recording.compareAndSet(false, true)) {
            throw new IllegalStateException("Đang thu âm");
        }
        writerFailure.set(null);
        Path output = Files.createTempFile("chatp2p-audio-", ".wav");
        output.toFile().deleteOnExit();
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                throw new IOException("Máy không hỗ trợ định dạng thu âm PCM 16 kHz");
            }
            TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(FORMAT);
            line = microphone;
            outputFile = output;
            Thread writer = new Thread(() -> writeWave(microphone, output), "audio-recorder");
            writer.setDaemon(true);
            writerThread = writer;
            microphone.start();
            writer.start();
            return output;
        } catch (LineUnavailableException failure) {
            recording.set(false);
            Files.deleteIfExists(output);
            throw new IOException("Không thể mở microphone: " + failure.getMessage(), failure);
        } catch (IOException | RuntimeException failure) {
            recording.set(false);
            Files.deleteIfExists(output);
            throw failure;
        }
    }

    public synchronized Path stop() throws IOException {
        if (!recording.getAndSet(false)) {
            throw new IllegalStateException("Chưa bắt đầu thu âm");
        }
        TargetDataLine microphone = line;
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        Thread writer = writerThread;
        if (writer != null) {
            try {
                writer.join(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Đã gián đoạn khi hoàn tất thu âm", interrupted);
            }
        }
        line = null;
        writerThread = null;
        IOException failure = writerFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        Path result = outputFile;
        outputFile = null;
        if (result == null || !Files.isRegularFile(result) || Files.size(result) <= 44) {
            throw new IOException("Không thu được dữ liệu âm thanh");
        }
        return result;
    }

    public boolean isRecording() {
        return recording.get();
    }

    @Override
    public synchronized void close() {
        if (recording.get()) {
            try {
                stop();
            } catch (IOException | IllegalStateException ignored) {
                // Closing the window only needs to release the microphone.
            }
        }
    }

    private void writeWave(TargetDataLine microphone, Path output) {
        try (AudioInputStream input = new AudioInputStream(microphone)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output.toFile());
        } catch (IOException failure) {
            if (recording.get()) {
                writerFailure.compareAndSet(null, failure);
            }
        }
    }
}
