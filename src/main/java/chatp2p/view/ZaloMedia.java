package chatp2p.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.Timer;

/**
 * Rich, colorful media catalog providing glossy 3D emojis, large Zalo-style stickers,
 * and animated reaction GIFs without any external dependencies.
 */
public final class ZaloMedia {
    public static final String EMOJI_PREFIX = "[EMOJI:";
    public static final String STICKER_PREFIX = "[STICKER:";
    public static final String GIF_PREFIX = "[GIF:";

    public record EmojiEntry(String id, String label, String category, Icon icon) {
    }

    public record StickerEntry(String id, String title, String subtitle, Icon icon) {
    }

    public record GifEntry(String id, String title, Icon icon) {
    }

    private static final Map<String, EmojiEntry> EMOJIS = new LinkedHashMap<>();
    private static final Map<String, StickerEntry> STICKERS = new LinkedHashMap<>();
    private static final Map<String, GifEntry> GIFS = new LinkedHashMap<>();
    private static final List<String> RECENT_EMOJIS = new ArrayList<>();

    static {
        initEmojis();
        initStickers();
        initGifs();
    }

    private ZaloMedia() {
    }

    public static boolean isEmoji(String content) {
        return content != null && content.startsWith(EMOJI_PREFIX) && content.endsWith("]");
    }

    public static boolean isSticker(String content) {
        return content != null && content.startsWith(STICKER_PREFIX) && content.endsWith("]");
    }

    public static boolean isGif(String content) {
        return content != null && content.startsWith(GIF_PREFIX) && content.endsWith("]");
    }

