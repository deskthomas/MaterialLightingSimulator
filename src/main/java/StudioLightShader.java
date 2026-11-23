import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33.*;

final class StudioLightShader {
    static final String SOURCE = """
        uniform vec4 lights[%1$d];
        uniform vec3 lightColors[%1$d];
        uniform vec3 lightAxes[%1$d];
        uniform sampler2DArrayShadow shadowMaps;
        uniform mat4 shadowViews[%1$d];
        uniform float shadowOpacity;
        uniform bool sceneryShadows;

        float fixtureIrradiance(int light, vec3 towardLight, float distanceSquared) {
            float alignment = dot(lightAxes[light], -towardLight);
            float beam = smoothstep(0.72, 0.92, alignment);
            return lights[light].w * mix(0.08, 1.45, beam) / max(distanceSquared, 0.01);
        }

        float filteredShadow(vec3 projected, int layer, float bias) {
            vec2 tap = 0.85 / vec2(textureSize(shadowMaps, 0).xy);
            float coverage = 0.0;
            for (int y = -1; y <= 1; y += 2) {
                for (int x = -1; x <= 1; x += 2) {
                    coverage += texture(shadowMaps, vec4(projected.xy + vec2(x, y) * tap,
                            float(layer), projected.z - bias));
                }
            }
            return coverage * 0.25;
        }

        float lightVisibility(int light, float nDotL, vec3 position) {
            if (shadowOpacity <= 0.0 && !sceneryShadows) return 1.0;
            vec4 clip = shadowViews[light] * vec4(position, 1.0);
            if (clip.w <= 0.0) return 1.0;
            vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
            if (any(lessThanEqual(projected, vec3(0.0)))
                    || any(greaterThanEqual(projected, vec3(1.0)))) return 1.0;
            float bias = max(0.00033, 0.0008 * (1.0 - nDotL));
            float scene = sceneryShadows ? filteredShadow(projected, light + lights.length(), bias) : 1.0;
            if (scene <= 0.001 || shadowOpacity <= 0.0) return scene;
            return min(scene, mix(1.0, filteredShadow(projected, light, bias), shadowOpacity));
        }
        """.formatted(StudioLight.values().length);

    private final int program;
    private final int lightsLocation;
    private final int colorsLocation;
    private final int axesLocation;
    private final int viewsLocation;
    private final int opacityLocation;
    private final int sceneryLocation;

    StudioLightShader(int program) {
        this.program = program;
        lightsLocation = glGetUniformLocation(program, "lights[0]");
        colorsLocation = glGetUniformLocation(program, "lightColors[0]");
        axesLocation = glGetUniformLocation(program, "lightAxes[0]");
        viewsLocation = glGetUniformLocation(program, "shadowViews[0]");
        opacityLocation = glGetUniformLocation(program, "shadowOpacity");
        sceneryLocation = glGetUniformLocation(program, "sceneryShadows");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "shadowMaps"), 2);
        glUseProgram(0);
    }

    void setLights(StudioLighting lighting, float targetHeight) {
        StudioLight[] lights = StudioLight.values();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var positions = stack.mallocFloat(lights.length * 4);
            var colors = stack.mallocFloat(lights.length * 3);
            var axes = stack.mallocFloat(lights.length * 3);
            var axis = new Vector3f();
            for (StudioLight light : lights) {
                StudioLighting.Setting setting = lighting.get(light);
                positions.put(light.x).put(light.y).put(light.z).put(setting.power(light));
                colors.put(setting.red()).put(setting.green()).put(setting.blue());
                axis.set(-light.x, targetHeight - light.y, -light.z).normalize();
                axes.put(axis.x).put(axis.y).put(axis.z);
            }
            glUseProgram(program);
            glUniform4fv(lightsLocation, positions.flip());
            glUniform3fv(colorsLocation, colors.flip());
            glUniform3fv(axesLocation, axes.flip());
            glUseProgram(0);
        }
    }

    void setShadows(StudioShadows shadows, float opacity) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var views = shadows.lightViews();
            var matrices = stack.mallocFloat(16 * views.length);
            for (int i = 0; i < views.length; i++) views[i].get(i * 16, matrices);
            glUseProgram(program);
            glUniformMatrix4fv(viewsLocation, false, matrices);
            glUniform1f(opacityLocation, opacity);
            glUniform1i(sceneryLocation, shadows.hasScenery() ? 1 : 0);
            glUseProgram(0);
        }
    }
}
