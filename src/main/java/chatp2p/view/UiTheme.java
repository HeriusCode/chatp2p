package chatp2p.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Shared visual constants for the dependency-free Swing interface. */
final class UiTheme {
    static final Color NAVY = new Color(15, 23, 42);
    static final Color SLATE = new Color(71, 85, 105);
    static final Color MUTED = new Color(148, 163, 184);
    static final Color BORDER = new Color(226, 232, 240);
    static final Color BACKGROUND = new Color(248, 250, 252);
    static final Color WHITE = Color.WHITE;
    static final Color PRIMARY = new Color(0, 104, 255); // Zalo / Modern Blue
    static final Color PRIMARY_DARK = new Color(0, 84, 210);
    static final Color MESSENGER_BLUE = new Color(0, 132, 255);
    static final Color SUCCESS = new Color(22, 163, 74);
    static final Color DANGER = new Color(220, 38, 38);

    // Zalo / Messenger Theme Constants
    static final Color CHAT_BG = new Color(240, 242, 245);
    static final Color BUBBLE_RECEIVE = new Color(228, 230, 235);
    static final Color BUBBLE_RECEIVE_TEXT = new Color(5, 5, 5);
    // Match the bright blue used by the registration branding panel.
    static final Color RAIL_BG = new Color(0, 112, 255);
    static final Color RAIL_HOVER = new Color(0, 84, 210);
    static final Color HOVER_BG = new Color(242, 244, 247);
    static final Color SELECTION_BG = new Color(231, 243, 255);
    static final Color ONLINE_GREEN = new Color(34, 197, 94);

    static final Font BODY = new Font("Segoe UI", Font.PLAIN, 14);
    static final Font BODY_BOLD = new Font("Segoe UI", Font.BOLD, 14);
    static final Font TITLE = new Font("Segoe UI", Font.BOLD, 28);
    static final Font SUBTITLE = new Font("Segoe UI", Font.PLAIN, 15);
    static final Font SMALL = new Font("Segoe UI", Font.PLAIN, 12);
    static final Font SMALL_BOLD = new Font("Segoe UI", Font.BOLD, 12);

    private static final Color[] AVATAR_COLORS = {
            new Color(0, 132, 255),   // Blue
            new Color(16, 185, 129),  // Emerald
            new Color(245, 158, 11),  // Amber
            new Color(239, 68, 68),   // Red
            new Color(139, 92, 246),  // Purple
            new Color(236, 72, 153),  // Pink
            new Color(14, 165, 233),  // Sky
            new Color(99, 102, 241),  // Indigo
            new Color(20, 184, 166)   // Teal
    };

    private UiTheme() {
    }

    static Color getAvatarColor(String name) {
        if (name == null || name.isBlank()) {
            return AVATAR_COLORS[0];
        }
        int hash = Math.abs(name.hashCode());
        return AVATAR_COLORS[hash % AVATAR_COLORS.length];
    }

    static void drawAvatar(
            Graphics2D g2, String name, int x, int y, int size, boolean isGroup, boolean isOnline) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color bg = isGroup ? new Color(100, 116, 139) : getAvatarColor(name);
        g2.setColor(bg);
        g2.fillOval(x, y, size, size);

        // Text / Icon inside avatar
        g2.setColor(WHITE);
        if (isGroup) {
            g2.setFont(new Font("Segoe UI", Font.BOLD, Math.max(12, size / 2)));
            FontMetrics fm = g2.getFontMetrics();
            String label = "#";
            int textX = x + (size - fm.stringWidth(label)) / 2;
            int textY = y + (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(label, textX, textY);
        } else {
            String initial = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "?";
            g2.setFont(new Font("Segoe UI", Font.BOLD, Math.max(12, size * 2 / 5)));
            FontMetrics fm = g2.getFontMetrics();
            int textX = x + (size - fm.stringWidth(initial)) / 2;
            int textY = y + (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(initial, textX, textY);
        }

        // Online badge dot
        if (isOnline) {
            int dotSize = Math.max(10, size / 3);
            int dotX = x + size - dotSize;
            int dotY = y + size - dotSize;
            g2.setColor(WHITE);
            g2.fillOval(dotX - 2, dotY - 2, dotSize + 4, dotSize + 4);
            g2.setColor(ONLINE_GREEN);
            g2.fillOval(dotX, dotY, dotSize, dotSize);
        }
    }

    static void applyModernScrollBar(JScrollPane scrollPane) {
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        scrollPane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));
        scrollPane.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        scrollPane.getHorizontalScrollBar().setUI(new ModernScrollBarUI());
    }

    static class ModernScrollBarUI extends BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return createZeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return createZeroButton();
        }

        private JButton createZeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // Invisible/flat track
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(180, 185, 195, 160));
            g2.fillRoundRect(thumbBounds.x + 1, thumbBounds.y + 1, thumbBounds.width - 2, thumbBounds.height - 2, 6, 6);
            g2.dispose();
        }
    }

    static JPanel panel(java.awt.LayoutManager layout, Color background) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(background);
        return panel;
    }

    static JLabel label(String text, Font font, Color foreground) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(foreground);
        return label;
    }

    static void styleTextField(JTextField field) {
        field.setFont(BODY);
        field.setForeground(NAVY);
        field.setBackground(WHITE);
        field.setCaretColor(PRIMARY);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(10, 12, 10, 12)));
        field.setPreferredSize(new Dimension(320, 42));
    }

    static void stylePrimaryButton(JButton button) {
        button.setFont(BODY_BOLD);
        button.setForeground(WHITE);
        button.setBackground(PRIMARY);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(11, 18, 11, 18));
        button.setOpaque(true);
    }

    static void styleSecondaryButton(JButton button) {
        button.setFont(BODY_BOLD);
        button.setForeground(PRIMARY_DARK);
        button.setBackground(new Color(239, 246, 255));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createLineBorder(new Color(191, 219, 254)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(9, 16, 9, 16));
    }

    static void setEnabled(JButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setCursor(enabled
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
    }

    static void addVerticalGap(JComponent component, int height) {
        component.add(javax.swing.Box.createVerticalStrut(height));
    }

    static void setPadding(JComponent component, int top, int left, int bottom, int right) {
        component.setBorder(new EmptyBorder(top, left, bottom, right));
    }

    static void leftAlign(Component component) {
        if (component instanceof JComponent swingComponent) {
            swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
    }
}

