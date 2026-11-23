import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL33.*;

final class Mesh implements AutoCloseable {
    private static final int FIXTURE_HARDWARE = 6;
    private static final String VERTEX_SHADER = """
        #version 330 core

        layout(location = 0) in vec3 position;
        layout(location = 1) in vec3 vertexNormal;
        uniform mat4 viewProjection;
        uniform mat4 model;
        uniform bool hasVertexNormals;
        out vec3 worldPosition;
        out vec3 worldNormal;
        out vec3 localPosition;
        out vec3 localNormal;

        void main() {
            vec4 world = model * vec4(position, 1.0);
            worldPosition = world.xyz;
            localPosition = position;
            localNormal = vertexNormal;
            worldNormal = hasVertexNormals
                    ? transpose(inverse(mat3(model))) * vertexNormal
                    : vec3(0.0);
            gl_Position = viewProjection * world;
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 330 core

        in vec3 worldPosition;
        in vec3 worldNormal;
        in vec3 localPosition;
        in vec3 localNormal;

        uniform vec3 baseColor;
        uniform bool emissive;
        uniform bool hasVertexNormals;
        uniform int material;
        uniform float roughness;
        uniform vec3 eye;
        uniform mat4 view;
        uniform samplerCube studioReflection;
        uniform sampler2D opaqueScene;
        %s

        out vec4 fragmentColor;

        const float PI = 3.14159265;
        const int METAL = 1;
        const int FABRIC = 2;
        const int WOOD = 3;
        const int GLASS = 4;
        const int BULB_GLASS = 5;
        const int FIXTURE_HARDWARE = %d;
        const int BULB_METAL = %d;
        const int POLISHED_BRASS = %d;
        const int POLISHED_STEEL = %d;
        const int IVORY_WOOD = %d;

        vec3 decodeColor(vec3 rgb) {
            return pow(rgb, vec3(2.2));
        }

        vec3 encodeColor(vec3 linearColor) {
            // Compress bright specular peaks while leaving dark tones readable.
            return pow(linearColor / (vec3(1.0) + linearColor), vec3(1.0 / 2.2));
        }

        vec3 fresnel(float angle, vec3 f0) {
            return f0 + (1.0 - f0) * pow(1.0 - clamp(angle, 0.0, 1.0), 5.0);
        }

        float ggx(float nDotH, float surfaceRoughness) {
            float alpha = surfaceRoughness * surfaceRoughness;
            float alphaSquared = alpha * alpha;
            float denominator = nDotH * nDotH * (alphaSquared - 1.0) + 1.0;
            return alphaSquared / max(PI * denominator * denominator, 0.000001);
        }

        float visibility(float nDotV, float nDotL, float surfaceRoughness) {
            float alpha = surfaceRoughness * surfaceRoughness;
            float a2 = alpha * alpha;
            float viewTerm = nDotL * sqrt(nDotV * nDotV * (1.0 - a2) + a2);
            float lightTerm = nDotV * sqrt(nDotL * nDotL * (1.0 - a2) + a2);
            return 0.5 / max(viewTerm + lightTerm, 0.0001);
        }

        vec3 reflection(vec3 direction, float surfaceRoughness) {
            float mipLevel = clamp(surfaceRoughness * 7.0, 0.0, 7.0);
            return decodeColor(textureLod(studioReflection, direction, mipLevel).rgb);
        }

        float filteredWave(float coordinate) {
            return sin(coordinate) * (1.0 - smoothstep(0.6, 2.6, fwidth(coordinate)));
        }

        float woodGrain(vec3 p) {
            vec2 warp = vec2(sin(p.y * 5.0 + p.z * 4.0), cos(p.y * 7.0 - p.x * 4.0)) * 0.055;
            float ring = length((p.xz + warp + vec2(0.12, -0.09)) * vec2(1.0, 1.35));
            float grain = filteredWave(ring * 83.0 + 1.8 * sin(p.z * 12.0 + p.y * 2.5));
            return grain * 0.7 + filteredWave(p.x * 260.0 + p.z * 93.0 + grain * 4.0) * 0.3;
        }

        float fabricWeave(vec3 p) {
            float warp = filteredWave(p.x * 220.0 + p.z * 51.0);
            float weft = filteredWave(p.y * 215.0 + p.z * 130.0);
            return warp * weft;
        }

        vec3 addSurfaceDetail(vec3 normal, float height) {
            vec3 dx = dFdx(worldPosition);
            vec3 dy = dFdy(worldPosition);
            vec3 acrossY = cross(dy, normal);
            vec3 acrossX = cross(normal, dx);
            float determinant = dot(dx, acrossY);
            if (abs(determinant) < 0.00000001) {
                return normal;
            }
            vec3 gradient = (dFdx(height) * acrossY + dFdy(height) * acrossX) / determinant;
            return normalize(normal - gradient);
        }

        vec3 glassColor(vec3 normal, vec3 incident, vec3 reflected, bool thinShell) {
            vec3 transmitted = refract(incident, normal, 1.0 / 1.5);
            vec2 dimensions = vec2(textureSize(opaqueScene, 0));
            vec2 screenUv = gl_FragCoord.xy / dimensions;
            vec2 bend = (mat3(view) * (transmitted - incident)).xy;
            float aspect = dimensions.x / dimensions.y;
            float offset = (thinShell ? 0.025 : 0.095) / max(length(eye - worldPosition), 0.8);
            vec2 refractedUv = clamp(screenUv + bend * vec2(offset / aspect, offset), 0.0, 1.0);
            vec3 tint = mix(vec3(1.0), decodeColor(baseColor), thinShell ? 0.12 : 0.27);
            vec3 background = decodeColor(texture(opaqueScene, refractedUv).rgb) * tint;
            float reflectance = fresnel(dot(normal, -incident), vec3(thinShell ? 0.055 : 0.04)).r;
            return mix(background, reflected, reflectance);
        }

        void main() {
            if (emissive) {
                fragmentColor = vec4(baseColor, 1.0);
                return;
            }

            vec3 normal;
            if (hasVertexNormals) {
                normal = normalize(worldNormal);
            } else {
                normal = normalize(cross(dFdx(worldPosition), dFdy(worldPosition)));
            }

            bool fixtureHardware = material == FIXTURE_HARDWARE;
            bool bulbMetal = material == BULB_METAL;
            bool polishedBrass = material == POLISHED_BRASS;
            bool polishedSteel = material == POLISHED_STEEL;
            bool diffuseOnly = material == 0 || fixtureHardware;
            vec3 brightness = vec3(0.18);
            vec3 albedo = decodeColor(baseColor);
            float surfaceRoughness = roughness;
            float exteriorPolish = 0.0;
            float visorRecess = 0.0;
            if (material == IVORY_WOOD) {
                float grain = woodGrain(localPosition);
                float streak = filteredWave(localPosition.x * 21.0 + localPosition.z * 18.0
                        + 0.9 * sin(localPosition.y * 20.0));
                albedo *= 0.95 + grain * 0.045 - 0.12 * smoothstep(0.66, 0.99, streak);
                surfaceRoughness += grain * 0.025;
            } else if (material == WOOD) {
                float grain = woodGrain(localPosition);
                albedo *= 0.85 + 0.24 * grain;
                surfaceRoughness = clamp(surfaceRoughness + grain * 0.08, 0.2, 0.9);
                normal = addSurfaceDetail(normal, grain * 0.00085);
            } else if (material == FABRIC) {
                float weave = fabricWeave(localPosition);
                albedo *= 0.92 + 0.25 * weave;
                normal = addSurfaceDetail(normal, weave * 0.00035);
            } else if (bulbMetal) {
                float brushing = filteredWave(localPosition.y * 170.0
                        + 2.4 * sin(localPosition.y * 93.0 + localPosition.x * 32.0));
                albedo *= 0.94 + 0.06 * brushing;
                surfaceRoughness += brushing * 0.055;
                normal = addSurfaceDetail(normal, brushing * 0.000075);
            } else if (polishedBrass) {
                // The inside of the bowl faces inward toward the chalice's axis.
                vec2 radial = localPosition.xz / max(length(localPosition.xz), 0.001);
                float radialFacing = dot(normalize(localNormal).xz, radial);
                float bowl = smoothstep(0.53, 0.63, localPosition.y);
                exteriorPolish = mix(1.0, smoothstep(-0.35, 0.25, radialFacing), bowl);
                float buffing = filteredWave(localPosition.y * 145.0 + length(localPosition.xz) * 26.0);
                surfaceRoughness = mix(roughness, 0.14 + buffing * 0.025, exteriorPolish);
                albedo = mix(albedo, vec3(0.92, 0.57, 0.23), 0.55 * exteriorPolish);
                normal = addSurfaceDetail(normal, exteriorPolish * buffing * 0.000035);
            } else if (polishedSteel) {
                visorRecess = 1.0 - smoothstep(0.247, 0.265, length(localPosition.xz));
                visorRecess *= smoothstep(0.39, 0.43, localPosition.y)
                        * (1.0 - smoothstep(0.58, 0.63, localPosition.y));
                albedo *= mix(1.0, 0.012, visorRecess);
                surfaceRoughness = mix(roughness, 0.72, visorRecess);
            }

            float metallic = material == METAL || bulbMetal || polishedBrass || polishedSteel ? 1.0 : 0.0;
            vec3 v = normalize(eye - worldPosition);
            float nDotV = max(dot(normal, v), 0.001);
            vec3 f0 = mix(vec3(0.04), albedo, metallic);
            vec3 direct = vec3(0.0);
            for (int i = 0; i < lights.length(); i++) {
                if (lights[i].w <= 0.0) {
                    continue;
                }
                vec3 toLight = lights[i].xyz - worldPosition;
                float actualDistanceSquared = dot(toLight, toLight);
                vec3 lightDirection = toLight * inversesqrt(max(actualDistanceSquared, 0.000001));
                float diffuse = max(dot(normal, lightDirection), 0.0);
                float irradiance = fixtureIrradiance(i, lightDirection, actualDistanceSquared);
                if (bulbMetal) {
                    irradiance /= 1.0 + irradiance * 0.22;
                }
                if (diffuse > 0.0) {
                    irradiance *= lightVisibility(i, diffuse, worldPosition);
                }
                if (diffuseOnly) {
                    if (fixtureHardware) {
                        // Keep the nearby filament from clipping the socket and support wires to white.
                        irradiance /= 1.0 + irradiance;
                    }
                    brightness += lightColors[i] * (irradiance * diffuse);
                } else if (diffuse > 0.0) {
                    vec3 h = normalize(v + lightDirection);
                    float nDotH = max(dot(normal, h), 0.0);
                    vec3 f = fresnel(dot(v, h), f0);
                    vec3 specular = f * ggx(nDotH, surfaceRoughness)
                            * visibility(nDotV, diffuse, surfaceRoughness);
                    vec3 lambert = (1.0 - f) * (1.0 - metallic) * albedo / PI;
                    if (material == GLASS || material == BULB_GLASS) {
                        lambert = vec3(0.0);
                    } else if (material == FABRIC) {
                        float sheen = pow(1.0 - nDotV, 3.0) * (0.15 + 0.2 * (1.0 - nDotH));
                        specular += sqrt(albedo) * sheen;
                    }
                    direct += (lambert + specular) * decodeColor(lightColors[i]) * irradiance * diffuse;
                }
            }

            if (diffuseOnly) {
                fragmentColor = vec4(baseColor * brightness, 1.0);
                return;
            }

            vec3 reflected = reflection(reflect(-v, normal), surfaceRoughness);
            if (material == GLASS || material == BULB_GLASS) {
                vec3 transmitted = glassColor(normal, -v, reflected, material == BULB_GLASS);
                fragmentColor = vec4(pow(clamp(transmitted + direct, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
                return;
            }

            vec3 environmentSpecular = reflected * fresnel(nDotV, f0);
            if (bulbMetal) {
                environmentSpecular *= 1.55;
            }
            if (polishedSteel) {
                environmentSpecular *= mix(2.15, 0.06, visorRecess);
            }
            environmentSpecular *= 1.0 + 1.15 * exteriorPolish;
            vec3 ambient = albedo * (1.0 - metallic) * 0.07;
            fragmentColor = vec4(encodeColor(ambient + direct + environmentSpecular), 1.0);
        }
        """.formatted(StudioLightShader.SOURCE, FIXTURE_HARDWARE, MaterialLook.BULB_METAL.shaderId,
            MaterialLook.POLISHED_BRASS.shaderId, MaterialLook.POLISHED_STEEL.shaderId,
            MaterialLook.IVORY_WOOD.shaderId);

