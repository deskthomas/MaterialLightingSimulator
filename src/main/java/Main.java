import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.Platform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;

import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

public class Main {

    private static final float FLOOR_Y = -1.5f;
    private static final float DISPLAY_FOCUS_Y = FLOOR_Y + 0.22f;
    private static final float WALK_LIMIT = 7.5f;
    private static final float STANDING_EYE_Y = 1.74f;
    private static final float CROUCHING_EYE_Y = FLOOR_Y + 0.08f;
    private static final float MAX_WALK_EYE_Y = 3.5f;
    private static final float WALK_HEIGHT_SPEED = 1.8f;
    private static final float WALK_SPEED = 3.0f;
    private static final double MOUSE_SENSITIVITY = 0.004;
    private static final float OBJECT_ROTATION_SENSITIVITY = 0.006f;
    private static final double DEFAULT_FIELD_OF_VIEW = 45.0;

    private long window;
    private double yaw = Math.toRadians(30);
    private double pitch = Math.toRadians(20);
    private double distance = 4.0;
    private boolean walking;
    private float walkX;
    private float walkEyeY = STANDING_EYE_Y;
    private float walkHeightTarget = STANDING_EYE_Y;
    private float walkZ = 4.0f;
    private double walkYaw;
    private double walkPitch = Math.toRadians(-29);
    private double walkFieldOfView = DEFAULT_FIELD_OF_VIEW;
    private int displayIndex = DisplayObjects.DEFAULT_INDEX;
    private float pendingObjectYaw;
    private float pendingObjectTilt;
    private StudioLighting lighting = StudioLighting.defaults();
    private ScenePreset selectedScene = ScenePreset.AUTO_WORKSHOP;
    private final ConcurrentLinkedQueue<Runnable> controlChanges = new ConcurrentLinkedQueue<>();
    private final StudioControls controls = new StudioControls(
            index -> controlChanges.add(() -> selectObject(index)),
            (light, setting) -> controlChanges.add(() -> lighting = lighting.with(light, setting)),
            scene -> controlChanges.add(() -> selectedScene = scene));
    private final SceneViewport viewport = new SceneViewport(
            key -> controlChanges.add(() -> handleKey(key)),
            zoom -> controlChanges.add(() -> zoomCamera(zoom)),
            (x, y, altDown) -> controlChanges.add(() -> look(x, y, altDown)));

    public static void main(String[] args) {
        if (Platform.get() == Platform.MACOSX) {
            // Let AWT own the Cocoa event loop and dispatch GLFW's Cocoa calls to it.
            Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
            Toolkit.getDefaultToolkit();
        }
        new Main().run();
    }

