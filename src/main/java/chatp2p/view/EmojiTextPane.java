package chatp2p.view;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Renders the app's colorful emoji artwork inside editable and read-only text. */
final class EmojiTextPane extends JTextPane {
    private static final String EMOJI_ID = "chatp2p.emojiId";
    private String placeholder;

    EmojiTextPane(String placeholder) {
        this.placeholder = placeholder;
        setOpaque(false);
    }

    void insertEmoji(String id) {
        if (ZaloMedia.getEmojiIcon(id) == null) {
            return;
        }
        int start = getSelectionStart();
        int end = getSelectionEnd();
        try {
            StyledDocument document = getStyledDocument();
            if (end > start) {
                document.remove(start, end - start);
            }
            insertEmojiAt(start, id);
            setCaretPosition(start + 1);
            setCharacterAttributes(new SimpleAttributeSet(), true);
        } catch (BadLocationException ignored) {
            // The selection changed while the emoji picker had focus.
        }
    }

    String messageText() {
        StyledDocument document = getStyledDocument();
        StringBuilder result = new StringBuilder();
        try {
            String raw = document.getText(0, document.getLength());
            for (int index = 0; index < raw.length(); index++) {
                Object id = document.getCharacterElement(index).getAttributes().getAttribute(EMOJI_ID);
                result.append(id instanceof String emojiId ? ZaloMedia.getEmojiText(emojiId) : raw.charAt(index));
            }
        } catch (BadLocationException ignored) {
            // Document offsets are derived from the same document and remain valid on the EDT.
        }
        return result.toString();
    }

    void showMessage(String text, Color foreground) {
        setText("");
        setForeground(foreground);
        StyledDocument document = getStyledDocument();
        SimpleAttributeSet plain = new SimpleAttributeSet();
        StyleConstants.setForeground(plain, foreground);
        StyleConstants.setFontFamily(plain, getFont().getFamily());
        StyleConstants.setFontSize(plain, getFont().getSize());

        List<ZaloMedia.EmojiEntry> emojis = ZaloMedia.getAllEmojis();
        int segmentStart = 0;
        for (int index = 0; index < text.length();) {
            String matchedId = null;
            int matchedLength = 0;
            for (ZaloMedia.EmojiEntry emoji : emojis) {
                String unicode = ZaloMedia.getEmojiText(emoji.id());
                if (unicode.length() > matchedLength && text.startsWith(unicode, index)) {
                    matchedId = emoji.id();
                    matchedLength = unicode.length();
                }
            }
            if (matchedId == null) {
                index++;
                continue;
            }
            try {
                document.insertString(document.getLength(), text.substring(segmentStart, index), plain);
                insertEmojiAt(document.getLength(), matchedId);
            } catch (BadLocationException ignored) {
                return;
            }
            index += matchedLength;
            segmentStart = index;
        }
        try {
            document.insertString(document.getLength(), text.substring(segmentStart), plain);
        } catch (BadLocationException ignored) {
            // Appending at document length cannot fail in normal use.
        }
        setCaretPosition(0);
    }

    private void insertEmojiAt(int position, String id) throws BadLocationException {
        Icon original = ZaloMedia.getEmojiIcon(id);
        if (original == null) {
            return;
        }
        Icon scaled = new Icon() {
            private static final int SIZE = 25;

            @Override
            public int getIconWidth() {
                return SIZE;
            }

            @Override
            public int getIconHeight() {
                return SIZE;
            }

            @Override
            public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
                Graphics2D canvas = (Graphics2D) graphics.create();
                try {
                    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    canvas.translate(x, y);
                    canvas.scale((double) SIZE / original.getIconWidth(), (double) SIZE / original.getIconHeight());
                    original.paintIcon(component, canvas, 0, 0);
                } finally {
                    canvas.dispose();
                }
            }
        };
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setIcon(attributes, scaled);
        attributes.addAttribute(EMOJI_ID, id);
        getStyledDocument().insertString(position, "\uFFFC", attributes);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (placeholder != null && getDocument().getLength() == 0 && !isFocusOwner()) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                canvas.setColor(UiTheme.MUTED);
                canvas.setFont(getFont());
                Insets insets = getInsets();
                FontMetrics metrics = canvas.getFontMetrics();
                int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                canvas.drawString(placeholder, insets.left, baseline);
            } finally {
                canvas.dispose();
            }
        }
    }
}