    private final int program;
    private final int vao;
    private final int vbo;
    private final int vertexCount;
    private final int viewProjectionLocation;
    private final int modelLocation;
    private final int baseColorLocation;
    private final int emissiveLocation;
    private final boolean hasVertexNormals;
    private final int hasVertexNormalsLocation;
    private final StudioLightShader fixtureShader;
    private final int materialLocation;
    private final int roughnessLocation;
    private final int eyeLocation;
    private final int viewLocation;

    private Mesh(float[] vertices, boolean hasVertexNormals) {
        this.hasVertexNormals = hasVertexNormals;
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        modelLocation = glGetUniformLocation(program, "model");
        baseColorLocation = glGetUniformLocation(program, "baseColor");
        emissiveLocation = glGetUniformLocation(program, "emissive");
        hasVertexNormalsLocation = glGetUniformLocation(program, "hasVertexNormals");
        fixtureShader = new StudioLightShader(program);
        materialLocation = glGetUniformLocation(program, "material");
        roughnessLocation = glGetUniformLocation(program, "roughness");
        eyeLocation = glGetUniformLocation(program, "eye");
        viewLocation = glGetUniformLocation(program, "view");
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "studioReflection"), 0);
        glUniform1i(glGetUniformLocation(program, "opaqueScene"), 1);
        glUseProgram(0);

        int floatsPerVertex = hasVertexNormals ? 6 : 3;
        vertexCount = vertices.length / floatsPerVertex;

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        int stride = floatsPerVertex * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        if (hasVertexNormals) {
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
            glEnableVertexAttribArray(1);
        }

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    static Mesh withNormals(float[] vertices) {
        return new Mesh(vertices, true);
    }

    static Mesh box() {
        float[] corners = {
                -0.5f, -0.5f, -0.5f,
                 0.5f, -0.5f, -0.5f,
                 0.5f,  0.5f, -0.5f,
                -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,
                 0.5f, -0.5f,  0.5f,
                 0.5f,  0.5f,  0.5f,
                -0.5f,  0.5f,  0.5f
        };
        int[] triangles = {
                0, 3, 2, 0, 2, 1,
                4, 5, 6, 4, 6, 7,
                0, 4, 7, 0, 7, 3,
                1, 2, 6, 1, 6, 5,
                0, 1, 5, 0, 5, 4,
                3, 7, 6, 3, 6, 2
        };

        float[] vertices = new float[triangles.length * 3];
        for (int i = 0; i < triangles.length; i++) {
            System.arraycopy(corners, triangles[i] * 3, vertices, i * 3, 3);
        }
        return new Mesh(vertices, false);
    }

    void setLights(StudioLighting lighting, float targetHeight) {
        fixtureShader.setLights(lighting, targetHeight);
    }

    void setShadows(StudioShadows shadows, float opacity) {
        fixtureShader.setShadows(shadows, opacity);
    }

    void draw(Matrix4f viewProjection, Matrix4f model, float red, float green, float blue) {
        draw(viewProjection, model, red, green, blue, false);
    }

    void draw(Matrix4f viewProjection, Matrix4f model, float red, float green, float blue,
              boolean emissive) {
        glUseProgram(program);
        glUniform1i(materialLocation, 0);
        drawGeometry(viewProjection, model, red, green, blue, emissive);
    }

    void drawFixtureHardware(Matrix4f viewProjection, Matrix4f model, float red, float green, float blue) {
        glUseProgram(program);
        glUniform1i(materialLocation, FIXTURE_HARDWARE);
        drawGeometry(viewProjection, model, red, green, blue, false);
    }

    void drawMaterial(Matrix4f viewProjection, Matrix4f view, Matrix4f model, Vector3f eye,
                      float red, float green, float blue, MaterialLook material, float roughness) {
        glUseProgram(program);
        glUniform1i(materialLocation, material.shaderId);
        glUniform1f(roughnessLocation, roughness);
        glUniform3f(eyeLocation, eye.x, eye.y, eye.z);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(viewLocation, false, view.get(stack.mallocFloat(16)));
        }
        drawGeometry(viewProjection, model, red, green, blue, false);
    }

    private void drawGeometry(Matrix4f viewProjection, Matrix4f model, float red, float green,
                              float blue, boolean emissive) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buffer = stack.mallocFloat(16);
            glUniformMatrix4fv(viewProjectionLocation, false, viewProjection.get(buffer));
            glUniformMatrix4fv(modelLocation, false, model.get(buffer));
        }
        glUniform3f(baseColorLocation, red, green, blue);
        glUniform1i(emissiveLocation, emissive ? 1 : 0);
        glUniform1i(hasVertexNormalsLocation, hasVertexNormals ? 1 : 0);
        drawPositions();
        glUseProgram(0);
    }

    void drawPositions() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        try {
            int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
            try {
                int program = glCreateProgram();
                glAttachShader(program, vertexShader);
                glAttachShader(program, fragmentShader);
                glLinkProgram(program);

                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                    String log = glGetProgramInfoLog(program);
                    glDeleteProgram(program);
                    throw new IllegalStateException("Shader program link failed:\n" + log);
                }

                glDetachShader(program, vertexShader);
                glDetachShader(program, fragmentShader);
                return program;
            } finally {
                glDeleteShader(fragmentShader);
            }
        } finally {
            glDeleteShader(vertexShader);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Shader compilation failed:\n" + log);
        }

        return shader;
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteProgram(program);
    }
}
