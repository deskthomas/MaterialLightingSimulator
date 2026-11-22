import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
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

    StudioRoom() {
        addBox(0, 3.25f, 0, 16, 9.5f, 16, 0.27f, 0.32f, 0.39f);
        addBox(0, -1.48f, 0, 15.98f, 0.04f, 15.98f, 0.31f, 0.34f, 0.37f);

        for (int offset = -6; offset <= 6; offset += 2) {
            addBox(offset, -1.457f, 0, 0.025f, 0.006f, 15.9f, 0.18f, 0.21f, 0.25f);
            addBox(0, -1.457f, offset, 15.9f, 0.006f, 0.025f, 0.18f, 0.21f, 0.25f);
        }

        for (int side : new int[] {-1, 1}) {
            addBox(0, -1.27f, side * 7.94f, 15.88f, 0.36f, 0.12f, 0.17f, 0.21f, 0.27f);
            addBox(side * 7.94f, -1.27f, 0, 0.12f, 0.36f, 15.88f, 0.17f, 0.21f, 0.27f);
            for (int panel = -6; panel <= 6; panel += 3) {
                addBox(panel, 1.3f, side * 7.92f, 2.8f, 4.5f, 0.12f, 0.35f, 0.40f, 0.47f);
                addBox(side * 7.92f, 1.3f, panel, 0.12f, 4.5f, 2.8f, 0.35f, 0.40f, 0.47f);
            }
        }

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

    void setLights(StudioLighting lighting) {
        bulbMeshes.forEach(mesh -> mesh.setLights(lighting));
    }

    void drawOpaque(Mesh box, Matrix4f viewProjection, StudioLighting lighting) {
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
        bulbMeshes.forEach(Mesh::close);
    }
}
