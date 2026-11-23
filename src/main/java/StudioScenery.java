import org.joml.Matrix4f;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;
import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT;
import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryUtil.*;

final class StudioScenery implements AutoCloseable {
    private static final float MAX_SCENE_ANISOTROPY = 8.0f;
    private record Surface(int texture, int normalMap, float red, float green, float blue,
                           float opacity, boolean water, boolean concrete) {}
    private static final String VERTEX = """
        #version 330 core
        layout(location = 0) in vec3 position;
        layout(location = 1) in vec3 normal;
        layout(location = 2) in vec2 texcoord;
        layout(location = 3) in mat4 instanceModel;
        uniform mat4 viewProjection;
        out vec3 worldPosition;
        out vec3 worldNormal;
        out vec2 uv;

        void main() {
            vec4 positionInScene = instanceModel * vec4(position, 1.0);
            worldPosition = positionInScene.xyz;
            worldNormal = transpose(inverse(mat3(instanceModel))) * normal;
            uv = texcoord;
            gl_Position = viewProjection * positionInScene;
        }
        """;

    private static final String FRAGMENT = """
        #version 330 core
        in vec3 worldPosition;
        in vec3 worldNormal;
        in vec2 uv;
        uniform sampler2D colorMap;
        uniform sampler2D normalMap;
        uniform vec3 tint;
        uniform bool textured;
        uniform bool hasNormalMap;
        uniform bool water;
        uniform bool concrete;
        uniform float opacity;
        uniform float ambient;
        %s
        out vec4 fragmentColor;

        vec3 mappedNormal(vec3 n) {
            vec2 dx = dFdx(uv), dy = dFdy(uv);
            vec3 px = dFdx(worldPosition), py = dFdy(worldPosition);
            float det = dx.x * dy.y - dx.y * dy.x;
            if (abs(det) < 0.00000001) return n;
            vec3 tangent = normalize((px * dy.y - py * dx.y) * sign(det));
            vec3 bitangent = normalize((py * dx.x - px * dy.x) * sign(det));
            vec3 sampleNormal = texture(normalMap, uv).xyz * 2.0 - 1.0;
            sampleNormal.xy *= 0.65;
            return normalize(mat3(tangent, bitangent, n) * sampleNormal);
        }

        float concreteHash(vec2 cell) {
            vec3 p = fract(vec3(cell.xyx) * 0.1031);
            p += dot(p, p.yzx + 33.33);
            return fract((p.x + p.y) * p.z);
        }

        float concreteNoise(vec2 position) {
            vec2 cell = floor(position);
            vec2 blend = smoothstep(vec2(0.0), vec2(1.0), fract(position));
            return mix(
                    mix(concreteHash(cell), concreteHash(cell + vec2(1, 0)), blend.x),
                    mix(concreteHash(cell + vec2(0, 1)), concreteHash(cell + vec2(1, 1)), blend.x),
                    blend.y);
        }

        void main() {
            vec3 n = normalize(worldNormal);
            if (!gl_FrontFacing) n = -n;
            vec3 geometricNormal = n;
            if (hasNormalMap) n = mappedNormal(n);
            vec3 base = textured ? texture(colorMap, uv).rgb * tint : tint;
            if (concrete) {
                vec3 axis = abs(geometricNormal);
                vec2 plane = axis.y >= max(axis.x, axis.z) ? worldPosition.xz
                        : axis.x > axis.z ? worldPosition.zy : worldPosition.xy;
                float detail = 1.0 - smoothstep(0.7, 2.3, length(fwidth(plane * 53.0)));
                float mottling = concreteNoise(plane * 3.7);
                float pores = concreteNoise(plane * 53.0);
                base *= 0.88 + 0.16 * mottling + (pores - 0.5) * 0.13 * detail;
            }
            if (water) {
                float waves = sin(worldPosition.x * 5.0 + worldPosition.z * 3.3)
                        * sin(worldPosition.z * 5.2 - worldPosition.x * 1.9);
                base *= 0.82 + 0.14 * waves + 0.15 * n.y;
            }
            vec3 irradiance = vec3(ambient * (0.84 + 0.16 * max(0.0, n.y)));
            for (int i = 0; i < lights.length(); i++) {
                if (lights[i].w <= 0.0) continue;
                vec3 toLight = lights[i].xyz - worldPosition;
                float distanceSquared = dot(toLight, toLight);
                vec3 direction = toLight * inversesqrt(max(distanceSquared, 0.000001));
                float facing = dot(geometricNormal, direction);
                float diffuse = max(dot(n, direction), 0.0);
                if (facing > 0.0 && diffuse > 0.0) {
                    float strength = fixtureIrradiance(i, direction, distanceSquared);
                    irradiance += pow(lightColors[i], vec3(2.2)) * diffuse
                            * lightVisibility(i, facing, worldPosition) * strength;
                }
            }
            vec3 linearColor = pow(base, vec3(2.2)) * irradiance;
            vec3 displayColor = pow(linearColor / (1.0 + 0.35 * linearColor), vec3(1.0 / 2.2));
            fragmentColor = vec4(displayColor, opacity);
        }
        """.formatted(StudioLightShader.SOURCE);

