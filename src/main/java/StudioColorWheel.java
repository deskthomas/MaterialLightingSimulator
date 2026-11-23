import javax.swing.JButton;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.IntConsumer;

final class StudioColorWheel extends JButton {
    private static final int SIZE = 194;
    private static final int OUTER_RADIUS = 90;
    private static final int INNER_RADIUS = 67;
    private static final int SWATCH_RADIUS = 25;
    private static final double HUE_OFFSET = 0.08;
    private static final double TURN = 2.0 * Math.PI;
    private static final BufferedImage RING = renderRing();

    private final Color marker;
    private final IntConsumer onHue;
    private int rgb;
    private boolean draggingHue;
    private boolean pressedSwatch;

    StudioColorWheel(Color marker, IntConsumer onHue) {
        this.marker = marker;
        this.onHue = onHue;
        setPreferredSize(new Dimension(SIZE, SIZE));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setToolTipText("Drag the ring to set hue. Click the swatch to enter an RGB color.");
        enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    void setRgb(int rgb) {
        this.rgb = rgb;
        repaint();
    }

    @Override
    protected void processMouseEvent(MouseEvent event) {
        if (event.getButton() != MouseEvent.BUTTON1) {
            super.processMouseEvent(event);
            return;
        }
        double x = event.getX() - getWidth() / 2.0;
        double y = event.getY() - getHeight() / 2.0;
        double distance = Math.hypot(x, y);
        if (event.getID() == MouseEvent.MOUSE_PRESSED) {
            requestFocusInWindow();
            draggingHue = distance >= INNER_RADIUS - 6 && distance <= OUTER_RADIUS + 6;
            pressedSwatch = distance <= SWATCH_RADIUS;
            if (draggingHue) {
                pickHue(x, y);
            }
        } else if (event.getID() == MouseEvent.MOUSE_RELEASED) {
            draggingHue = false;
            if (pressedSwatch && distance <= SWATCH_RADIUS) {
                doClick(0);
            }
            pressedSwatch = false;
        }
    }

    @Override
    protected void processMouseMotionEvent(MouseEvent event) {
        if (draggingHue && event.getID() == MouseEvent.MOUSE_DRAGGED) {
            pickHue(event.getX() - getWidth() / 2.0, event.getY() - getHeight() / 2.0);
        }
        super.processMouseMotionEvent(event);
    }

    private void pickHue(double x, double y) {
        if (x == 0 && y == 0) {
            return;
        }
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
        float saturation = hsb[1] < 0.35f ? 0.83f : hsb[1];
        float brightness = hsb[2] < 0.25f ? 1.0f : hsb[2];
        setRgb(Color.HSBtoRGB(hue(x, y), saturation, brightness) & 0xffffff);
        onHue.accept(rgb);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        var brush = (Graphics2D) graphics.create();
        brush.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int centerX = getWidth() / 2, centerY = getHeight() / 2;
        brush.drawImage(RING, centerX - SIZE / 2, centerY - SIZE / 2, null);
        Color selected = new Color(rgb);
        brush.setColor(selected);
        brush.fillOval(centerX - SWATCH_RADIUS, centerY - SWATCH_RADIUS, SWATCH_RADIUS * 2, SWATCH_RADIUS * 2);
        if (isFocusOwner()) {
            brush.setColor(marker);
            brush.drawOval(centerX - SWATCH_RADIUS - 3, centerY - SWATCH_RADIUS - 3,
                    SWATCH_RADIUS * 2 + 6, SWATCH_RADIUS * 2 + 6);
        }
        float[] hsb = Color.RGBtoHSB(selected.getRed(), selected.getGreen(), selected.getBlue(), null);
        if (hsb[1] > 0.01f && hsb[2] > 0.01f) {
            double angle = -(hsb[0] + HUE_OFFSET) * TURN;
            double radius = (OUTER_RADIUS + INNER_RADIUS) / 2.0;
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius);
            brush.setColor(marker);
            brush.fillOval(x - 12, y - 12, 24, 24);
            brush.setColor(selected);
            brush.fillOval(x - 9, y - 9, 18, 18);
        }
        brush.dispose();
    }

    private static BufferedImage renderRing() {
        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = x + 0.5 - SIZE / 2.0, dy = y + 0.5 - SIZE / 2.0;
                double distance = Math.hypot(dx, dy);
                double coverage = Math.min(OUTER_RADIUS + 0.5 - distance, distance - INNER_RADIUS + 0.5);
                if (coverage > 0) {
                    int alpha = (int) (Math.min(coverage, 1.0) * 255);
                    int color = Color.HSBtoRGB(hue(dx, dy), 0.95f, 1.0f) & 0xffffff;
                    image.setRGB(x, y, (alpha << 24) | color);
                }
            }
        }
        return image;
    }

    private static float hue(double x, double y) {
        double hue = -Math.atan2(y, x) / TURN - HUE_OFFSET;
        return (float) (hue - Math.floor(hue));
    }
}
