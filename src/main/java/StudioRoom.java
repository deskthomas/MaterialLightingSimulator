import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

final class StudioRoom implements AutoCloseable {
    private static final float CEILING_Y = 8.0f;
    private static final float SOCKET_RED = 0.82f;
    private static final float SOCKET_GREEN = 0.84f;
    private static final float SOCKET_BLUE = 0.87f;

    private record Part(Matrix4f model, float red, float green, float blue) {}
    private record Bulb(Matrix4f glass, Matrix4f socket, Matrix4f supports, Matrix4f filament,
                        StudioLight light) {}

    private final List<Part> parts = new ArrayList<>();
    private final List<Bulb> bulbs = new ArrayList<>();
    private final List<Mesh> bulbMeshes = new ArrayList<>();
    private final Mesh bulbGlass;
    private final Mesh bulbSocket;
    private final Mesh bulbSupports;
    private final Mesh bulbFilament;
    private final BulbGlow glow;
    private final EnumMap<ScenePreset, StudioScenery> scenes = new EnumMap<>(ScenePreset.class);
    private StudioScenery scenery;

    StudioRoom() {
        for (StudioLight light : StudioLight.values()) {
            addHangingBulb(light);
        }

        try {
            bulbGlass = loadBulbPart("glass");
            bulbSocket = loadBulbPart("socket");
            bulbSupports = loadBulbPart("supports");
            bulbFilament = loadBulbPart("filament");
            glow = new BulbGlow();
        } catch (RuntimeException | Error failure) {
            bulbMeshes.forEach(Mesh::close);
            throw failure;
        }
    }

    void setScene(ScenePreset scene) {
        scenery = scenes.computeIfAbsent(scene, StudioScenery::new);
    }

    private Mesh loadBulbPart(String name) {
        Mesh mesh = ModelLoader.loadGlb("/models/bulb-" + name + ".glb");
        bulbMeshes.add(mesh);
        return mesh;
    }

    private void addHangingBulb(StudioLight light) {
        float x = light.x, y = light.y, z = light.z;
        float cordBottom = y + 0.27f;
        addBox(x, (CEILING_Y + cordBottom) / 2, z, 0.018f, CEILING_Y - cordBottom, 0.018f,
                0.20f, 0.22f, 0.25f);
        bulbs.add(new Bulb(
                new Matrix4f().translation(x, y - 0.18f, z).scale(0.36f),
                new Matrix4f().translation(x, y + 0.12f, z).scale(0.15f),
                new Matrix4f().translation(x, y + 0.004f, z).scale(0.137f),
                new Matrix4f().translation(x, y - 0.011f, z).scale(0.022f),
                light));
    }

    private void addBox(float x, float y, float z, float width, float height, float depth,
                        float red, float green, float blue) {
        parts.add(new Part(new Matrix4f().translation(x, y, z).scale(width, height, depth),
                red, green, blue));
    }

    void setLights(StudioLighting lighting, float targetHeight) {
        bulbMeshes.forEach(mesh -> mesh.setLights(lighting, targetHeight));
        scenery.setLights(lighting, targetHeight);
    }

    void setShadows(StudioShadows shadows, float opacity) {
        bulbMeshes.forEach(mesh -> mesh.setShadows(shadows, opacity));
        scenery.setShadows(shadows, opacity);
    }

    void drawSceneryDepth() {
        scenery.drawDepth();
    }

    void drawOpaque(Mesh box, Matrix4f viewProjection, StudioLighting lighting) {
        scenery.draw(viewProjection);
        for (Part part : parts) {
            box.draw(viewProjection, part.model(), part.red(), part.green(), part.blue());
        }
        for (Bulb bulb : bulbs) {
            bulbSupports.drawFixtureHardware(viewProjection, bulb.supports(), 0.46f, 0.43f, 0.36f);
            StudioLighting.Setting setting = lighting.get(bulb.light());
            if (setting.shining()) {
                float emission = (float) Math.sqrt(setting.intensityPercent() / 100.0f) * 1.35f;
                bulbFilament.draw(viewProjection, bulb.filament(), setting.red() * emission,
                        setting.green() * emission, setting.blue() * emission, true);
            } else {
                bulbFilament.drawFixtureHardware(viewProjection, bulb.filament(), 0.34f, 0.30f, 0.24f);
            }
        }
    }

    void drawSocketsForReflection(Matrix4f viewProjection) {
        for (Bulb bulb : bulbs) {
            bulbSocket.drawFixtureHardware(viewProjection, bulb.socket(), SOCKET_RED, SOCKET_GREEN, SOCKET_BLUE);
        }
    }

    void drawMetalSockets(Matrix4f viewProjection, Matrix4f view, Vector3f eye) {
        for (Bulb bulb : bulbs) {
            bulbSocket.drawMaterial(viewProjection, view, bulb.socket(), eye,
                    SOCKET_RED, SOCKET_GREEN, SOCKET_BLUE, MaterialLook.BULB_METAL, 0.28f);
        }
    }

    void drawGlass(Matrix4f viewProjection, Matrix4f view, Vector3f eye) {
        for (Bulb bulb : bulbs) {
            bulbGlass.drawMaterial(viewProjection, view, bulb.glass(), eye,
                    0.96f, 0.99f, 1.0f, MaterialLook.BULB_GLASS, 0.08f);
        }
    }

    void drawGlow(Matrix4f viewProjection, StudioLighting lighting) {
        glow.draw(viewProjection, lighting);
    }

    @Override
    public void close() {
        glow.close();
        scenes.values().forEach(StudioScenery::close);
        bulbMeshes.forEach(Mesh::close);
    }
}