    private final int program = Mesh.createProgram(VERTEX, FRAGMENT);
    private final int transformLocation = glGetUniformLocation(program, "viewProjection");
    private final int waterLocation = glGetUniformLocation(program, "water");
    private final int concreteLocation = glGetUniformLocation(program, "concrete");
    private final int opacityLocation = glGetUniformLocation(program, "opacity");
    private final int tintLocation = glGetUniformLocation(program, "tint");
    private final int texturedLocation = glGetUniformLocation(program, "textured");
    private final int hasNormalMapLocation = glGetUniformLocation(program, "hasNormalMap");
    private final StudioLightShader fixtures = new StudioLightShader(program);
    private final float anisotropy = availableAnisotropy();
    private final List<Part> parts = new ArrayList<>();
    private final Map<Integer, Integer> textures = new HashMap<>();

    StudioScenery(ScenePreset preset) {
        try {
            load(preset);
            glUseProgram(program);
            glUniform1i(glGetUniformLocation(program, "colorMap"), 3);
            glUniform1i(glGetUniformLocation(program, "normalMap"), 4);
            glUniform1f(glGetUniformLocation(program, "ambient"), preset.ambient);
            glUseProgram(0);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void load(ScenePreset preset) {
        byte[] bytes;
        try (var input = StudioScenery.class.getResourceAsStream(preset.resource)) {
            if (input == null) throw new IllegalStateException("Missing scenery: " + preset.resource);
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read " + preset.resource, failure);
        }
        var data = memAlloc(bytes.length);
        try {
            data.put(bytes).flip();
            AIScene scene = aiImportFileFromMemory(data, aiProcess_Triangulate, "glb");
            if (scene == null) throw new IllegalStateException("Could not import scenery: " + aiGetErrorString());
            try {
                upload(scene, preset.placement());
            } finally {
                aiReleaseImport(scene);
            }
        } finally {
            memFree(data);
        }
    }

    private void upload(AIScene scene, Matrix4f placement) {
        var surfaces = new Surface[scene.mNumMaterials()];
        for (int i = 0; i < surfaces.length; i++) {
            surfaces[i] = readSurface(scene, AIMaterial.create(scene.mMaterials().get(i)));
        }
        var instances = new ArrayList<List<Matrix4f>>();
        for (int i = 0; i < scene.mNumMeshes(); i++) instances.add(new ArrayList<>());
        collectInstances(scene.mRootNode(), placement, instances);
        for (int i = 0; i < instances.size(); i++) {
            if (instances.get(i).isEmpty()) continue;
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            parts.add(new Part(mesh, instances.get(i), surfaces[mesh.mMaterialIndex()]));
        }
    }

    private Surface readSurface(AIScene scene, AIMaterial material) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIString name = AIString.calloc(stack);
            aiGetMaterialString(material, AI_MATKEY_NAME, 0, 0, name);
            String materialName = name.dataString();
            if (materialName.contains("WATER_VOLUME")) {
                return new Surface(0, 0, 0.20f, 0.67f, 0.65f, 0.62f, true, false);
            }
            int texture = loadTexture(scene, material, aiTextureType_BASE_COLOR);
            if (texture == 0) texture = loadTexture(scene, material, aiTextureType_DIFFUSE);
            int normals = loadTexture(scene, material, aiTextureType_NORMALS);
            AIColor4D color = AIColor4D.calloc(stack);
            if (aiGetMaterialColor(material, AI_MATKEY_BASE_COLOR, 0, 0, color) == aiReturn_SUCCESS) {
                return new Surface(texture, normals, displayColor(color.r()), displayColor(color.g()),
                        displayColor(color.b()), color.a(), false, materialName.startsWith("concrete"));
            }
            return new Surface(texture, normals, 1, 1, 1, 1, false, false);
        }
    }

