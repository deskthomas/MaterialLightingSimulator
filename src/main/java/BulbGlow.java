import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33.*;

final class BulbGlow implements AutoCloseable {
    private static final String VERTEX_SHADER = """
        #version 330 core

        uniform mat4 viewProjection;
        uniform vec3 center;
        uniform float radius;
        out vec2 offset;

        void main() {
            offset = vec2(gl_VertexID & 1, gl_VertexID >> 1) * 2.0 - 1.0;
            vec3 right = normalize(vec3(viewProjection[0][0], viewProjection[1][0], viewProjection[2][0]));
            vec3 up = normalize(vec3(viewProjection[0][1], viewProjection[1][1], viewProjection[2][1]));
            vec3 point = center + radius * (right * offset.x + up * offset.y);
            vec4 clip = viewProjection * vec4(point, 1.0);
            // Put the glare in front of its own glass while retaining the filament's screen position.
            vec4 front = viewProjection * vec4(point + cross(right, up) * 0.25, 1.0);
            if (clip.w > 0.0 && front.w > 0.0) {
                clip.z = front.z * clip.w / front.w;
            }
            gl_Position = clip;
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 330 core

        uniform vec3 color;
        uniform float strength;
        in vec2 offset;
        out vec4 fragmentColor;

        void main() {
            float distanceSquared = dot(offset, offset);
            float edge = 1.0 - smoothstep(0.45, 1.0, distanceSquared);
            float core = 0.42 * exp(-38.0 * distanceSquared);
            float halo = 0.14 * exp(-5.8 * distanceSquared);
            fragmentColor = vec4(color * strength * (core + halo) * edge, 0.0);
        }
        """;

    private final int program = Mesh.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
    private final int vao = glGenVertexArrays();
    private final int viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
    private final int centerLocation = glGetUniformLocation(program, "center");
    private final int radiusLocation = glGetUniformLocation(program, "radius");
    private final int colorLocation = glGetUniformLocation(program, "color");
    private final int strengthLocation = glGetUniformLocation(program, "strength");

    void draw(Matrix4f viewProjection, StudioLighting lighting) {
        glUseProgram(program);
        glBindVertexArray(vao);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDepthMask(false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(viewProjectionLocation, false, viewProjection.get(stack.mallocFloat(16)));
            for (StudioLight light : StudioLight.values()) {
                var setting = lighting.get(light);
                if (!setting.shining()) {
                    continue;
                }
                float glow = (float) Math.sqrt(setting.intensityPercent() / 100.0f);
                glUniform3f(centerLocation, light.x, light.y, light.z);
                glUniform1f(radiusLocation, 0.26f + 0.22f * glow);
                glUniform3f(colorLocation, setting.red(), setting.green(), setting.blue());
                glUniform1f(strengthLocation, glow);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            }
        } finally {
            glDepthMask(true);
            glDisable(GL_BLEND);
            glBindVertexArray(0);
            glUseProgram(0);
        }
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
    }
}
