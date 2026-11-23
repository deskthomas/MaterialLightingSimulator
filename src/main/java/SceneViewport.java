import javax.swing.JComponent;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

final class SceneViewport extends JComponent {
    record Size(int width, int height) {}

    private final Object frameLock = new Object();
    private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();
    private volatile Size renderSize = new Size(0, 0);
    private BufferedImage frame;
    private boolean captured;
    private Robot mouse;
    private Cursor hiddenCursor;
    private Point lastPointer;
    private Runnable focusControls = () -> {};

    SceneViewport(IntConsumer onKey, DoubleConsumer onZoom, PointerMotion onLook) {
        setBackground(new Color(0x101018));
        setOpaque(true);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        setPreferredSize(new Dimension(830, 700));

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                renderSize = new Size(getWidth(), getHeight());
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                releaseCamera();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int key = event.getKeyCode();
                if (key == KeyEvent.VK_CONTROL) {
                    event.consume();
                    releaseCamera();
                    focusControls.run();
                } else if (captured && heldKeys.add(key)) {
                    event.consume();
                    onKey.accept(key);
                }
            }

            @Override
            public void keyReleased(KeyEvent event) {
                heldKeys.remove(event.getKeyCode());
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) {
                    captureCamera();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (captured && mouse != null && isShowing()) {
                    Point center = screenCenter();
                    mouse.mouseMove(center.x, center.y);
                }
            }
        });
        addMouseWheelListener(event -> {
            if (captured) {
                onZoom.accept(-event.getPreciseWheelRotation());
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                moveCamera(event, onLook);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                moveCamera(event, onLook);
            }
        });
    }

    @FunctionalInterface
    interface PointerMotion {
        void accept(double x, double y, boolean altDown);
    }

    void setControlFocusAction(Runnable action) {
        focusControls = action;
    }

    void captureCamera() {
        if (captured || !isShowing()) {
            return;
        }
        requestFocusInWindow();
        captured = true;
        lastPointer = null;
        if (mouse == null) {
            try {
                mouse = new Robot(getGraphicsConfiguration().getDevice());
                var blank = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                hiddenCursor = getToolkit().createCustomCursor(blank, new Point(), "Scene camera");
            } catch (AWTException | RuntimeException ignored) {
                mouse = null;
            }
        }
        if (mouse != null) {
            setCursor(hiddenCursor);
            Point center = screenCenter();
            mouse.mouseMove(center.x, center.y);
        }
    }

    private void releaseCamera() {
        captured = false;
        heldKeys.clear();
        lastPointer = null;
        setCursor(Cursor.getDefaultCursor());
    }

    private void moveCamera(MouseEvent event, PointerMotion onLook) {
        if (!captured || !isShowing()) {
            return;
        }
        Point current = event.getLocationOnScreen();
        Point previous = mouse == null ? lastPointer : screenCenter();
        lastPointer = current;
        if (previous == null || previous.equals(current)) {
            return;
        }
        boolean altDown = event.isAltDown() || heldKeys.contains(KeyEvent.VK_ALT);
        onLook.accept(current.x - previous.x, current.y - previous.y, altDown);
        if (mouse != null) {
            mouse.mouseMove(previous.x, previous.y);
        }
    }

    private Point screenCenter() {
        Point center = getLocationOnScreen();
        center.translate(getWidth() / 2, getHeight() / 2);
        return center;
    }

    boolean keyDown(int key) {
        return heldKeys.contains(key);
    }

    Size renderSize() {
        return renderSize;
    }

    void present(IntBuffer pixels, int width, int height) {
        synchronized (frameLock) {
            if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }
            int[] destination = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < height; y++) {
                pixels.get((height - 1 - y) * width, destination, y * width, width);
            }
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        synchronized (frameLock) {
            if (frame != null) {
                g.drawImage(frame, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}