    public static String extractMediaId(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf(':');
        int end = content.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return content.substring(start + 1, end).trim();
        }
        return "";
    }

    public static String formatEmojiPayload(String emojiId) {
        return EMOJI_PREFIX + emojiId + "]";
    }

    public static String formatStickerPayload(String stickerId) {
        return STICKER_PREFIX + stickerId + "]";
    }

    public static String formatGifPayload(String gifId) {
        return GIF_PREFIX + gifId + "]";
    }

    public static List<EmojiEntry> getAllEmojis() {
        return new ArrayList<>(EMOJIS.values());
    }

    public static List<StickerEntry> getAllStickers() {
        return new ArrayList<>(STICKERS.values());
    }

    public static List<GifEntry> getAllGifs() {
        return new ArrayList<>(GIFS.values());
    }

    public static List<EmojiEntry> getRecentEmojis() {
        List<EmojiEntry> list = new ArrayList<>();
        for (String id : RECENT_EMOJIS) {
            EmojiEntry entry = EMOJIS.get(id);
            if (entry != null) {
                list.add(entry);
            }
        }
        if (list.isEmpty()) {
            // Default recent
            String[] defaults = {"cake", "smile", "love", "haha", "thumbs_up"};
            for (String d : defaults) {
                EmojiEntry e = EMOJIS.get(d);
                if (e != null) {
                    list.add(e);
                }
            }
        }
        return list;
    }

    public static void recordRecentEmoji(String id) {
        if (id == null) {
            return;
        }
        RECENT_EMOJIS.remove(id);
        RECENT_EMOJIS.add(0, id);
        while (RECENT_EMOJIS.size() > 16) {
            RECENT_EMOJIS.remove(RECENT_EMOJIS.size() - 1);
        }
    }

    public static Icon getStickerIcon(String id) {
        StickerEntry entry = STICKERS.get(id);
        return entry != null ? entry.icon() : null;
    }

    public static Icon getGifIcon(String id) {
        GifEntry entry = GIFS.get(id);
        return entry != null ? entry.icon() : null;
    }

    public static Icon getEmojiIcon(String id) {
        EmojiEntry entry = EMOJIS.get(id);
        return entry != null ? entry.icon() : null;
    }

    public static String getEmojiText(String id) {
        return switch (id) {
            case "smile" -> "🙂";
            case "haha" -> "😂";
            case "love" -> "😍";
            case "cool" -> "😎";
            case "cry" -> "😭";
            case "angry" -> "😡";
            case "wink" -> "😉";
            case "blush" -> "😊";
            case "star_eyes" -> "🤩";
            case "sleep" -> "😴";
            case "party" -> "🥳";
            case "devil" -> "😈";
            case "shock" -> "😮";
            case "kiss" -> "😘";
            case "clown" -> "🤡";
            case "angel" -> "😇";
            case "cake" -> "🎂";
            case "thumbs_up" -> "👍";
            case "thumbs_down" -> "👎";
            case "heart_red" -> "❤️";
            case "heart_pink" -> "💗";
            case "fire" -> "🔥";
            case "star" -> "⭐";
            case "clap" -> "👏";
            case "pray" -> "🙏";
            case "victory" -> "✌️";
            case "hundred" -> "💯";
            case "coffee" -> "☕";
            case "gift" -> "🎁";
            default -> "";
        };
    }

    /* -------------------------------------------------------------
     *  EMOJIS INITIALIZATION (Glossy 3D rendered in Java2D)
     * ------------------------------------------------------------- */
    private static void initEmojis() {
        // Cảm xúc cơ bản
        registerEmoji("smile", "Mỉm cười", "Cảm xúc", drawFace(FaceType.SMILE));
        registerEmoji("haha", "Cười to", "Cảm xúc", drawFace(FaceType.HAHA));
        registerEmoji("love", "Yêu thương", "Cảm xúc", drawFace(FaceType.LOVE));
        registerEmoji("cool", "Ngầu", "Cảm xúc", drawFace(FaceType.COOL));
        registerEmoji("cry", "Khóc", "Cảm xúc", drawFace(FaceType.CRY));
        registerEmoji("angry", "Tức giận", "Cảm xúc", drawFace(FaceType.ANGRY));
        registerEmoji("wink", "Nháy mắt", "Cảm xúc", drawFace(FaceType.WINK));
        registerEmoji("blush", "Ngại ngùng", "Cảm xúc", drawFace(FaceType.BLUSH));
        registerEmoji("star_eyes", "Ngưỡng mộ", "Cảm xúc", drawFace(FaceType.STAR_EYES));
        registerEmoji("sleep", "Buồn ngủ", "Cảm xúc", drawFace(FaceType.SLEEP));
        registerEmoji("party", "Tiệc tùng", "Cảm xúc", drawFace(FaceType.PARTY));
        registerEmoji("devil", "Ma mãnh", "Cảm xúc", drawFace(FaceType.DEVIL));
        registerEmoji("shock", "Bất ngờ", "Cảm xúc", drawFace(FaceType.SHOCK));
        registerEmoji("kiss", "Hôn", "Cảm xúc", drawFace(FaceType.KISS));
        registerEmoji("clown", "Chú hề", "Cảm xúc", drawFace(FaceType.CLOWN));
        registerEmoji("angel", "Thiên thần", "Cảm xúc", drawFace(FaceType.ANGEL));

        // Cử chỉ & Biểu tượng
        registerEmoji("cake", "Bánh kem", "Biểu tượng", drawCakeIcon());
        registerEmoji("thumbs_up", "Tuyệt vời", "Cử chỉ", drawThumbsUpIcon(true));
        registerEmoji("thumbs_down", "Không thích", "Cử chỉ", drawThumbsUpIcon(false));
        registerEmoji("heart_red", "Trái tim", "Biểu tượng", drawHeartIcon(new Color(239, 68, 68)));
        registerEmoji("heart_pink", "Tim hồng", "Biểu tượng", drawHeartIcon(new Color(236, 72, 153)));
        registerEmoji("fire", "Ngọn lửa", "Biểu tượng", drawFireIcon());
        registerEmoji("star", "Ngôi sao", "Biểu tượng", drawStarIcon());
        registerEmoji("clap", "Vỗ tay", "Cử chỉ", drawClapIcon());
        registerEmoji("pray", "Cầu nguyện", "Cử chỉ", drawPrayIcon());
        registerEmoji("victory", "Chiến thắng", "Cử chỉ", drawVictoryIcon());
        registerEmoji("hundred", "100 điểm", "Biểu tượng", drawHundredIcon());
        registerEmoji("coffee", "Cà phê", "Biểu tượng", drawCoffeeIcon());
        registerEmoji("gift", "Hộp quà", "Biểu tượng", drawGiftIcon());
    }

    private static void registerEmoji(String id, String label, String cat, BufferedImage img) {
        EMOJIS.put(id, new EmojiEntry(id, label, cat, new ImageIcon(img)));
    }

    private enum FaceType {
        SMILE, HAHA, LOVE, COOL, CRY, ANGRY, WINK, BLUSH, STAR_EYES, SLEEP, PARTY, DEVIL, SHOCK, KISS, CLOWN, ANGEL
    }

    private static BufferedImage drawFace(FaceType type) {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int cx = size / 2;
        int cy = size / 2;
        int r = 16;

        // Base gradient
        Color topColor = new Color(255, 225, 53);
        Color botColor = new Color(245, 158, 11);
        if (type == FaceType.ANGRY) {
            topColor = new Color(248, 113, 113);
            botColor = new Color(220, 38, 38);
        } else if (type == FaceType.DEVIL) {
            topColor = new Color(192, 132, 252);
            botColor = new Color(126, 34, 206);
        }

        GradientPaint headGrad = new GradientPaint(cx, cy - r, topColor, cx, cy + r, botColor);
        g.setPaint(headGrad);
        g.fillOval(cx - r, cy - r, r * 2, r * 2);

        // Specular gloss on top
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx, cy - r + 5), 10f,
                new float[]{0.0f, 1.0f},
                new Color[]{new Color(255, 255, 255, 140), new Color(255, 255, 255, 0)}
        ));
        g.fillOval(cx - 10, cy - r + 2, 20, 10);

        // Head border
        g.setColor(new Color(180, 83, 9, 70));
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);

        // Facial details
        switch (type) {
            case SMILE -> {
                drawEye(g, cx - 6, cy - 3, 2);
                drawEye(g, cx + 6, cy - 3, 2);
                // Smile mouth
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx - 7, cy - 3, 14, 11, 200, 140);
            }
            case HAHA -> {
                // Curved closed laughing eyes ^ ^
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx - 9, cy - 5, 6, 6, 20, 140);
                g.drawArc(cx + 3, cy - 5, 6, 6, 20, 140);
                // Big laughing open mouth
                g.setColor(new Color(120, 53, 15));
                g.fillArc(cx - 9, cy - 2, 18, 14, 180, 180);
                g.setColor(Color.WHITE);
                g.fillRect(cx - 6, cy + 3, 12, 3);
                // Teardrops
                g.setColor(new Color(56, 189, 248));
                g.fillOval(cx - 13, cy - 2, 4, 6);
                g.fillOval(cx + 9, cy - 2, 4, 6);
            }
            case LOVE -> {
                // Big 3D red heart eyes
                drawMiniHeart(g, cx - 6, cy - 3, 5, new Color(239, 68, 68));
                drawMiniHeart(g, cx + 6, cy - 3, 5, new Color(239, 68, 68));
                // Smile
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx - 6, cy - 2, 12, 10, 200, 140);
            }
            case COOL -> {
                // Sunglasses
                g.setColor(new Color(15, 23, 42));
                g.fillRoundRect(cx - 12, cy - 6, 10, 8, 4, 4);
                g.fillRoundRect(cx + 2, cy - 6, 10, 8, 4, 4);
                g.fillRect(cx - 2, cy - 4, 4, 3);
                // Glasses shine
                g.setColor(new Color(255, 255, 255, 120));
                g.drawLine(cx - 10, cy - 4, cx - 7, cy - 1);
                g.drawLine(cx + 4, cy - 4, cx + 7, cy - 1);
                // Smirk
                g.setColor(new Color(15, 23, 42));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx - 4, cy, 10, 8, 220, 100);
            }
            case CRY -> {
                drawEye(g, cx - 6, cy - 4, 2);
                drawEye(g, cx + 6, cy - 4, 2);
                // Sad mouth
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx - 6, cy + 3, 12, 8, 20, 140);
                // Waterfall tears
                g.setColor(new Color(56, 189, 248));
                g.fillRoundRect(cx - 8, cy - 2, 4, 12, 3, 3);
                g.fillRoundRect(cx + 4, cy - 2, 4, 12, 3, 3);
            }
            case ANGRY -> {
                // Slanted eyebrows
                g.setColor(new Color(69, 10, 10));
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 10, cy - 7, cx - 3, cy - 4);
                g.drawLine(cx + 10, cy - 7, cx + 3, cy - 4);
                drawEye(g, cx - 6, cy - 2, 2);
                drawEye(g, cx + 6, cy - 2, 2);
                // Frown mouth
                g.drawArc(cx - 7, cy + 3, 14, 8, 20, 140);
            }
            case WINK -> {
                drawEye(g, cx - 6, cy - 3, 2);
                // Winking eye
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx + 3, cy - 5, 6, 4, 190, 160);
                // Tongue out
                g.drawArc(cx - 6, cy - 2, 12, 10, 200, 140);
                g.setColor(new Color(244, 63, 94));
                g.fillOval(cx - 2, cy + 4, 5, 7);
            }
            case BLUSH -> {
                drawEye(g, cx - 6, cy - 3, 2);
                drawEye(g, cx + 6, cy - 3, 2);
                // Pink cheeks
                g.setColor(new Color(244, 63, 94, 150));
                g.fillOval(cx - 11, cy - 1, 5, 3);
                g.fillOval(cx + 6, cy - 1, 5, 3);
                // Shy mouth
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(cx - 4, cy, 8, 6, 200, 140);
            }
            case STAR_EYES -> {
                // Golden star eyes
                drawMiniStar(g, cx - 6, cy - 3, 4, new Color(245, 158, 11));
                drawMiniStar(g, cx + 6, cy - 3, 4, new Color(245, 158, 11));
                // Open happy mouth
                g.setColor(new Color(120, 53, 15));
                g.fillArc(cx - 6, cy - 1, 12, 10, 180, 180);
            }
            case SLEEP -> {
                // Closed eyes - -
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(cx - 9, cy - 3, cx - 4, cy - 3);
                g.drawLine(cx + 4, cy - 3, cx + 9, cy - 3);
                // Small round mouth
                g.fillOval(cx - 2, cy + 2, 5, 6);
                // Zzz
                g.setColor(new Color(59, 130, 246));
                g.setFont(new Font("Segoe UI", Font.BOLD, 10));
                g.drawString("Z", cx + 8, cy - 8);
            }
            case PARTY -> {
                drawEye(g, cx - 6, cy - 2, 2);
                drawEye(g, cx + 6, cy - 2, 2);
                // Smile
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f));
                g.drawArc(cx - 6, cy - 1, 12, 9, 200, 140);
                // Party hat
                int[] hx = {cx - 12, cx - 2, cx - 18};
                int[] hy = {cy - 12, cy - 10, cy - 24};
                g.setColor(new Color(236, 72, 153));
                g.fillPolygon(hx, hy, 3);
                g.setColor(new Color(59, 130, 246));
                g.fillOval(cx - 20, cy - 26, 6, 6);
            }
            default -> {
                drawEye(g, cx - 6, cy - 3, 2);
                drawEye(g, cx + 6, cy - 3, 2);
                g.setColor(new Color(120, 53, 15));
                g.setStroke(new BasicStroke(2f));
                g.drawArc(cx - 6, cy - 2, 12, 10, 200, 140);
            }
        }

        g.dispose();
        return img;
    }

    private static void drawEye(Graphics2D g, int x, int y, int radius) {
        g.setColor(new Color(30, 20, 10));
        g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        // Reflection
        g.setColor(Color.WHITE);
        g.fillOval(x - radius + 1, y - radius + 1, radius, radius);
    }

    private static void drawMiniHeart(Graphics2D g, int x, int y, int size, Color c) {
        g.setColor(c);
        g.fillOval(x - size, y - size, size, size);
        g.fillOval(x, y - size, size, size);
        int[] px = {x - size, x + size, x};
        int[] py = {y - size / 3, y - size / 3, y + size};
        g.fillPolygon(px, py, 3);
    }

    private static void drawMiniStar(Graphics2D g, int x, int y, int r, Color c) {
        g.setColor(c);
        int[] px = new int[10];
        int[] py = new int[10];
        for (int i = 0; i < 10; i++) {
            double angle = i * Math.PI / 5 - Math.PI / 2;
            int rad = (i % 2 == 0) ? r : r / 2;
            px[i] = x + (int) (Math.cos(angle) * rad);
            py[i] = y + (int) (Math.sin(angle) * rad);
        }
        g.fillPolygon(px, py, 10);
    }

    private static BufferedImage drawCakeIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Cake base
        g.setColor(new Color(245, 158, 11));
        g.fillRoundRect(6, 16, 24, 14, 6, 6);
        // Cream layer
        g.setColor(new Color(254, 243, 199));
        g.fillRoundRect(4, 13, 28, 8, 4, 4);
        // Pink frosting drips
        g.setColor(new Color(244, 63, 94));
        g.fillRoundRect(4, 12, 28, 5, 3, 3);
        // Candles
        g.setColor(new Color(59, 130, 246));
        g.fillRect(10, 6, 3, 7);
        g.fillRect(16, 6, 3, 7);
        g.fillRect(22, 6, 3, 7);
        // Flames
        g.setColor(new Color(239, 68, 68));
        g.fillOval(10, 2, 3, 4);
        g.fillOval(16, 2, 3, 4);
        g.fillOval(22, 2, 3, 4);
        g.dispose();
        return img;
    }

    private static BufferedImage drawThumbsUpIcon(boolean up) {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(up ? new Color(14, 165, 233) : new Color(239, 68, 68));
        if (up) {
            g.fillRoundRect(12, 10, 6, 16, 4, 4);
            g.fillRoundRect(16, 14, 12, 12, 4, 4);
            g.fillOval(12, 6, 6, 8);
        } else {
            g.fillRoundRect(12, 10, 6, 16, 4, 4);
            g.fillRoundRect(16, 10, 12, 12, 4, 4);
            g.fillOval(12, 22, 6, 8);
        }
        g.dispose();
        return img;
    }

    private static BufferedImage drawHeartIcon(Color c) {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int cx = size / 2;
        int cy = size / 2;
        drawMiniHeart(g, cx, cy + 2, 9, c);
        // Sparkle gleam
        g.setColor(Color.WHITE);
        g.fillOval(cx - 7, cy - 5, 4, 4);
        g.dispose();
        return img;
    }

    private static BufferedImage drawFireIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(239, 68, 68));
        g.fillOval(7, 12, 22, 20);
        g.setColor(new Color(245, 158, 11));
        g.fillOval(11, 16, 14, 15);
        g.setColor(new Color(254, 240, 138));
        g.fillOval(14, 20, 8, 10);
        g.dispose();
        return img;
    }

    private static BufferedImage drawStarIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawMiniStar(g, size / 2, size / 2, 14, new Color(245, 158, 11));
        g.dispose();
        return img;
    }

    private static BufferedImage drawClapIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 158, 11));
        g.fillOval(10, 12, 8, 16);
        g.fillOval(18, 12, 8, 16);
        g.dispose();
        return img;
    }

    private static BufferedImage drawPrayIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(251, 191, 36));
        g.fillRoundRect(11, 8, 6, 20, 4, 4);
        g.fillRoundRect(18, 8, 6, 20, 4, 4);
        g.dispose();
        return img;
    }

    private static BufferedImage drawVictoryIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 158, 11));
        g.fillRoundRect(10, 6, 5, 16, 3, 3);
        g.fillRoundRect(18, 6, 5, 16, 3, 3);
        g.fillRoundRect(10, 18, 14, 12, 6, 6);
        g.dispose();
        return img;
    }

    private static BufferedImage drawHundredIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(220, 38, 38));
        g.setFont(new Font("Segoe UI", Font.BOLD, 14));
        g.drawString("100", 5, 20);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(5, 24, 29, 24);
        g.drawLine(5, 27, 29, 27);
        g.dispose();
        return img;
    }

    private static BufferedImage drawCoffeeIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(180, 83, 9));
        g.fillRoundRect(7, 14, 18, 16, 6, 6);
        g.setStroke(new BasicStroke(2f));
        g.drawArc(22, 16, 8, 10, 270, 180);
        // Steam
        g.setColor(new Color(156, 163, 175));
        g.drawArc(10, 6, 4, 6, 0, 180);
        g.drawArc(16, 4, 4, 6, 0, 180);
        g.dispose();
        return img;
    }

    private static BufferedImage drawGiftIcon() {
        int size = 36;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(239, 68, 68));
        g.fillRoundRect(8, 14, 20, 16, 4, 4);
        g.setColor(new Color(220, 38, 38));
        g.fillRoundRect(6, 10, 24, 6, 3, 3);
        // Ribbon
        g.setColor(new Color(251, 191, 36));
        g.fillRect(16, 10, 4, 20);
        g.drawArc(12, 6, 6, 6, 0, 270);
        g.drawArc(18, 6, 6, 6, 0, 270);
        g.dispose();
        return img;
    }

    /* -------------------------------------------------------------
     *  STICKERS INITIALIZATION (Large, expressive visual cards)
     * ------------------------------------------------------------- */
    private static void initStickers() {
        registerSticker("sticker_love", "Thả tim", "Gửi ngàn yêu thương",
                drawStickerCard("❤️", "BẮN TIM", new Color(254, 226, 226), new Color(239, 68, 68)));
        registerSticker("sticker_like", "Đỉnh chóp", "Tuyệt vời sếp ơi",
                drawStickerCard("👍", "ĐỈNH CHÓP", new Color(224, 242, 254), new Color(2, 132, 199)));
        registerSticker("sticker_haha", "Cười ngất", "Haha chết cười",
                drawStickerCard("😂", "CHẾT CƯỜI", new Color(254, 243, 199), new Color(217, 119, 6)));
        registerSticker("sticker_cry", "Khóc ròng", "Khóc một dòng sông",
                drawStickerCard("😭", "KHÓC RÒNG", new Color(236, 254, 255), new Color(8, 145, 178)));
        registerSticker("sticker_angry", "Bốc hỏa", "Tức giận xì khói",
                drawStickerCard("😡", "BỐC HỎA", new Color(254, 242, 242), new Color(220, 38, 38)));
        registerSticker("sticker_party", "Quẩy lên", "Chúc mừng tiệc tùng",
                drawStickerCard("🥳", "QUẨY LÊN", new Color(250, 232, 255), new Color(192, 38, 211)));
        registerSticker("sticker_sleep", "Ngủ ngon", "Khò khò đi ngủ",
                drawStickerCard("😴", "NGỦ THÔI", new Color(238, 242, 255), new Color(79, 70, 229)));
        registerSticker("sticker_coffee", "Cà phê", "Cà phê chém gió",
                drawStickerCard("☕", "CÀ PHÊ NHÉ", new Color(254, 243, 199), new Color(180, 83, 9)));
        registerSticker("sticker_ok", "Nhất trí", "OK duyệt luôn",
                drawStickerCard("👌", "DUYỆT LUÔN", new Color(240, 253, 244), new Color(22, 163, 74)));
        registerSticker("sticker_hbd", "Sinh nhật", "Happy Birthday",
                drawStickerCard("🎂", "SINH NHẬT", new Color(254, 243, 199), new Color(217, 119, 6)));
        registerSticker("sticker_hug", "Gấu ôm", "Ôm một cái nào",
                drawStickerCard("🐻", "ÔM CÁI NÈ", new Color(254, 243, 199), new Color(146, 64, 14)));
        registerSticker("sticker_rose", "Tặng hoa", "Bông hoa tươi thắm",
                drawStickerCard("🌹", "TẶNG NÈ", new Color(255, 228, 230), new Color(225, 29, 72)));
    }

    private static void registerSticker(String id, String title, String sub, BufferedImage img) {
        STICKERS.put(id, new StickerEntry(id, title, sub, new ImageIcon(img)));
    }

    private static BufferedImage drawStickerCard(String emojiText, String bannerText, Color bg, Color textCol) {
        int w = 96;
        int h = 96;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Card background with rounded borders
        g.setColor(bg);
        g.fillRoundRect(2, 2, w - 4, h - 4, 16, 16);
        g.setColor(new Color(textCol.getRed(), textCol.getGreen(), textCol.getBlue(), 60));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(2, 2, w - 4, h - 4, 16, 16);

        // Big emoji symbol / visual in the center. Draw the heart as a vector
        // because the Windows color-emoji glyph has an off-center left bearing.
        if ("❤️".equals(emojiText)) {
            drawMiniHeart(g, w / 2, 34, 18, textCol);
            g.setColor(new Color(255, 255, 255, 190));
            g.fillOval(w / 2 - 12, 19, 7, 7);
        } else {
            ImageIcon artwork = null;
            for (EmojiEntry entry : EMOJIS.values()) {
                if (emojiText.equals(getEmojiText(entry.id())) && entry.icon() instanceof ImageIcon image) {
                    artwork = image;
                    break;
                }
            }
            if (artwork != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(artwork.getImage(), 21, 6, 54, 54, null);
            } else {
                g.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
                FontMetrics fm = g.getFontMetrics();
                int ex = (w - fm.stringWidth(emojiText)) / 2;
                g.drawString(emojiText, ex, 48);
            }
        }

        // Banner pill at the bottom
        int bw = w - 16;
        int bh = 22;
        int bx = 8;
        int by = h - 28;
        g.setColor(textCol);
        g.fillRoundRect(bx, by, bw, bh, 10, 10);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 10));
        FontMetrics bfm = g.getFontMetrics();
        int tx = bx + (bw - bfm.stringWidth(bannerText)) / 2;
        int ty = by + (bh - bfm.getHeight()) / 2 + bfm.getAscent();
        g.drawString(bannerText, tx, ty);

        g.dispose();
        return img;
    }

    /* -------------------------------------------------------------
     *  ANIMATED GIFS INITIALIZATION (Multi-frame animated Icon)
     * ------------------------------------------------------------- */
    private static void initGifs() {
        registerGif("gif_heart_beat", "Trái tim đập", createHeartBeatGif());
        registerGif("gif_party_dance", "Quẩy chúc mừng", createPartyGif());
        registerGif("gif_thumbs_bounce", "Tuyệt vời", createThumbsBounceGif());
        registerGif("gif_haha_bounce", "Cười rung lắc", createHahaWiggleGif());
        registerGif("gif_fire_flame", "Bốc cháy", createFireFlameGif());
        registerGif("gif_waving_hand", "Vẫy tay chào", createWavingHandGif());
    }

    private static void registerGif(String id, String title, AnimatedIcon icon) {
        GIFS.put(id, new GifEntry(id, title, icon));
    }

    private static AnimatedIcon createHeartBeatGif() {
        List<Image> frames = new ArrayList<>();
        float[] scales = {0.85f, 1.0f, 1.15f, 1.05f};
        for (float s : scales) {
            BufferedImage f = new BufferedImage(90, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = 45;
            int cy = 40;
            int r = (int) (18 * s);
            drawMiniHeart(g, cx, cy + 2, r, new Color(239, 68, 68));
            // Gleam
            g.setColor(Color.WHITE);
            g.fillOval(cx - (int) (10 * s), cy - (int) (8 * s), (int) (6 * s), (int) (6 * s));
            g.dispose();
            frames.add(f);
        }
        return new AnimatedIcon(frames, 160);
    }

    private static AnimatedIcon createPartyGif() {
        List<Image> frames = new ArrayList<>();
        int[] offsets = {-4, 0, 4, 0};
        for (int dy : offsets) {
            BufferedImage f = new BufferedImage(90, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = 42 + dy;
            // Party emoji
            g.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 40));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("🥳", (90 - fm.stringWidth("🥳")) / 2, cy);
            // Confetti
            g.setColor(new Color(59, 130, 246));
            g.fillOval(20 + dy, 18 - dy, 4, 4);
            g.setColor(new Color(239, 68, 68));
            g.fillOval(68 - dy, 14 + dy, 5, 5);
            g.setColor(new Color(245, 158, 11));
            g.fillOval(72 + dy, 32 - dy, 4, 4);
            g.dispose();
            frames.add(f);
        }
        return new AnimatedIcon(frames, 140);
    }

    private static AnimatedIcon createThumbsBounceGif() {
        List<Image> frames = new ArrayList<>();
        int[] heights = {0, -6, -2, -8};
        for (int dh : heights) {
            BufferedImage f = new BufferedImage(90, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = 52 + dh;
            g.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("👍", (90 - fm.stringWidth("👍")) / 2, cy);
            // Sparkle
            drawMiniStar(g, 68, 20 + dh, 6, new Color(245, 158, 11));
            g.dispose();
            frames.add(f);
        }
        return new AnimatedIcon(frames, 150);
    }

    private static AnimatedIcon createHahaWiggleGif() {
        List<Image> frames = new ArrayList<>();
        int[] rotates = {-5, 0, 5, 0};
        for (int r : rotates) {
            BufferedImage f = new BufferedImage(90, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.rotate(Math.toRadians(r), 45, 40);
            g.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("😂", (90 - fm.stringWidth("😂")) / 2, 52);
            g.dispose();
            frames.add(f);
        }
        return new AnimatedIcon(frames, 120);
    }

    private static AnimatedIcon createFireFlameGif() {
        List<Image> frames = new ArrayList<>();
        float[] scales = {0.9f, 1.0f, 1.1f, 0.95f};
        for (float s : scales) {
            BufferedImage f = new BufferedImage(90, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = 45;
            int cy = 40;
            g.setFont(new Font("Segoe UI Emoji", Font.PLAIN, (int) (40 * s)));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("🔥", cx - fm.stringWidth("🔥") / 2, cy + 18);
            g.dispose();
            frames.add(f);
        }
        return new AnimatedIcon(frames, 130);
    }

    private static AnimatedIcon createWavingHandGif() {
        List<Image> frames = new ArrayList<>();
        int[] angles = {-12, 0, 12, 0};
        for (int a : angles) {
            BufferedImage f = new BufferedImage(90, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = f.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.rotate(Math.toRadians(a), 45, 50);
            g.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 42));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("👋", (90 - fm.stringWidth("👋")) / 2, 52);
            g.dispose();
            frames.add(f);
        }
        return new AnimatedIcon(frames, 150);
    }

    /** Multi-frame smooth animated icon looping indefinitely in Swing. */
    public static final class AnimatedIcon implements Icon {
        private final List<Image> frames;
        private int currentFrame = 0;
        private Timer timer;
        private final List<Component> registeredComponents = Collections.synchronizedList(new ArrayList<>());

        public AnimatedIcon(List<Image> frames, int delayMillis) {
            this.frames = frames;
            if (!frames.isEmpty()) {
                timer = new Timer(delayMillis, e -> {
                    currentFrame = (currentFrame + 1) % frames.size();
                    synchronized (registeredComponents) {
                        for (Component c : registeredComponents) {
                            c.repaint();
                        }
                    }
                });
                timer.start();
            }
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (c != null && !registeredComponents.contains(c)) {
                registeredComponents.add(c);
            }
            if (!frames.isEmpty()) {
                Image frame = frames.get(currentFrame % frames.size());
                g.drawImage(frame, x, y, null);
            }
        }

        @Override
        public int getIconWidth() {
            return frames.isEmpty() ? 0 : frames.get(0).getWidth(null);
        }

        @Override
        public int getIconHeight() {
            return frames.isEmpty() ? 0 : frames.get(0).getHeight(null);
        }
    }
}

