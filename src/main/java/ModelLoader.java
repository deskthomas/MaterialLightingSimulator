import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

import static org.lwjgl.assimp.Assimp.*;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

final class ModelLoader {
    private ModelLoader() {}

    static Mesh loadGlb(String resource) {
        return Mesh.withNormals(loadGeometry(resource));
    }

    static float[] loadGeometry(String resource) {
        byte[] bytes;
        try (InputStream input = ModelLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing model resource: " + resource);
            }
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read model " + resource, e);
        }

        ByteBuffer data = memAlloc(bytes.length);
        try {
            data.put(bytes).flip();
            AIScene scene = aiImportFileFromMemory(data,
                    aiProcess_Triangulate | aiProcess_GenSmoothNormals
                            | aiProcess_PreTransformVertices, "glb");
            if (scene == null) {
                throw new IllegalArgumentException("Could not load " + resource + ": " + aiGetErrorString());
            }
            try {
                return copyTriangles(scene);
            } finally {
                aiReleaseImport(scene);
            }
        } finally {
            memFree(data);
        }
    }

    private static float[] copyTriangles(AIScene scene) {
        var meshes = scene.mMeshes();
        if (meshes == null) {
            throw new IllegalArgumentException("Model has no meshes");
        }

        int triangleCount = 0;
        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(meshes.get(i));
            for (int j = 0; j < mesh.mNumFaces(); j++) {
                if (mesh.mFaces().get(j).mNumIndices() == 3) {
                    triangleCount++;
                }
            }
        }
        if (triangleCount == 0) {
            throw new IllegalArgumentException("Model has no triangles");
        }

        float[] vertices = new float[Math.multiplyExact(triangleCount, 18)];
        int offset = 0;
        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(meshes.get(i));
            AIVector3D.Buffer positions = mesh.mVertices();
            AIVector3D.Buffer normals = mesh.mNormals();
            AIFace.Buffer faces = mesh.mFaces();
            for (int j = 0; j < mesh.mNumFaces(); j++) {
                AIFace face = faces.get(j);
                if (face.mNumIndices() != 3) {
                    continue;
                }
                if (normals == null) {
                    throw new IllegalArgumentException("Model has a triangle mesh without normals");
                }
                var indices = face.mIndices();
                for (int k = 0; k < 3; k++) {
                    int index = indices.get(k);
                    AIVector3D position = positions.get(index);
                    AIVector3D normal = normals.get(index);
                    vertices[offset++] = position.x();
                    vertices[offset++] = position.y();
                    vertices[offset++] = position.z();
                    vertices[offset++] = normal.x();
                    vertices[offset++] = normal.y();
                    vertices[offset++] = normal.z();
                }
            }
        }

        fitToStage(vertices);
        return vertices;
    }

    private static void fitToStage(float[] vertices) {
        float minX = vertices[0], maxX = vertices[0];
        float minY = vertices[1], maxY = vertices[1];
        float minZ = vertices[2], maxZ = vertices[2];
        for (int i = 6; i < vertices.length; i += 6) {
            minX = Math.min(minX, vertices[i]);
            maxX = Math.max(maxX, vertices[i]);
            minY = Math.min(minY, vertices[i + 1]);
            maxY = Math.max(maxY, vertices[i + 1]);
            minZ = Math.min(minZ, vertices[i + 2]);
            maxZ = Math.max(maxZ, vertices[i + 2]);
        }

        float centerX = minX + (maxX - minX) * 0.5f;
        float centerZ = minZ + (maxZ - minZ) * 0.5f;
        float height = maxY - minY;
        if (!(height > 0) || !Float.isFinite(height)) {
            throw new IllegalArgumentException("Model must have a finite, positive height");
        }
        for (int i = 0; i < vertices.length; i += 6) {
            vertices[i] = (vertices[i] - centerX) / height;
            vertices[i + 1] = (vertices[i + 1] - minY) / height;
            vertices[i + 2] = (vertices[i + 2] - centerZ) / height;
        }
    }
}
