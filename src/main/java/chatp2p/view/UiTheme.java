package chatp2p.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

/** Shared visual constants for the dependency-free Swing interface. */
final class UiTheme {
    static final Color NAVY = new Color(15, 23, 42);
    static final Color SLATE = new Color(71, 85, 105);
    static final Color MUTED = new Color(148, 163, 184);
    static final Color BORDER = new Color(226, 232, 240);
    static final Color BACKGROUND = new Color(248, 250, 252);
    static final Color WHITE = Color.WHITE;
    static final Color PRIMARY = new Color(37, 99, 235);
    static final Color PRIMARY_DARK = new Color(29, 78, 216);
    static final Color SUCCESS = new Color(22, 163, 74);
    static final Color DANGER = new Color(220, 38, 38);

    static final Font BODY = new Font("Segoe UI", Font.PLAIN, 14);
    static final Font BODY_BOLD = new Font("Segoe UI", Font.BOLD, 14);
    static final Font TITLE = new Font("Segoe UI", Font.BOLD, 28);
    static final Font SUBTITLE = new Font("Segoe UI", Font.PLAIN, 15);

    private UiTheme() {
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

