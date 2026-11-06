package chatp2p.view;

import chatp2p.client.CallSession;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicButtonUI;

/** Separate call window so chat stays usable while talking. */
final class CallFrame extends JFrame {
    private final CallSession call;
    private final JLabel status = new JLabel("Đang đổ chuông", SwingConstants.CENTER);
    private final JLabel remoteVideo = new JLabel("Đang chờ hình ảnh...", SwingConstants.CENTER);
    private final JLabel localVideo = new JLabel("Camera của bạn", SwingConstants.CENTER);
    private final JButton accept = new FilledButton("Nhận cuộc gọi",
            new Color(18, 153, 87), CallIcons.PHONE);
    private final JButton reject = new FilledButton("Từ chối",
            new Color(218, 49, 57), CallIcons.PHONE);
    private final JButton hangup = new FilledButton("Kết thúc",
            new Color(218, 49, 57), CallIcons.PHONE);
    private final JButton mute = new FilledButton("Tắt mic",
            new Color(0, 105, 225), null);

    CallFrame(CallSession call) {
        super((call.video() ? "Gọi video • " : "Gọi thoại • ") + call.peerName());
        this.call = call;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(call.video() ? 680 : 440, call.video() ? 560 : 320);
        setMinimumSize(new Dimension(390, 270));
        setLocationByPlatform(true);

        JLabel name = new JLabel(call.peerName(), SwingConstants.CENTER);
        name.setFont(new Font("Segoe UI", Font.BOLD, 24));
        status.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        JPanel heading = new JPanel(new BorderLayout(0, 8));
        heading.setBackground(Color.WHITE);
        heading.setBorder(BorderFactory.createEmptyBorder(20, 15, 15, 15));
        heading.add(name, BorderLayout.NORTH);
        heading.add(status, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        if (call.video()) {
            JPanel videoArea = new JPanel(new BorderLayout(8, 8));
            videoArea.setBackground(new Color(19, 35, 62));
            videoArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            remoteVideo.setForeground(Color.WHITE);
            localVideo.setForeground(Color.WHITE);
            localVideo.setPreferredSize(new Dimension(160, 120));
            videoArea.add(remoteVideo, BorderLayout.CENTER);
            videoArea.add(localVideo, BorderLayout.EAST);
            add(videoArea, BorderLayout.CENTER);
        } else {
            JLabel avatar = new JLabel(call.peerName().substring(0, 1).toUpperCase(),
                    SwingConstants.CENTER);
            avatar.setOpaque(true);
            avatar.setBackground(new Color(219, 234, 254));
            avatar.setForeground(new Color(0, 94, 235));
            avatar.setFont(new Font("Segoe UI", Font.BOLD, 58));
            add(avatar, BorderLayout.CENTER);
        }

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 14));
        actions.setBackground(Color.WHITE);
        actions.add(accept);
        actions.add(reject);
        actions.add(mute);
        actions.add(hangup);
        add(actions, BorderLayout.SOUTH);

        accept.addActionListener(event -> call.accept());
        reject.addActionListener(event -> call.reject());
        hangup.addActionListener(event -> {
            if (call.state() == CallSession.State.ENDED) dispose();
            else call.close();
        });
        mute.addActionListener(event -> call.setMuted(!call.muted()));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { call.close(); }
        });
        updateState();
    }

    void updateState() {
        status.setText(call.status());
        boolean ringing = call.state() == CallSession.State.RINGING;
        accept.setVisible(ringing && call.incoming());
        reject.setVisible(ringing && call.incoming());
        mute.setVisible(call.state() == CallSession.State.CONNECTED);
        mute.setText(call.muted() ? "Bật mic" : "Tắt mic");
        hangup.setVisible(!(ringing && call.incoming()));
        hangup.setText(call.state() == CallSession.State.ENDED ? "Đóng" : "Kết thúc");
    }

    void showFrame(BufferedImage frame, boolean local) {
        if (!call.video() || call.state() == CallSession.State.ENDED) return;
        JLabel target = local ? localVideo : remoteVideo;
        int width = local ? 155 : Math.max(320, remoteVideo.getWidth());
        int height = local ? 115 : Math.max(240, remoteVideo.getHeight());
        Image scaled = frame.getScaledInstance(width, height, Image.SCALE_FAST);
        target.setIcon(new ImageIcon(scaled));
        target.setText("");
    }

    private static final class FilledButton extends JButton {
        private final Color fill;

        private FilledButton(String text, Color fill, javax.swing.Icon icon) {
            super(text, icon);
            this.fill = fill;
            setUI(new BasicButtonUI());
            setForeground(Color.WHITE);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setIconTextGap(7);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(11, 16, 11, 16));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(getModel().isPressed() ? fill.darker() : fill);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            } finally {
                canvas.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