    private void run() {
        init();

        try (Mesh box = Mesh.box();
             StudioRoom room = new StudioRoom();
             DisplayObjects objects = new DisplayObjects(FLOOR_Y);
             StudioEnvironment environment = new StudioEnvironment();
             StudioShadows shadows = new StudioShadows(DISPLAY_FOCUS_Y);
             ViewportFramebuffer target = new ViewportFramebuffer()) {
            controls.mount(viewport, displayIndex, lighting,
                    () -> controlChanges.add(() -> glfwSetWindowShouldClose(window, true)));
            updateWindowTitle();
            loop(box, room, objects, environment, shadows, target);
        } finally {
            controls.close();
            cleanup();
        }
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        // GLFW supplies the OpenGL context. Swing displays the offscreen framebuffer.
        window = glfwCreateWindow(64, 64, "MaterialLightingSimulator renderer", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
    }

    private void handleKey(int key) {
        if (key == KeyEvent.VK_ESCAPE) {
            glfwSetWindowShouldClose(window, true);
        } else if (key == KeyEvent.VK_TAB) {
            setWalking(!walking);
        } else if (key == KeyEvent.VK_OPEN_BRACKET || key == KeyEvent.VK_CLOSE_BRACKET) {
            int direction = key == KeyEvent.VK_OPEN_BRACKET ? -1 : 1;
            int next = Math.floorMod(displayIndex + direction, DisplayObjects.COUNT);
            selectObject(next);
            controls.selectObject(next);
        }
    }

    private void look(double deltaX, double deltaY, boolean altDown) {
        if (altDown) {
            pendingObjectYaw += (float) deltaX * OBJECT_ROTATION_SENSITIVITY;
            pendingObjectTilt += (float) deltaY * OBJECT_ROTATION_SENSITIVITY;
            return;
        }
        if (walking) {
            walkYaw += deltaX * MOUSE_SENSITIVITY;
            walkPitch -= deltaY * MOUSE_SENSITIVITY;
            double limit = Math.toRadians(85);
            walkPitch = Math.max(-limit, Math.min(limit, walkPitch));
        } else {
            yaw -= deltaX * MOUSE_SENSITIVITY;
            pitch += deltaY * MOUSE_SENSITIVITY;
            pitch = Math.max(Math.toRadians(-10), Math.min(Math.toRadians(85), pitch));
        }
    }

    private void zoomCamera(double amount) {
        double zoomFactor = Math.pow(0.9, amount);
        if (walking) {
            walkFieldOfView = Math.max(20.0, Math.min(70.0, walkFieldOfView * zoomFactor));
        } else {
            distance = Math.max(1.25, Math.min(7.0, distance * zoomFactor));
        }
    }

    private void setWalking(boolean walking) {
        this.walking = walking;
        updateWindowTitle();
    }

    private void selectObject(int index) {
        if (displayIndex != index) {
            pendingObjectYaw = 0;
            pendingObjectTilt = 0;
        }
        displayIndex = index;
        updateWindowTitle();
    }

    private void updateWindowTitle() {
        String controls = walking ? "WASD walk, Up/Down height, hold C to crouch, scroll zoom, Tab orbit"
                : "Mouse orbit, scroll zoom, WASD walk";
        this.controls.setTitle("Material Lighting Simulator | " + DisplayObjects.name(displayIndex)
                + " | Alt + mouse turns object | Ctrl for controls | " + controls);
    }

    private int keyDown(int key) {
        return viewport.keyDown(key) ? 1 : 0;
    }

    private void updateWalking(float deltaTime) {
        int forward = keyDown(KeyEvent.VK_W) - keyDown(KeyEvent.VK_S);
        int right = keyDown(KeyEvent.VK_D) - keyDown(KeyEvent.VK_A);
        int vertical = keyDown(KeyEvent.VK_UP) - keyDown(KeyEvent.VK_DOWN);
        boolean crouching = keyDown(KeyEvent.VK_C) == 1;
        if (!walking) {
            if (forward == 0 && right == 0 && vertical == 0 && !crouching) {
                return;
            }
            setWalking(true);
        }

        updateWalkHeight(vertical, crouching, deltaTime);
        if (forward == 0 && right == 0) {
            return;
        }

        double step = WALK_SPEED * deltaTime / Math.hypot(forward, right);
        double sin = Math.sin(walkYaw);
        double cos = Math.cos(walkYaw);
        walkX = clampToRoom(walkX + (float) ((forward * sin + right * cos) * step));
        walkZ = clampToRoom(walkZ + (float) ((-forward * cos + right * sin) * step));
    }

    private void updateWalkHeight(int vertical, boolean crouching, float deltaTime) {
        if (!crouching) {
            float nextHeight = walkHeightTarget + vertical * WALK_HEIGHT_SPEED * deltaTime;
            walkHeightTarget = Math.max(CROUCHING_EYE_Y, Math.min(MAX_WALK_EYE_Y, nextHeight));
        }
        float targetHeight = crouching ? CROUCHING_EYE_Y : walkHeightTarget;
        float blend = (float) (1.0 - Math.exp(-12.0 * deltaTime));
        walkEyeY += (targetHeight - walkEyeY) * blend;
    }

    private static float clampToRoom(float coordinate) {
        return Math.max(-WALK_LIMIT, Math.min(WALK_LIMIT, coordinate));
    }

    private void updateView(Matrix4f view) {
        if (walking) {
            double horizontal = Math.cos(walkPitch);
            float lookX = walkX + (float) (horizontal * Math.sin(walkYaw));
            float lookY = walkEyeY + (float) Math.sin(walkPitch);
            float lookZ = walkZ - (float) (horizontal * Math.cos(walkYaw));
            view.setLookAt(walkX, walkEyeY, walkZ, lookX, lookY, lookZ, 0, 1, 0);
            return;
        }

        double horizontalDistance = distance * Math.cos(pitch);
        float inspection = (float) Math.max(0, (2.8 - distance) / (2.8 - 1.25));
        float targetY = -0.30f + (FLOOR_Y + 0.52f) * inspection;
        float eyeX = (float) (horizontalDistance * Math.sin(yaw));
        float eyeY = Math.max(FLOOR_Y + 0.10f, targetY + (float) (distance * Math.sin(pitch)));
        float eyeZ = (float) (horizontalDistance * Math.cos(yaw));
        view.setLookAt(eyeX, eyeY, eyeZ, 0, targetY, 0, 0, 1, 0);
    }

    private void loop(Mesh box, StudioRoom room, DisplayObjects objects, StudioEnvironment environment,
                      StudioShadows shadows, ViewportFramebuffer target) {
        Matrix4f view = new Matrix4f();
        Matrix4f viewProjection = new Matrix4f();
        Vector3f eye = new Vector3f();
        ScenePreset activeScene = null;
        double previousTime = glfwGetTime();
        StudioLighting uploadedLighting = null;
        int shadowCaster = -1;
        while (!glfwWindowShouldClose(window)) {
            long frameStart = System.nanoTime();
            glfwPollEvents();
            Runnable change;
            while ((change = controlChanges.poll()) != null) {
                change.run();
            }
            if (glfwWindowShouldClose(window)) {
                break;
            }
            double currentTime = glfwGetTime();
            float deltaTime = (float) Math.min(0.05, currentTime - previousTime);
            previousTime = currentTime;

            SceneViewport.Size size = viewport.renderSize();
            int framebufferWidth = size.width();
            int framebufferHeight = size.height();
            if (framebufferWidth == 0 || framebufferHeight == 0) {
                glfwWaitEventsTimeout(0.02);
                continue;
            }

            updateWalking(deltaTime);
            updateView(view);
            view.originAffine(eye);

            boolean poseChanged = pendingObjectYaw != 0 || pendingObjectTilt != 0;
            if (poseChanged) {
                objects.rotate(displayIndex, pendingObjectYaw, pendingObjectTilt, view);
                pendingObjectYaw = 0;
                pendingObjectTilt = 0;
            }
            boolean casterChanged = shadowCaster != displayIndex;
            boolean lightsChanged = uploadedLighting != lighting;
            boolean sceneChanged = activeScene != selectedScene;
            boolean glassObject = DisplayObjects.isGlass(displayIndex);
            if (sceneChanged) {
                room.setScene(selectedScene);
                shadows.captureScenery(room::drawSceneryDepth);
                activeScene = selectedScene;
                glClearColor(activeScene.backgroundRed, activeScene.backgroundGreen, activeScene.backgroundBlue, 1.0f);
            }
            if (casterChanged || poseChanged) {
                objects.captureShadow(displayIndex, shadows);
                shadowCaster = displayIndex;
            }
            if (casterChanged || sceneChanged) {
                float shadowOpacity = glassObject ? 0.38f : 1.0f;
                box.setShadows(shadows, shadowOpacity);
                room.setShadows(shadows, shadowOpacity);
                objects.setShadows(shadows, shadowOpacity);
            }
            if (lightsChanged || sceneChanged) {
                box.setLights(lighting, DISPLAY_FOCUS_Y);
                room.setLights(lighting, DISPLAY_FOCUS_Y);
                objects.setLights(lighting, DISPLAY_FOCUS_Y);
                uploadedLighting = lighting;
            }
            if (lightsChanged || casterChanged || poseChanged || sceneChanged) {
                environment.captureRoom(DISPLAY_FOCUS_Y, framebufferWidth, framebufferHeight, capture -> {
                    room.drawOpaque(box, capture, lighting);
                    room.drawSocketsForReflection(capture);
                    room.drawGlow(capture, lighting);
                });
            }
            target.bind(framebufferWidth, framebufferHeight);

            float aspect = (float) framebufferWidth / framebufferHeight;
            float fieldOfView = (float) Math.toRadians(walking ? walkFieldOfView : DEFAULT_FIELD_OF_VIEW);
            viewProjection
                    .setPerspective(fieldOfView, aspect, 0.1f, 100.0f)
                    .mul(view);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            room.drawOpaque(box, viewProjection, lighting);
            environment.bindTextures();
            room.drawMetalSockets(viewProjection, view, eye);
            if (!glassObject) {
                objects.draw(displayIndex, viewProjection, view, eye);
            }
            environment.captureBackground(target.renderWidth(), target.renderHeight());
            environment.bindTextures();
            room.drawGlass(viewProjection, view, eye);
            room.drawGlow(viewProjection, lighting);
            if (glassObject) {
                environment.captureBackground(target.renderWidth(), target.renderHeight());
                objects.draw(displayIndex, viewProjection, view, eye);
            }
            target.present(viewport);
            long remaining = 16_666_667L - (System.nanoTime() - frameStart);
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
            }
        }
    }

    private void cleanup() {
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }
}
