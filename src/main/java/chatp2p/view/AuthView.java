package chatp2p.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;

/** Shared visual components and layouts for modern Zalo/Messenger-inspired auth pages. */
final class AuthView {
    private AuthView() {
    }

    /** Creates the outer window shell with a centered modern card. */
    static JPanel createShell(JPanel formCard, String title, String description) {
        JPanel outer = UiTheme.panel(new GridBagLayout(), UiTheme.CHAT_BG);
        outer.setBorder(new EmptyBorder(16, 16, 16, 16));

        AuthCardPanel card = new AuthCardPanel(24);
        card.setPreferredSize(new Dimension(880, 570));
        card.setMinimumSize(new Dimension(860, 550));

        // Left Branding Banner
        card.add(new LeftBrandingPanel(24, title, description), BorderLayout.WEST);

        // Right Form Card
        JPanel formWrapper = UiTheme.panel(new GridBagLayout(), Color.WHITE);
        formWrapper.setBorder(new EmptyBorder(20, 28, 20, 28));
        formWrapper.add(formCard);
        card.add(formWrapper, BorderLayout.CENTER);

        outer.add(card);
        return outer;
    }

    /** Creates a modern, padded form container. */
    static JPanel form(String headingText, String subtitleText) {
        JPanel form = UiTheme.panel(null, Color.WHITE);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setPreferredSize(new Dimension(440, 500));

        JLabel heading = UiTheme.label(headingText, new Font("Segoe UI", Font.BOLD, 24), UiTheme.NAVY);
        JLabel subtitle = UiTheme.label(subtitleText, new Font("Segoe UI", Font.PLAIN, 13), UiTheme.SLATE);
        UiTheme.leftAlign(heading);
        UiTheme.leftAlign(subtitle);

        form.add(heading);
        UiTheme.addVerticalGap(form, 4);
        form.add(subtitle);
        UiTheme.addVerticalGap(form, 16);
        return form;
    }

