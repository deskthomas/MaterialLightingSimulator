import org.lwjgl.assimp.AIScene;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.lwjgl.assimp.Assimp.*;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

// Run with LWJGL core and Assimp (including their natives) on the classpath.
final class ImportObjShapes {
    private static final Pattern TEXTURE_COORDINATE = Pattern.compile("(?<=/)-?\\d+(?=/)");
    private static final int IMPORT_FLAGS = aiProcess_Triangulate | aiProcess_JoinIdenticalVertices
            | aiProcess_GenSmoothNormals | aiProcess_SortByPType;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: ImportObjShapes <output-directory> <file.obj>...");
        }
        Path output = Files.createDirectories(Path.of(args[0]));
        for (int i = 1; i < args.length; i++) {
            Path source = Path.of(args[i]);
            String filename = source.getFileName().toString();
            if (!filename.endsWith(".obj")) {
                throw new IllegalArgumentException("Expected an OBJ file: " + source);
            }
            Path target = output.resolve(filename.substring(0, filename.length() - 4) + ".glb");
            importShape(source, target);
            System.out.println(target + " (" + Files.size(target) + " bytes)");
        }
    }

    private static void importShape(Path source, Path target) throws IOException {
        byte[] geometry = geometryOnly(source);
        ByteBuffer input = memAlloc(geometry.length);
        try {
            input.put(geometry).flip();
            AIScene scene = aiImportFileFromMemory(input, IMPORT_FLAGS, "obj");
            if (scene == null) {
                throw new IOException("Could not read " + source + ": " + aiGetErrorString());
            }
            try {
                if (aiExportScene(scene, "glb2", target.toString(), 0) != aiReturn_SUCCESS) {
                    throw new IOException("Could not write " + target + ": " + aiGetErrorString());
                }
            } finally {
                aiReleaseImport(scene);
            }
        } finally {
            memFree(input);
        }
    }

    private static byte[] geometryOnly(Path source) throws IOException {
        var shape = new StringBuilder();
        try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                String directive = line.split("\\s+", 2)[0];
                switch (directive) {
                    case "v", "vn", "s" -> shape.append(line).append('\n');
                    case "f" -> shape.append(TEXTURE_COORDINATE.matcher(line).replaceAll("")).append('\n');
                    default -> { }
                }
            }
        }
        return shape.toString().getBytes(StandardCharsets.UTF_8);
    }
}
