import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;

import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Main {

    private static final float STAGE_HALF_WIDTH = 1.6f;
    private static final float STAGE_TOP = -1.0f;
    private static final float STAGE_HEIGHT = 0.5f;
    private static final float WALK_RADIUS = 0.25f;
    private static final float WALK_LIMIT = 7.5f;
    private static final float STANDING_EYE_Y = 1.74f;
    private static final float CROUCHING_EYE_Y = STAGE_TOP - 0.02f;
    private static final float MAX_WALK_EYE_Y = 3.5f;
    private static final float WALK_HEIGHT_SPEED = 1.8f;
    private static final float WALK_SPEED = 3.0f;
    private static final double MOUSE_SENSITIVITY = 0.004;
    private static final double DEFAULT_FIELD_OF_VIEW = 45.0;

    private long window;
    private int framebufferWidth;
    private int framebufferHeight;
    private double yaw = Math.toRadians(30);
    private double pitch = Math.toRadians(20);
    private double distance = 4.0;
    private boolean cursorPositionKnown;
    private boolean walking;
    private float walkX;
    private float walkEyeY = STANDING_EYE_Y;
    private float walkHeightTarget = STANDING_EYE_Y;
    private float walkZ = 4.0f;
    private double walkYaw;
    private double walkPitch = Math.toRadians(-29);
    private double walkFieldOfView = DEFAULT_FIELD_OF_VIEW;
    private double lastMouseX;
    private double lastMouseY;
    private int displayIndex = DisplayObjects.DEFAULT_INDEX;
    private StudioLighting lighting = StudioLighting.defaults();
    private final ConcurrentLinkedQueue<Runnable> controlChanges = new ConcurrentLinkedQueue<>();
    private final StudioControls controls = new StudioControls(
            index -> controlChanges.add(() -> selectObject(index)),
            (light, setting) -> controlChanges.add(() -> lighting = lighting.with(light, setting)));

    public static void main(String[] args) {
        new Main().run();
    }

    private void run() {
        init();

        try (Mesh box = Mesh.box();
             StudioRoom room = new StudioRoom();
             DisplayObjects objects = new DisplayObjects(STAGE_TOP);
             StudioEnvironment environment = new StudioEnvironment()) {
            loop(box, room, objects, environment);
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
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        window = glfwCreateWindow(1280, 720, "MaterialLightingSimulator", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) {
                glfwSetWindowShouldClose(window, true);
            } else if (key == GLFW_KEY_TAB && action == GLFW_PRESS) {
                setWalking(!walking);
            } else if (action == GLFW_PRESS
                    && (key == GLFW_KEY_LEFT_CONTROL || key == GLFW_KEY_RIGHT_CONTROL)) {
                glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                cursorPositionKnown = false;
                controls.show(displayIndex, lighting);
            } else if (action == GLFW_PRESS
                    && (key == GLFW_KEY_LEFT_BRACKET || key == GLFW_KEY_RIGHT_BRACKET)) {
                int direction = key == GLFW_KEY_LEFT_BRACKET ? -1 : 1;
                int next = Math.floorMod(displayIndex + direction, DisplayObjects.COUNT);
                selectObject(next);
                controls.selectObject(next);
            }
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);

            glfwGetFramebufferSize(window, width, height);
            resizeFramebuffer(width.get(0), height.get(0));
        }

        glfwSetFramebufferSizeCallback(window, (handle, width, height) ->
                resizeFramebuffer(width, height));
        glEnable(GL_DEPTH_TEST);

        glfwSetWindowFocusCallback(window, (handle, focused) -> {
            cursorPositionKnown = false;
            glfwSetInputMode(handle, GLFW_CURSOR, focused ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        });
        glfwSetMouseButtonCallback(window, (handle, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS
                    && glfwGetInputMode(handle, GLFW_CURSOR) == GLFW_CURSOR_NORMAL) {
                cursorPositionKnown = false;
                glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            }
        });

        glfwSetCursorPosCallback(window, (handle, x, y) -> {
            double deltaX = x - lastMouseX;
            double deltaY = y - lastMouseY;
            lastMouseX = x;
            lastMouseY = y;
            if (glfwGetInputMode(handle, GLFW_CURSOR) != GLFW_CURSOR_DISABLED) {
                return;
            }
            if (!cursorPositionKnown) {
                cursorPositionKnown = true;
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
        });

        glfwSetScrollCallback(window, (handle, xOffset, yOffset) -> {
            double zoomFactor = Math.pow(0.9, yOffset);
            if (walking) {
                walkFieldOfView = Math.max(20.0, Math.min(70.0, walkFieldOfView * zoomFactor));
            } else {
                distance = Math.max(1.25, Math.min(7.0, distance * zoomFactor));
            }
        });

        glfwShowWindow(window); // fixed
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
        setWalking(false);
    }

    private void resizeFramebuffer(int width, int height) {
        framebufferWidth = width;
        framebufferHeight = height;
        glViewport(0, 0, width, height);
    }

    private void setWalking(boolean walking) {
        this.walking = walking;
        cursorPositionKnown = false;
        updateWindowTitle();
    }

    private void selectObject(int index) {
        displayIndex = index;
        updateWindowTitle();
    }

    private void updateWindowTitle() {
        String controls = walking ? "WASD walk, Up/Down height, hold C to crouch, scroll zoom, Tab orbit"
                : "Mouse orbit, scroll zoom, WASD walk";
        glfwSetWindowTitle(window, "MaterialLightingSimulator | " + DisplayObjects.name(displayIndex)
                + " | Ctrl opens controls | [ / ] change object | " + controls);
    }

    private int keyDown(int key) {
        return glfwGetKey(window, key) == GLFW_PRESS ? 1 : 0;
    }

    private void updateWalking(float deltaTime) {
        int forward = keyDown(GLFW_KEY_W) - keyDown(GLFW_KEY_S);
        int right = keyDown(GLFW_KEY_D) - keyDown(GLFW_KEY_A);
        int vertical = keyDown(GLFW_KEY_UP) - keyDown(GLFW_KEY_DOWN);
        boolean crouching = keyDown(GLFW_KEY_C) == 1;
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
        float nextX = clampToRoom(walkX + (float) ((forward * sin + right * cos) * step));
        float nextZ = clampToRoom(walkZ + (float) ((-forward * cos + right * sin) * step));

        if (!insideStage(nextX, walkZ)) {
            walkX = nextX;
        }
        if (!insideStage(walkX, nextZ)) {
            walkZ = nextZ;
        }
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

    private static boolean insideStage(float x, float z) {
        float limit = STAGE_HALF_WIDTH + WALK_RADIUS;
        return Math.abs(x) < limit && Math.abs(z) < limit;
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
        float targetY = -0.70f * inspection;
        float eyeX = (float) (horizontalDistance * Math.sin(yaw));
        float eyeY = targetY + (float) (distance * Math.sin(pitch));
        float eyeZ = (float) (horizontalDistance * Math.cos(yaw));
        view.setLookAt(eyeX, eyeY, eyeZ, 0, targetY, 0, 0, 1, 0);
    }

    private void loop(Mesh box, StudioRoom room, DisplayObjects objects, StudioEnvironment environment) {
        Matrix4f view = new Matrix4f();
        Matrix4f viewProjection = new Matrix4f();
        Vector3f eye = new Vector3f();
        Matrix4f stageModel = new Matrix4f()
                .translation(0.0f, STAGE_TOP - STAGE_HEIGHT / 2, 0.0f)
                .scale(STAGE_HALF_WIDTH * 2, STAGE_HEIGHT, STAGE_HALF_WIDTH * 2);
        glClearColor(0.1f, 0.1f, 0.12f, 1.0f);
        double previousTime = glfwGetTime();
        StudioLighting uploadedLighting = null;
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            Runnable change;
            while ((change = controlChanges.poll()) != null) {
                change.run();
            }
            double currentTime = glfwGetTime();
            float deltaTime = (float) Math.min(0.05, currentTime - previousTime);
            previousTime = currentTime;

            if (framebufferWidth == 0 || framebufferHeight == 0) {
                glfwWaitEventsTimeout(0.1);
                continue;
            }

            updateWalking(deltaTime);
            updateView(view);
            view.originAffine(eye);

            if (uploadedLighting != lighting) {
                box.setLights(lighting);
                room.setLights(lighting);
                objects.setLights(lighting);
                environment.captureRoom(STAGE_TOP + 0.22f, framebufferWidth, framebufferHeight, capture -> {
                    room.drawOpaque(box, capture, lighting);
                    room.drawSocketsForReflection(capture);
                    box.draw(capture, stageModel, 0.65f, 0.58f, 0.48f);
                    room.drawGlow(capture, lighting);
                });
                uploadedLighting = lighting;
            }

            float aspect = (float) framebufferWidth / framebufferHeight;
            float fieldOfView = (float) Math.toRadians(walking ? walkFieldOfView : DEFAULT_FIELD_OF_VIEW);
            viewProjection
                    .setPerspective(fieldOfView, aspect, 0.1f, 100.0f)
                    .mul(view);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            room.drawOpaque(box, viewProjection, lighting);
            box.draw(viewProjection, stageModel, 0.65f, 0.58f, 0.48f);
            environment.bindTextures();
            room.drawMetalSockets(viewProjection, view, eye);
            boolean glassObject = DisplayObjects.isGlass(displayIndex);
            if (!glassObject) {
                objects.draw(displayIndex, viewProjection, view, eye);
            }
            environment.captureBackground(framebufferWidth, framebufferHeight);
            environment.bindTextures();
            room.drawGlass(viewProjection, view, eye);
            room.drawGlow(viewProjection, lighting);
            if (glassObject) {
                environment.captureBackground(framebufferWidth, framebufferHeight);
                objects.draw(displayIndex, viewProjection, view, eye);
            }
            glfwSwapBuffers(window);
        }
    }

    private void cleanup() {
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }
}
