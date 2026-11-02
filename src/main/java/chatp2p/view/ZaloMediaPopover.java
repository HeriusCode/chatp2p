package chatp2p.view;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;

/**
 * Modern Zalo-styled popover panel providing STICKER, EMOJI, and GIF tabs.
 */
public final class ZaloMediaPopover extends JPopupMenu {
    public enum TabType {
        STICKER, EMOJI, GIF
    }

    private final Consumer<String> onSendMedia;
    private final Consumer<String> onInsertEmoji;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentCards = new JPanel(cardLayout);

    private final TabButton stickerTabBtn;
    private final TabButton emojiTabBtn;
    private final TabButton gifTabBtn;
    private final JPanel recentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

    public ZaloMediaPopover(Consumer<String> onSendMedia, Consumer<String> onInsertEmoji) {
        this.onSendMedia = onSendMedia;
        this.onInsertEmoji = onInsertEmoji;

        setBorder(BorderFactory.createCompoundBorder(
                new RoundedBorder(new Color(218, 224, 233), 16),
                new EmptyBorder(10, 10, 10, 10)));
        setBackground(Color.WHITE);
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(380, 480));

        // 1. Top Tab Bar (STICKER | EMOJI | GIF)
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(241, 245, 249)));

        JPanel tabsLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        tabsLeft.setBackground(Color.WHITE);

        stickerTabBtn = new TabButton("STICKER");
        emojiTabBtn = new TabButton("EMOJI");
        gifTabBtn = new TabButton("GIF");

        stickerTabBtn.addActionListener(e -> selectTab(TabType.STICKER));
        emojiTabBtn.addActionListener(e -> selectTab(TabType.EMOJI));
        gifTabBtn.addActionListener(e -> selectTab(TabType.GIF));

        tabsLeft.add(stickerTabBtn);
        tabsLeft.add(emojiTabBtn);
        tabsLeft.add(gifTabBtn);

        JButton closeBtn = new JButton("✕");
        closeBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        closeBtn.setForeground(new Color(148, 163, 184));
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> setVisible(false));

        topBar.add(tabsLeft, BorderLayout.CENTER);
        topBar.add(closeBtn, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

        // 2. Tab Contents
        contentCards.setBackground(Color.WHITE);
        contentCards.add(createStickerPanel(), TabType.STICKER.name());
        contentCards.add(createEmojiPanel(), TabType.EMOJI.name());
        contentCards.add(createGifPanel(), TabType.GIF.name());

        add(contentCards, BorderLayout.CENTER);

        // Default tab: EMOJI
        selectTab(TabType.EMOJI);
    }

    public void selectTab(TabType tab) {
        stickerTabBtn.setActive(tab == TabType.STICKER);
        emojiTabBtn.setActive(tab == TabType.EMOJI);
        gifTabBtn.setActive(tab == TabType.GIF);
        cardLayout.show(contentCards, tab.name());
        if (tab == TabType.EMOJI) {
            refreshRecentEmojis();
        }
    }

    /* -------------------------------------------------------------
     *  STICKER TAB
     * ------------------------------------------------------------- */
    private JPanel createStickerPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 8));
        header.setBackground(Color.WHITE);
        JLabel title = UiTheme.label("Bộ sưu tập Sticker Zalo", new Font("Segoe UI", Font.BOLD, 12), UiTheme.NAVY);
        header.add(title);
        panel.add(header, BorderLayout.NORTH);

        List<ZaloMedia.StickerEntry> stickers = ZaloMedia.getAllStickers();
        int cols = 3;
        int rows = (int) Math.ceil((double) stickers.size() / cols);
        JPanel grid = new JPanel(new GridLayout(rows, cols, 8, 8));
        grid.setBackground(Color.WHITE);
        grid.setBorder(new EmptyBorder(4, 4, 8, 4));

        for (ZaloMedia.StickerEntry s : stickers) {
            JButton item = new JButton(s.icon());
            item.setToolTipText(s.title() + " - " + s.subtitle());
            item.setPreferredSize(new Dimension(102, 102));
            item.setBorderPainted(false);
            item.setContentAreaFilled(false);
            item.setFocusPainted(false);
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            item.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    item.setContentAreaFilled(true);
                    item.setBackground(new Color(241, 245, 249));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    item.setContentAreaFilled(false);
                }
            });
            item.addActionListener(e -> {
                if (onSendMedia != null) {
                    onSendMedia.accept(ZaloMedia.formatStickerPayload(s.id()));
                }
                setVisible(false);
            });
            grid.add(item);
        }

        JScrollPane scroll = new JScrollPane(grid);
        UiTheme.applyModernScrollBar(scroll);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /* -------------------------------------------------------------
     *  EMOJI TAB
     * ------------------------------------------------------------- */
    private JPanel createEmojiPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);

        JPanel mainList = new JPanel();
        mainList.setLayout(new BoxLayout(mainList, BoxLayout.Y_AXIS));
        mainList.setBackground(Color.WHITE);
        mainList.setBorder(new EmptyBorder(6, 6, 6, 6));

        // 1. Gần đây section
        JLabel recentLabel = UiTheme.label("Gần đây", new Font("Segoe UI", Font.BOLD, 12), UiTheme.SLATE);
        recentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainList.add(recentLabel);
        mainList.add(Box.createVerticalStrut(4));

        recentRow.setBackground(Color.WHITE);
        recentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainList.add(recentRow);
        mainList.add(Box.createVerticalStrut(12));

        // 2. Cảm xúc & Biểu tượng section
        JLabel emotionLabel = UiTheme.label("Cảm xúc & Biểu tượng", new Font("Segoe UI", Font.BOLD, 12), UiTheme.SLATE);
        emotionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainList.add(emotionLabel);
        mainList.add(Box.createVerticalStrut(6));

        List<ZaloMedia.EmojiEntry> emojis = ZaloMedia.getAllEmojis();
        int cols = 7;
        int rows = (int) Math.ceil((double) emojis.size() / cols);
        JPanel grid = new JPanel(new GridLayout(rows, cols, 4, 4));
        grid.setBackground(Color.WHITE);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (ZaloMedia.EmojiEntry em : emojis) {
            JButton item = new JButton(em.icon());
            item.setToolTipText(em.label());
            item.setPreferredSize(new Dimension(42, 42));
            item.setBorderPainted(false);
            item.setContentAreaFilled(false);
            item.setFocusPainted(false);
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            item.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    item.setContentAreaFilled(true);
                    item.setBackground(new Color(241, 245, 249));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    item.setContentAreaFilled(false);
                }
            });
            item.addActionListener(e -> {
                ZaloMedia.recordRecentEmoji(em.id());
                if (onInsertEmoji != null) {
                    onInsertEmoji.accept(em.id());
                }
                refreshRecentEmojis();
            });
            grid.add(item);
        }
        mainList.add(grid);

        JScrollPane scroll = new JScrollPane(mainList);
        UiTheme.applyModernScrollBar(scroll);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    private void refreshRecentEmojis() {
        recentRow.removeAll();
        for (ZaloMedia.EmojiEntry em : ZaloMedia.getRecentEmojis()) {
            JButton item = new JButton(em.icon());
            item.setToolTipText(em.label());
            item.setPreferredSize(new Dimension(36, 36));
            item.setBorderPainted(false);
            item.setContentAreaFilled(false);
            item.setFocusPainted(false);
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            item.addActionListener(e -> {
                ZaloMedia.recordRecentEmoji(em.id());
                if (onInsertEmoji != null) {
                    onInsertEmoji.accept(em.id());
                }
                refreshRecentEmojis();
            });
            recentRow.add(item);
        }
        recentRow.revalidate();
        recentRow.repaint();
    }

    /* -------------------------------------------------------------
     *  GIF TAB
     * ------------------------------------------------------------- */
    private JPanel createGifPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 8));
        header.setBackground(Color.WHITE);
        JLabel title = UiTheme.label("GIF phản hồi động", new Font("Segoe UI", Font.BOLD, 12), UiTheme.NAVY);
        header.add(title);
        panel.add(header, BorderLayout.NORTH);

        List<ZaloMedia.GifEntry> gifs = ZaloMedia.getAllGifs();
        int cols = 2;
        int rows = (int) Math.ceil((double) gifs.size() / cols);
        JPanel grid = new JPanel(new GridLayout(rows, cols, 10, 10));
        grid.setBackground(Color.WHITE);
        grid.setBorder(new EmptyBorder(6, 6, 8, 6));

        for (ZaloMedia.GifEntry g : gifs) {
            JPanel card = new JPanel(new BorderLayout());
            card.setBackground(new Color(248, 250, 252));
            card.setBorder(BorderFactory.createCompoundBorder(
                    new RoundedBorder(new Color(226, 232, 240), 12),
                    new EmptyBorder(8, 8, 8, 8)));
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel animLabel = new JLabel(g.icon(), JLabel.CENTER);
            JLabel titleLabel = UiTheme.label(g.title(), new Font("Segoe UI", Font.BOLD, 11), UiTheme.SLATE);
            titleLabel.setHorizontalAlignment(JLabel.CENTER);

            card.add(animLabel, BorderLayout.CENTER);
            card.add(titleLabel, BorderLayout.SOUTH);

            card.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    card.setBackground(new Color(231, 243, 255));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    card.setBackground(new Color(248, 250, 252));
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onSendMedia != null) {
                        onSendMedia.accept(ZaloMedia.formatGifPayload(g.id()));
                    }
                    setVisible(false);
                }
            });
            grid.add(card);
        }

        JScrollPane scroll = new JScrollPane(grid);
        UiTheme.applyModernScrollBar(scroll);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Top bar tab button with active blue underline indicator. */
    private static final class TabButton extends JButton {
        private boolean active;

        private TabButton(String text) {
            super(text);
            setFont(new Font("Segoe UI", Font.BOLD, 12));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(80, 36));
        }

        public void setActive(boolean active) {
            this.active = active;
            setForeground(active ? UiTheme.PRIMARY : new Color(100, 116, 139));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (active) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UiTheme.PRIMARY);
                    g2.fillRoundRect(8, getHeight() - 3, getWidth() - 16, 3, 2, 2);
                } finally {
                    g2.dispose();
                }
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
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
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
        public java.awt.Insets getBorderInsets(Component component) {
            return new java.awt.Insets(1, 1, 1, 1);
        }
    }
}

