import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL33.*;

final class StudioEnvironment implements AutoCloseable {
    private static final int FACE_SIZE = 256;
    private static final float[][] DIRECTIONS = {
            { 1, 0, 0, 0, -1, 0}, {-1,  0, 0, 0, -1,  0},
            { 0, 1, 0, 0,  0, 1}, { 0, -1, 0, 0,  0, -1},
            { 0, 0, 1, 0, -1, 0}, { 0,  0,-1, 0, -1,  0}
    };

    private final int cube = glGenTextures();
    private final int sceneColor = glGenTextures();
    private final int framebuffer = glGenFramebuffers();
    private final int depth = glGenRenderbuffers();
    private int sceneWidth;
    private int sceneHeight;

    StudioEnvironment() {
        glBindTexture(GL_TEXTURE_CUBE_MAP, cube);
        for (int i = 0; i < DIRECTIONS.length; i++) {
            glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, 0, GL_RGB8,
                    FACE_SIZE, FACE_SIZE, 0, GL_RGB, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
        glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);

        glBindTexture(GL_TEXTURE_2D, sceneColor);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, FACE_SIZE, FACE_SIZE);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }

    void captureRoom(float height, int viewportWidth, int viewportHeight, Consumer<Matrix4f> drawRoom) {
        Matrix4f projection = new Matrix4f().setPerspective((float) (Math.PI / 2), 1, 0.05f, 40);
        Matrix4f view = new Matrix4f();
        Matrix4f viewProjection = new Matrix4f();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        try {
            glViewport(0, 0, FACE_SIZE, FACE_SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            for (int i = 0; i < DIRECTIONS.length; i++) {
                float[] direction = DIRECTIONS[i];
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, cube, 0);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Could not render the studio reflection cubemap");
                }
                view.setLookAt(0, height, 0, direction[0], height + direction[1], direction[2],
                        direction[3], direction[4], direction[5]);
                projection.mul(view, viewProjection);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                drawRoom.accept(viewProjection);
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, viewportWidth, viewportHeight);
        }

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_CUBE_MAP, cube);
        glGenerateMipmap(GL_TEXTURE_CUBE_MAP);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
    }

    void captureBackground(int width, int height) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, sceneColor);
        if (width != sceneWidth || height != sceneHeight) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, width, height, 0,
                    GL_RGB, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            sceneWidth = width;
            sceneHeight = height;
        }
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        glActiveTexture(GL_TEXTURE0);
    }

    void bindTextures() {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, sceneColor);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_CUBE_MAP, cube);
    }

    @Override
    public void close() {
        glDeleteFramebuffers(framebuffer);
        glDeleteRenderbuffers(depth);
        glDeleteTextures(cube);
        glDeleteTextures(sceneColor);
    }
}
