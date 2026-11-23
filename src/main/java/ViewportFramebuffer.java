import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;

final class ViewportFramebuffer implements AutoCloseable {
    private static final int SUPERSAMPLE = 2;

    private final int maximumSize = Math.min(glGetInteger(GL_MAX_RENDERBUFFER_SIZE),
            glGetInteger(GL_MAX_TEXTURE_SIZE));
    private final int framebuffer = glGenFramebuffers();
    private final int color = glGenRenderbuffers();
    private final int depth = glGenRenderbuffers();
    private final int resolveFramebuffer = glGenFramebuffers();
    private final int resolveColor = glGenRenderbuffers();
    private IntBuffer pixels;
    private int width;
    private int height;
    private int renderWidth;
    private int renderHeight;

    void bind(int panelWidth, int panelHeight) {
        double displayScale = Math.min(1.0, (double) maximumSize / Math.max(panelWidth, panelHeight));
        int newWidth = Math.max(1, (int) (panelWidth * displayScale));
        int newHeight = Math.max(1, (int) (panelHeight * displayScale));
        if (newWidth != width || newHeight != height) {
            double scale = Math.min(SUPERSAMPLE, (double) maximumSize / Math.max(newWidth, newHeight));
            renderWidth = (int) (newWidth * scale);
            renderHeight = (int) (newHeight * scale);

            glBindFramebuffer(GL_FRAMEBUFFER, resolveFramebuffer);
            attachColor(resolveColor, newWidth, newHeight);
            checkComplete();

            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            attachColor(color, renderWidth, renderHeight);
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, renderWidth, renderHeight);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            checkComplete();
            resizeReadback(newWidth, newHeight);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, renderWidth, renderHeight);
    }

    private static void attachColor(int buffer, int width, int height) {
        glBindRenderbuffer(GL_RENDERBUFFER, buffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, buffer);
    }

    private static void checkComplete() {
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Could not create the scene viewport");
        }
    }

    int renderWidth() {
        return renderWidth;
    }

    int renderHeight() {
        return renderHeight;
    }

    private void resizeReadback(int newWidth, int newHeight) {
        IntBuffer replacement = memAllocInt(Math.multiplyExact(newWidth, newHeight));
        memFree(pixels);
        pixels = replacement;
        width = newWidth;
        height = newHeight;
    }

    void present(SceneViewport viewport) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFramebuffer);
        glBlitFramebuffer(0, 0, renderWidth, renderHeight, 0, 0, width, height,
                GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, resolveFramebuffer);
        glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        viewport.present(pixels, width, height);
    }

    @Override
    public void close() {
        memFree(pixels);
        glDeleteFramebuffers(framebuffer);
        glDeleteFramebuffers(resolveFramebuffer);
        glDeleteRenderbuffers(color);
        glDeleteRenderbuffers(resolveColor);
        glDeleteRenderbuffers(depth);
    }
}
