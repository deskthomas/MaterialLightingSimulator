import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.function.IntConsumer;

import static org.lwjgl.opengl.GL33.*;

final class StudioShadows implements AutoCloseable {
    private static final int SIZE = 1024;
    private static final String VERTEX_SHADER = """
        #version 330 core
        layout(location = 0) in vec3 position;
        layout(location = 3) in mat4 instanceModel;
        uniform mat4 lightModelViewProjection;
        uniform bool scenery;

        void main() {
            vec4 point = vec4(position, 1.0);
            gl_Position = lightModelViewProjection * (scenery ? instanceModel * point : point);
        }
        """;
    private static final String FRAGMENT_SHADER = """
        #version 330 core
        void main() {}
        """;

    private final int program = Mesh.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
    private final int transformLocation = glGetUniformLocation(program, "lightModelViewProjection");
    private final int sceneryLocation = glGetUniformLocation(program, "scenery");
    private final int framebuffer = glGenFramebuffers();
    private final int depth = glGenTextures();
    private final Matrix4f[] lightViews;
    private boolean sceneryReady;

    StudioShadows(float targetHeight) {
        StudioLight[] lights = StudioLight.values();
        lightViews = new Matrix4f[lights.length];
        Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(96), 1, 0.15f, 25);
        for (int i = 0; i < lights.length; i++) {
            StudioLight light = lights[i];
            boolean vertical = light.x == 0 && light.z == 0;
            lightViews[i] = new Matrix4f(projection).lookAt(light.x, light.y, light.z,
                    0, targetHeight, 0, 0, vertical ? 0 : 1, vertical ? -1 : 0);
        }

        glBindTexture(GL_TEXTURE_2D_ARRAY, depth);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT24, SIZE, SIZE, lights.length * 2,
                0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depth, 0, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            close();
            throw new IllegalStateException("Could not create the fixture shadow maps");
        }
    }

    Matrix4f[] lightViews() {
        return lightViews;
    }

    void capture(Mesh caster, Matrix4f model) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocFloat(16);
            var transform = new Matrix4f();
            captureLayers(false, i -> {
                lightViews[i].mul(model, transform);
                glUniformMatrix4fv(transformLocation, false, transform.get(buffer));
                caster.drawPositions();
            });
        }
    }

    void captureScenery(Runnable drawScenery) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocFloat(16);
            captureLayers(true, i -> {
                glUniformMatrix4fv(transformLocation, false, lightViews[i].get(buffer));
                drawScenery.run();
            });
            sceneryReady = true;
        }
    }

    boolean hasScenery() {
        return sceneryReady;
    }

    private void captureLayers(boolean scenery, IntConsumer drawDepth) {
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, SIZE, SIZE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.25f, 1.0f);
        glUseProgram(program);
        glUniform1i(sceneryLocation, scenery ? 1 : 0);
        int firstLayer = scenery ? lightViews.length : 0;
        try {
            for (int i = 0; i < lightViews.length; i++) {
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, depth, 0, firstLayer + i);
                glClear(GL_DEPTH_BUFFER_BIT);
                drawDepth.accept(i);
            }
        } finally {
            glUseProgram(0);
            glDisable(GL_POLYGON_OFFSET_FILL);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D_ARRAY, depth);
        glActiveTexture(GL_TEXTURE0);
    }

    @Override
    public void close() {
        glDeleteFramebuffers(framebuffer);
        glDeleteTextures(depth);
        glDeleteProgram(program);
    }
}
