package chatp2p.view;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/** Small vector icons that render consistently without emoji fonts. */
final class CallIcons {
    static final Icon PHONE = new VectorIcon(false);
    static final Icon VIDEO = new VectorIcon(true);

    private CallIcons() {
    }

    private static final class VectorIcon implements Icon {
        private final boolean video;

        private VectorIcon(boolean video) {
            this.video = video;
        }

        @Override public int getIconWidth() { return 22; }
        @Override public int getIconHeight() { return 20; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.translate(x, y);
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(component.isEnabled() ? component.getForeground()
                        : new Color(148, 163, 184));
                if (video) {
                    canvas.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    canvas.drawRoundRect(1, 4, 14, 12, 4, 4);
                    Path2D lens = new Path2D.Float();
                    lens.moveTo(16, 8);
                    lens.lineTo(21, 5);
                    lens.lineTo(21, 15);
                    lens.lineTo(16, 12);
                    canvas.draw(lens);
                } else {
                    Path2D handset = new Path2D.Float();
                    handset.moveTo(4, 1);
                    handset.curveTo(2, 1, 1, 3, 2, 6);
                    handset.curveTo(4, 12, 10, 18, 16, 19);
                    handset.curveTo(19, 19, 21, 17, 20, 15);
                    handset.lineTo(17, 12);
                    handset.curveTo(16, 11, 15, 11, 14, 12);
                    handset.lineTo(12, 14);
                    handset.curveTo(9, 13, 7, 11, 6, 8);
                    handset.lineTo(8, 6);
                    handset.curveTo(9, 5, 9, 4, 8, 3);
                    handset.closePath();
                    canvas.fill(handset);
                }
            } finally {
                canvas.dispose();
            }
        }
    }
}