    private static float displayColor(float linear) {
        return (float) Math.pow(Math.max(0, linear), 1.0 / 2.2);
    }

    private int loadTexture(AIScene scene, AIMaterial material, int kind) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIString path = AIString.calloc(stack);
            if (aiGetMaterialTexture(material, kind, 0, path,
                    (IntBuffer) null, null, null, null, null, null) != aiReturn_SUCCESS) {
                return 0;
            }
            String reference = path.dataString();
            if (!reference.startsWith("*")) {
                throw new IllegalStateException("Expected embedded scenery texture: " + reference);
            }
            int index = Integer.parseInt(reference.substring(1));
            if (textures.containsKey(index)) return textures.get(index);
            var image = AITexture.create(scene.mTextures().get(index));
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            var channels = stack.mallocInt(1);
            var pixels = stbi_load_from_memory(image.pcDataCompressed(), width, height, channels, 4);
            if (pixels == null) throw new IllegalStateException("Could not decode scenery texture: " + stbi_failure_reason());
            int texture = glGenTextures();
            textures.put(index, texture);
            try {
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width.get(0), height.get(0), 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                if (anisotropy > 1.0f) {
                    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAX_ANISOTROPY_EXT, anisotropy);
                }
                glGenerateMipmap(GL_TEXTURE_2D);
                glBindTexture(GL_TEXTURE_2D, 0);
                return texture;
            } finally {
                stbi_image_free(pixels);
            }
        }
    }

    private static float availableAnisotropy() {
        var capabilities = GL.getCapabilities();
        if (capabilities.OpenGL46 || capabilities.GL_ARB_texture_filter_anisotropic
                || capabilities.GL_EXT_texture_filter_anisotropic) {
            return Math.min(MAX_SCENE_ANISOTROPY, glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT));
        }
        return 1.0f;
    }

    void setLights(StudioLighting lighting, float targetHeight) {
        fixtures.setLights(lighting, targetHeight);
    }

    void setShadows(StudioShadows shadows, float opacity) {
        fixtures.setShadows(shadows, opacity);
    }

    void drawDepth() {
        for (Part part : parts) {
            if (part.surface.opacity() >= 1.0f) part.draw();
        }
        glBindVertexArray(0);
    }

    private static void collectInstances(AINode node, Matrix4f parent, List<List<Matrix4f>> instances) {
        AIMatrix4x4 a = node.mTransformation();
        var transform = new Matrix4f(parent).mul(new Matrix4f(
                a.a1(), a.b1(), a.c1(), a.d1(), a.a2(), a.b2(), a.c2(), a.d2(),
                a.a3(), a.b3(), a.c3(), a.d3(), a.a4(), a.b4(), a.c4(), a.d4()));
        for (int i = 0; i < node.mNumMeshes(); i++) {
            instances.get(node.mMeshes().get(i)).add(transform);
        }
        for (int i = 0; i < node.mNumChildren(); i++) {
            collectInstances(AINode.create(node.mChildren().get(i)), transform, instances);
        }
    }

    void draw(Matrix4f viewProjection) {
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE3);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(transformLocation, false, viewProjection.get(stack.mallocFloat(16)));
            for (Part part : parts) {
                if (part.surface.opacity() >= 1.0f) drawPart(part);
            }
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            try {
                for (Part part : parts) {
                    if (part.surface.opacity() < 1.0f) drawPart(part);
                }
            } finally {
                glDepthMask(true);
                glDisable(GL_BLEND);
            }
        } finally {
            glActiveTexture(GL_TEXTURE4);
            glBindTexture(GL_TEXTURE_2D, 0);
            glActiveTexture(GL_TEXTURE3);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindVertexArray(0);
            glActiveTexture(GL_TEXTURE0);
            glUseProgram(0);
        }
    }

    private void drawPart(Part part) {
        Surface material = part.surface;
        glUniform3f(tintLocation, material.red(), material.green(), material.blue());
        glUniform1i(waterLocation, material.water() ? 1 : 0);
        glUniform1i(concreteLocation, material.concrete() ? 1 : 0);
        glUniform1f(opacityLocation, material.opacity());
        glUniform1i(texturedLocation, material.texture() == 0 ? 0 : 1);
        glUniform1i(hasNormalMapLocation, material.normalMap() == 0 ? 0 : 1);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, material.normalMap());
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, material.texture());
        part.draw();
    }

    @Override
    public void close() {
        parts.forEach(Part::close);
        textures.values().forEach(texture -> glDeleteTextures(texture));
        glDeleteProgram(program);
    }

    private static final class Part implements AutoCloseable {
        private final int vao = glGenVertexArrays();
        private final int geometry = glGenBuffers();
        private final int elements = glGenBuffers();
        private final int transforms = glGenBuffers();
        private final Surface surface;
        private final int indexCount;
        private final int instanceCount;

        Part(AIMesh mesh, List<Matrix4f> instances, Surface surface) {
            this.surface = surface;
            this.instanceCount = instances.size();
            var vertices = memAllocFloat(mesh.mNumVertices() * 8);
            var indices = memAllocInt(mesh.mNumFaces() * 3);
            var matrices = memAllocFloat(instances.size() * 16);
            try {
                var positions = mesh.mVertices();
                var normals = mesh.mNormals();
                var uv = mesh.mTextureCoords(0);
                for (int i = 0; i < mesh.mNumVertices(); i++) {
                    var p = positions.get(i);
                    var n = normals.get(i);
                    var t = uv.get(i);
                    vertices.put(p.x()).put(p.y()).put(p.z()).put(n.x()).put(n.y()).put(n.z())
                            .put(t.x()).put(1.0f - t.y());
                }
                for (int i = 0; i < mesh.mNumFaces(); i++) {
                    var face = mesh.mFaces().get(i).mIndices();
                    if (face.remaining() == 3) indices.put(face);
                }
                indexCount = indices.position();
                for (int i = 0; i < instances.size(); i++) instances.get(i).get(i * 16, matrices);
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, geometry);
                glBufferData(GL_ARRAY_BUFFER, vertices.flip(), GL_STATIC_DRAW);
                attribute(0, 3, 8, 0, 0);
                attribute(1, 3, 8, 3, 0);
                attribute(2, 2, 8, 6, 0);
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elements);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.flip(), GL_STATIC_DRAW);
                glBindBuffer(GL_ARRAY_BUFFER, transforms);
                glBufferData(GL_ARRAY_BUFFER, matrices, GL_STATIC_DRAW);
                for (int column = 0; column < 4; column++) attribute(3 + column, 4, 16, column * 4, 1);
                glBindVertexArray(0);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            } finally {
                memFree(vertices);
                memFree(indices);
                memFree(matrices);
            }
        }

        private static void attribute(int index, int size, int stride, int offset, int divisor) {
            glVertexAttribPointer(index, size, GL_FLOAT, false, stride * Float.BYTES, (long) offset * Float.BYTES);
            glEnableVertexAttribArray(index);
            glVertexAttribDivisor(index, divisor);
        }

        void draw() {
            glBindVertexArray(vao);
            glDrawElementsInstanced(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L, instanceCount);
        }

        @Override
        public void close() {
            glDeleteVertexArrays(vao);
            glDeleteBuffers(geometry);
            glDeleteBuffers(elements);
            glDeleteBuffers(transforms);
        }
    }
}