    /** Sleek horizontal server connection box saving vertical screen space. */
    static JPanel createServerBar(JTextField hostField, JTextField portField, JButton discoverButton) {
        JPanel container = UiTheme.panel(new BorderLayout(), new Color(248, 250, 252));
        container.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(new Color(226, 232, 240), 12),
                new EmptyBorder(10, 12, 10, 12)));
        container.setPreferredSize(new Dimension(440, 96));
        container.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
        container.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel titleRow = UiTheme.panel(
                new FlowLayout(FlowLayout.LEFT, 6, 0), new Color(248, 250, 252));
        titleRow.setPreferredSize(new Dimension(1, 20));
        titleRow.add(new FeatureIcon(IconKind.BROADCAST, UiTheme.PRIMARY, 20));
        titleRow.add(UiTheme.label(
                "KẾT NỐI SERVER", new Font("Segoe UI", Font.BOLD, 11), UiTheme.SLATE));
        container.add(titleRow, BorderLayout.NORTH);

        JPanel row = UiTheme.panel(new BorderLayout(8, 0), new Color(248, 250, 252));
        row.setBorder(new EmptyBorder(6, 0, 0, 0));

        // Host Input
        styleModernInput(hostField, "localhost");
        hostField.setPreferredSize(new Dimension(190, 44));
        hostField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        // Colon label
        JLabel colon = UiTheme.label(":", new Font("Segoe UI", Font.BOLD, 14), UiTheme.SLATE);

        // Port Input
        styleModernInput(portField, "5000");
        portField.setPreferredSize(new Dimension(78, 44));
        portField.setMaximumSize(new Dimension(78, 44));

        JPanel hostPortBox = UiTheme.panel(new BorderLayout(4, 0), new Color(248, 250, 252));
        hostPortBox.add(hostField, BorderLayout.CENTER);
        hostPortBox.add(colon, BorderLayout.EAST);

        JPanel middleBox = UiTheme.panel(new BorderLayout(6, 0), new Color(248, 250, 252));
        middleBox.add(hostPortBox, BorderLayout.CENTER);
        middleBox.add(portField, BorderLayout.EAST);

        // Small discover button
        discoverButton.setFont(new Font("Segoe UI", Font.BOLD, 11));
        discoverButton.setForeground(UiTheme.PRIMARY);
        discoverButton.setBackground(new Color(231, 243, 255));
        discoverButton.setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(new Color(191, 219, 254), 10),
                new EmptyBorder(4, 10, 4, 10)));
        discoverButton.setFocusPainted(false);
        discoverButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        discoverButton.setPreferredSize(new Dimension(108, 44));

        row.add(middleBox, BorderLayout.CENTER);
        row.add(discoverButton, BorderLayout.EAST);

        container.add(row, BorderLayout.CENTER);
        return container;
    }

    /** Adds a field with label and modern focus glow styling. */
    static void addField(JPanel form, String labelText, JTextField field) {
        JLabel label = UiTheme.label(labelText, new Font("Segoe UI", Font.BOLD, 11), UiTheme.SLATE);
        UiTheme.leftAlign(label);
        styleModernInput(field, "");
        UiTheme.leftAlign(field);

        form.add(label);
        UiTheme.addVerticalGap(form, 4);
        form.add(field);
        UiTheme.addVerticalGap(form, 10);
    }

    /** Styles a modern textfield with rounded borders and focus highlight. */
    static void styleModernInput(JTextField field, String placeholder) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        field.setForeground(UiTheme.NAVY);
        field.setBackground(new Color(248, 250, 252));
        field.setCaretColor(UiTheme.PRIMARY);
        field.setSelectionColor(new Color(191, 219, 254));
        field.setSelectedTextColor(UiTheme.NAVY);
        Color normalBorder = new Color(226, 232, 240);
        Color focusBorder = UiTheme.PRIMARY;
        field.setBorder(BorderFactory.createCompoundBorder(
                new FocusRoundedBorder(normalBorder, focusBorder, 10),
                new EmptyBorder(9, 12, 9, 12)));
        field.setPreferredSize(new Dimension(320, 44));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBackground(Color.WHITE);
                field.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBackground(new Color(248, 250, 252));
                field.repaint();
            }
        });
    }

    /** Styles a primary pill action button with hover color. */
    static void styleModernPrimaryButton(JButton button) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setForeground(Color.WHITE);
        button.setBackground(UiTheme.PRIMARY);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(Integer.MAX_VALUE, 40));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Creates a horizontal switcher row: "Chưa có tài khoản? [Đăng ký ngay]". */
    static JPanel createSwitcherRow(String promptText, JButton linkButton) {
        JPanel row = UiTheme.panel(new FlowLayout(FlowLayout.CENTER, 4, 0), Color.WHITE);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JLabel prompt = UiTheme.label(promptText, new Font("Segoe UI", Font.PLAIN, 12), UiTheme.SLATE);

        linkButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        linkButton.setForeground(UiTheme.PRIMARY);
        linkButton.setBorderPainted(false);
        linkButton.setContentAreaFilled(false);
        linkButton.setFocusPainted(false);
        linkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkButton.setBorder(new EmptyBorder(0, 0, 0, 0));

        row.add(prompt);
        row.add(linkButton);
        return row;
    }

    static void addProgressAndStatus(
            JPanel form, JProgressBar progressBar, JLabel statusLabel) {
        UiTheme.addVerticalGap(form, 6);
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setBorderPainted(false);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 3));
        progressBar.setForeground(UiTheme.PRIMARY);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(progressBar);

        UiTheme.addVerticalGap(form, 4);
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(statusLabel);
    }

    static int validate(String host, String portText, String username, String password) {
        if (host.isBlank()) {
            throw new IllegalArgumentException("Server host không được để trống.");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("Tên đăng nhập không được để trống.");
        }
        if (password.length() < 6 || password.length() > 128) {
            throw new IllegalArgumentException("Mật khẩu phải có 6-128 ký tự.");
        }
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Port phải nằm trong khoảng 1-65535.");
            }
            return port;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Port phải là một số nguyên.", invalid);
        }
    }

    static void showStatus(JLabel label, String message, Color color) {
        label.setForeground(color);
        label.setText("<html>" + escape(message) + "</html>");
    }

    static String friendly(Throwable failure) {
        return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Không thể kết nối tới Server." : failure.getMessage();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Rounded container for the entire floating card. */
    static final class AuthCardPanel extends JPanel {
        private final int arc;

        AuthCardPanel(int arc) {
            this.arc = arc;
            setLayout(new BorderLayout());
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 15));
                g2.fillRoundRect(2, 4, getWidth() - 4, getHeight() - 4, arc, arc);
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.setColor(new Color(226, 232, 240));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** Beautiful left-hand branding panel with gradient background and feature cards. */
    static final class LeftBrandingPanel extends JPanel {
        private final int arc;

        LeftBrandingPanel(int arc, String title, String description) {
            this.arc = arc;
            setLayout(new BorderLayout());
            setOpaque(false);
            setPreferredSize(new Dimension(350, 0));
            setBorder(new EmptyBorder(34, 30, 26, 30));

            // Top: App Icon & Name
            JPanel top = UiTheme.panel(null, new Color(0, 0, 0, 0));
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

            JPanel logo = new BrandLogoIcon();
            logo.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel appName = UiTheme.label("Chat P2P", new Font("Segoe UI", Font.BOLD, 22), Color.WHITE);
            appName.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel badge = UiTheme.label("  MẠNG HYBRID P2P + TCP  ", new Font("Segoe UI", Font.BOLD, 9), new Color(191, 219, 254));
            badge.setOpaque(true);
            badge.setBackground(new Color(255, 255, 255, 30));
            badge.setBorder(new EmptyBorder(3, 6, 3, 6));
            badge.setAlignmentX(Component.LEFT_ALIGNMENT);

            top.add(logo);
            UiTheme.addVerticalGap(top, 8);
            top.add(appName);
            UiTheme.addVerticalGap(top, 4);
            top.add(badge);

            // Center: Features Bullets
            JPanel center = UiTheme.panel(null, new Color(0, 0, 0, 0));
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            UiTheme.addVerticalGap(center, 24);

            JLabel heading = UiTheme.label(
                    "<html><b>Trò chuyện & Truyền file</b><br>trực tiếp giữa hai máy</html>",
                    new Font("Segoe UI", Font.PLAIN, 15), Color.WHITE);
            heading.setAlignmentX(Component.LEFT_ALIGNMENT);
            center.add(heading);
            UiTheme.addVerticalGap(center, 18);

            center.add(createFeatureItem(
                    IconKind.LIGHTNING, new Color(253, 224, 71),
                    "Chat 1-1 riêng tư qua TCP socket"));
            UiTheme.addVerticalGap(center, 10);
            center.add(createFeatureItem(
                    IconKind.FOLDER, new Color(125, 211, 252),
                    "Truyền file không giới hạn, mã SHA-256"));
            UiTheme.addVerticalGap(center, 10);
            center.add(createFeatureItem(
                    IconKind.SHIELD, new Color(134, 239, 172),
                    "Bảo mật tài khoản với PBKDF2 salt"));
            UiTheme.addVerticalGap(center, 10);
            center.add(createFeatureItem(
                    IconKind.BROADCAST, new Color(251, 146, 60),
                    "Tự tìm máy chủ qua UDP Broadcast"));

            // Bottom: Tech stack info
            JLabel footer = UiTheme.label(
                    "Java 17 • PostgreSQL chat history",
                    new Font("Segoe UI", Font.PLAIN, 11), new Color(148, 163, 184));
            footer.setAlignmentX(Component.LEFT_ALIGNMENT);

            add(top, BorderLayout.NORTH);
            add(center, BorderLayout.CENTER);
            add(footer, BorderLayout.SOUTH);
        }

        private JPanel createFeatureItem(
                IconKind iconKind, Color iconColor, String labelText) {
            JPanel item = UiTheme.panel(new FlowLayout(FlowLayout.LEFT, 8, 0), new Color(0, 0, 0, 0));
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel icon = new FeatureIcon(iconKind, iconColor, 22);
            JLabel label = UiTheme.label(labelText, new Font("Segoe UI", Font.PLAIN, 12), new Color(226, 232, 240));
            item.add(icon);
            item.add(label);
            return item;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gradient = new GradientPaint(
                        0, 0, new Color(0, 112, 255),
                        0, getHeight(), new Color(0, 75, 210));
                g2.setPaint(gradient);
                g2.fillRoundRect(0, 0, getWidth() + arc, getHeight(), arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private enum IconKind {
        LIGHTNING,
        FOLDER,
        SHIELD,
        BROADCAST
    }

    /** Vector feature icon so Swing does not depend on monochrome emoji glyphs. */
    private static final class FeatureIcon extends JPanel {
        private final IconKind kind;
        private final Color color;

        private FeatureIcon(IconKind kind, Color color, int size) {
            this.kind = kind;
            this.color = color;
            setOpaque(false);
            setPreferredSize(new Dimension(size, size));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                canvas.setColor(color);
                canvas.setStroke(new java.awt.BasicStroke(
                        Math.max(1.6f, width / 11f),
                        java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                switch (kind) {
                    case LIGHTNING -> {
                        Polygon bolt = new Polygon();
                        bolt.addPoint(width * 6 / 10, height / 10);
                        bolt.addPoint(width * 3 / 10, height * 5 / 10);
                        bolt.addPoint(width * 5 / 10, height * 5 / 10);
                        bolt.addPoint(width * 4 / 10, height * 9 / 10);
                        bolt.addPoint(width * 8 / 10, height * 4 / 10);
                        bolt.addPoint(width * 6 / 10, height * 4 / 10);
                        canvas.fillPolygon(bolt);
                    }
                    case FOLDER -> {
                        canvas.fillRoundRect(
                                width / 10, height * 3 / 10,
                                width * 8 / 10, height * 6 / 10, 4, 4);
                        canvas.fillRoundRect(
                                width * 2 / 10, height * 2 / 10,
                                width * 4 / 10, height * 3 / 10, 3, 3);
                    }
                    case SHIELD -> {
                        Polygon shield = new Polygon();
                        shield.addPoint(width / 2, height / 10);
                        shield.addPoint(width * 8 / 10, height * 2 / 10);
                        shield.addPoint(width * 8 / 10, height * 5 / 10);
                        shield.addPoint(width / 2, height * 9 / 10);
                        shield.addPoint(width * 2 / 10, height * 5 / 10);
                        shield.addPoint(width * 2 / 10, height * 2 / 10);
                        canvas.fillPolygon(shield);
                    }
                    case BROADCAST -> {
                        int dot = Math.max(4, width / 5);
                        canvas.fillOval((width - dot) / 2, height - dot - 1, dot, dot);
                        canvas.drawArc(width * 3 / 10, height * 4 / 10,
                                width * 4 / 10, height * 4 / 10, 45, 90);
                        canvas.drawArc(width / 10, height / 10,
                                width * 8 / 10, height * 7 / 10, 45, 90);
                    }
                }
            } finally {
                canvas.dispose();
            }
        }
    }

    /** Focus-aware border drawn inside its bounds so all four blue edges stay visible. */
    private static final class FocusRoundedBorder extends AbstractBorder {
        private final Color normalColor;
        private final Color focusColor;
        private final int arc;

        private FocusRoundedBorder(Color normalColor, Color focusColor, int arc) {
            this.normalColor = normalColor;
            this.focusColor = focusColor;
            this.arc = arc;
        }

        @Override
        public void paintBorder(
                Component component, Graphics graphics,
                int x, int y, int width, int height) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                boolean focused = component.isFocusOwner();
                canvas.setColor(focused ? focusColor : normalColor);
                canvas.setStroke(new java.awt.BasicStroke(focused ? 2f : 1f));
                int inset = focused ? 1 : 0;
                canvas.drawRoundRect(
                        x + inset, y + inset,
                        width - 1 - inset * 2, height - 1 - inset * 2,
                        arc, arc);
            } finally {
                canvas.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(2, 2, 2, 2);
        }
    }

    /** White vector chat logo for the blue branding banner. */
    private static final class BrandLogoIcon extends JPanel {
        private BrandLogoIcon() {
            setOpaque(false);
            setPreferredSize(new Dimension(48, 44));
            setMaximumSize(new Dimension(48, 44));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(new Color(255, 255, 255, 48));
                canvas.fillOval(0, 0, 44, 44);
                canvas.setColor(Color.WHITE);
                canvas.fillRoundRect(9, 9, 27, 20, 12, 12);
                Polygon tail = new Polygon(
                        new int[]{14, 12, 20}, new int[]{27, 36, 29}, 3);
                canvas.fillPolygon(tail);
            } finally {
                canvas.dispose();
            }
        }
    }

    private static final class RoundedBorder extends AbstractBorder {
        private final Color color;
        private final int arc;

        private RoundedBorder(Color color, int arc) {
            this.color = color;
            this.arc = arc;
        }

        @Override
        public void paintBorder(
                Component component, Graphics graphics,
                int x, int y, int width, int height) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(color);
                canvas.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            } finally {
                canvas.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(2, 4, 2, 4);
        }
    }
}
