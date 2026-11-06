package chatp2p.view;

import com.github.sarxos.webcam.Webcam;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Rational;

/** Live camera preview and direct photo/video capture for a P2P conversation. */
final class CameraCaptureDialog extends JDialog {
    private static final int FPS = 10;
    private static final int MAX_SECONDS = 30;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Consumer<Path> onCaptured;
    private final Runnable onClosed;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "camera-capture");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean previewQueued = new AtomicBoolean();
    private final JLabel preview = new JLabel("Đang mở camera...", SwingConstants.CENTER);
    private final JLabel status = new JLabel("Đang tìm camera");
    private final JButton photoButton = new JButton("Chụp ảnh & gửi");
    private final JButton recordButton = new JButton("Bắt đầu quay");
    private final JButton stopButton = new JButton("Dừng & gửi video");

    private Webcam webcam;
    private ScheduledFuture<?> previewTask;
    private BufferedImage latestFrame;
    private SeekableByteChannel videoChannel;
    private AWTSequenceEncoder videoEncoder;
    private Path videoFile;
    private boolean recording;
    private int recordedFrames;
    private long recordingStarted;

    CameraCaptureDialog(JFrame owner, String receiver, Consumer<Path> onCaptured,
                        Runnable chooseExistingVideo, Runnable onClosed) {
        super(owner, "Camera • gửi cho " + receiver, true);
        this.onCaptured = onCaptured;
        this.onClosed = onClosed;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(580, 510));
        setSize(640, 540);
        setLocationRelativeTo(owner);

        preview.setOpaque(true);
        preview.setBackground(new Color(18, 30, 50));
        preview.setForeground(Color.WHITE);
        preview.setPreferredSize(new Dimension(560, 360));
        preview.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        status.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));

        photoButton.setEnabled(false);
        recordButton.setEnabled(false);
        stopButton.setEnabled(false);
        stylePrimary(photoButton);
        stylePrimary(recordButton);
        stylePrimary(stopButton);
        JButton existingButton = new JButton("Chọn video có sẵn");
        JButton cancelButton = new JButton("Hủy");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        actions.add(photoButton);
        actions.add(recordButton);
        actions.add(stopButton);
        actions.add(existingButton);
        actions.add(cancelButton);

        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.NORTH);
        south.add(actions, BorderLayout.SOUTH);
        add(preview, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        photoButton.addActionListener(event -> capturePhoto());
        recordButton.addActionListener(event -> beginRecording());
        stopButton.addActionListener(event -> worker.execute(() -> finishRecording(true)));
        existingButton.addActionListener(event -> {
            dispose();
            chooseExistingVideo.run();
        });
        cancelButton.addActionListener(event -> dispose());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closeCamera();
                onClosed.run();
            }
        });
        worker.execute(this::openCamera);
    }

    private static void stylePrimary(JButton button) {
        button.setForeground(Color.WHITE);
        button.setBackground(UiTheme.PRIMARY);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
    }

    private void openCamera() {
        try {
            Webcam found = Webcam.getDefault();
            if (found == null || !found.open()) {
                throw new IOException("Không tìm thấy camera hoặc camera đang được ứng dụng khác sử dụng");
            }
            webcam = found;
            if (closed.get()) {
                found.close();
                return;
            }
            SwingUtilities.invokeLater(() -> {
                photoButton.setEnabled(true);
                recordButton.setEnabled(true);
                status.setText("Camera sẵn sàng • video tối đa " + MAX_SECONDS + " giây, không âm thanh");
            });
            previewTask = worker.scheduleWithFixedDelay(this::grabFrame, 0,
                    1000 / FPS, TimeUnit.MILLISECONDS);
        } catch (Exception | LinkageError failure) {
            showFailure("Không thể mở camera: " + message(failure));
        }
    }

    private void grabFrame() {
        if (closed.get() || webcam == null) {
            return;
        }
        try {
            BufferedImage frame = webcam.getImage();
            if (frame == null) {
                return;
            }
            latestFrame = frame;
            if (previewQueued.compareAndSet(false, true)) {
                BufferedImage scaled = scale(frame, 540, 350);
                SwingUtilities.invokeLater(() -> {
                    preview.setIcon(new ImageIcon(scaled));
                    preview.setText("");
                    previewQueued.set(false);
                });
            }
            if (recording) {
                videoEncoder.encodeImage(scale(frame, 320, 240));
                recordedFrames++;
                long seconds = TimeUnit.NANOSECONDS.toSeconds(
                        System.nanoTime() - recordingStarted);
                SwingUtilities.invokeLater(() -> status.setText(
                        "Đang quay: " + seconds + " / " + MAX_SECONDS + " giây"));
                if (seconds >= MAX_SECONDS) {
                    finishRecording(true);
                }
            }
        } catch (Exception | LinkageError failure) {
            if (previewTask != null) {
                previewTask.cancel(false);
            }
            if (recording) {
                finishRecording(false);
            }
            SwingUtilities.invokeLater(() -> {
                photoButton.setEnabled(false);
                recordButton.setEnabled(false);
                stopButton.setEnabled(false);
            });
            showFailure("Camera ngừng hoạt động: " + message(failure));
        }
    }

    private void capturePhoto() {
        photoButton.setEnabled(false);
        worker.execute(() -> {
            try {
                if (latestFrame == null) {
                    throw new IOException("Camera chưa có hình ảnh");
                }
                Path file = newCaptureFile("png");
                if (!ImageIO.write(latestFrame, "PNG", file.toFile())) {
                    throw new IOException("Không thể ghi ảnh PNG");
                }
                complete(file);
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> photoButton.setEnabled(true));
                showFailure("Không thể chụp ảnh: " + message(failure));
            }
        });
    }

    private void beginRecording() {
        recordButton.setEnabled(false);
        photoButton.setEnabled(false);
        worker.execute(() -> {
            try {
                videoFile = newCaptureFile("mp4");
                videoChannel = NIOUtils.writableFileChannel(videoFile.toString());
                videoEncoder = new AWTSequenceEncoder(videoChannel, Rational.R(FPS, 1));
                recordedFrames = 0;
                recordingStarted = System.nanoTime();
                recording = true;
                SwingUtilities.invokeLater(() -> {
                    stopButton.setEnabled(true);
                    status.setText("Đang quay: 0 / " + MAX_SECONDS + " giây");
                });
            } catch (Exception failure) {
                closeVideoFile(true);
                SwingUtilities.invokeLater(() -> {
                    recordButton.setEnabled(true);
                    photoButton.setEnabled(true);
                });
                showFailure("Không thể bắt đầu quay: " + message(failure));
            }
        });
    }

    private void finishRecording(boolean send) {
        if (!recording) {
            return;
        }
        recording = false;
        try {
            if (recordedFrames == 0) {
                throw new IOException("Chưa ghi được khung hình video");
            }
            videoEncoder.finish();
            Path finished = videoFile;
            closeVideoFile(false);
            if (send && !closed.get()) {
                complete(finished);
            } else {
                Files.deleteIfExists(finished);
            }
        } catch (Exception failure) {
            closeVideoFile(true);
            showFailure("Không thể lưu video: " + message(failure));
        } finally {
            SwingUtilities.invokeLater(() -> {
                stopButton.setEnabled(false);
                if (!closed.get()) {
                    recordButton.setEnabled(true);
                    photoButton.setEnabled(true);
                }
            });
        }
    }

    private void closeVideoFile(boolean delete) {
        if (videoChannel != null) {
            NIOUtils.closeQuietly(videoChannel);
            videoChannel = null;
        }
        videoEncoder = null;
        if (delete && videoFile != null) {
            try {
                Files.deleteIfExists(videoFile);
            } catch (IOException ignored) {
                // The incomplete file can be removed manually if Windows still holds it.
            }
        }
        videoFile = null;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double factor = Math.min(width / (double) source.getWidth(),
                    height / (double) source.getHeight());
            int drawnWidth = (int) Math.round(source.getWidth() * factor);
            int drawnHeight = (int) Math.round(source.getHeight() * factor);
            graphics.drawImage(source, (width - drawnWidth) / 2,
                    (height - drawnHeight) / 2, drawnWidth, drawnHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static Path newCaptureFile(String extension) throws IOException {
        Path folder = Path.of("data", "captured_media");
        Files.createDirectories(folder);
        return folder.resolve("camera-" + FILE_TIME.format(LocalDateTime.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "." + extension);
    }

    private void complete(Path file) {
        SwingUtilities.invokeLater(() -> {
            if (closed.get()) {
                return;
            }
            dispose();
            onCaptured.accept(file);
        });
    }

    private void showFailure(String text) {
        SwingUtilities.invokeLater(() -> {
            if (!closed.get()) {
                status.setText(text);
                JOptionPane.showMessageDialog(this, text, "Camera", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void closeCamera() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (previewTask != null) {
            previewTask.cancel(false);
        }
        worker.execute(() -> {
            if (recording) {
                finishRecording(false);
            } else if (videoChannel != null) {
                closeVideoFile(true);
            }
            if (webcam != null && webcam.isOpen()) {
                webcam.close();
            }
            worker.shutdown();
        });
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
