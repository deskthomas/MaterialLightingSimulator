import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

final class DisplayObjects implements AutoCloseable {
    private record ObjectSpec(String asset, MaterialLook material, String name, float height,
                              float red, float green, float blue, float yaw, float roughness) {}

    private static final List<ObjectSpec> SPECS = List.of(
            new ObjectSpec("imported/knight_helmet", MaterialLook.POLISHED_STEEL, "Medieval helmet", 0.57f,
                    0.91f, 0.92f, 0.93f, 0, 0.075f),
            new ObjectSpec("imported/disco_mirror_ball", MaterialLook.METAL, "Disco ball", 0.30f, 0.64f, 0.69f, 0.75f, 0, 0.16f),
            new ObjectSpec("imported/old_goblet_01_SW29_AK", MaterialLook.POLISHED_BRASS, "Chalice", 0.40f, 0.84f, 0.61f, 0.31f, 0, 0.23f),
            new ObjectSpec("imported/toy_bear_with_gift", MaterialLook.FABRIC, "Toy bear with gift", 0.52f, 0.72f, 0.47f, 0.37f, 0, 0.82f),
            new ObjectSpec("imported/Leaning_Pillow", MaterialLook.FABRIC, "Pillow", 0.37f, 0.45f, 0.64f, 0.71f, 15, 0.76f),
            new ObjectSpec("imported/open_book_STY3BLO", MaterialLook.FABRIC, "Cloth-bound book", 0.075f, 0.64f, 0.34f, 0.42f, 15, 0.72f),
            new ObjectSpec("imported/chessPieceKnight", MaterialLook.IVORY_WOOD, "Knight chess piece", 0.20f,
                    0.88f, 0.85f, 0.77f, 0, 0.42f),
            new ObjectSpec("imported/blue_green_vase_BKNM133330E", MaterialLook.WOOD, "Wooden vase", 0.44f, 0.68f, 0.44f, 0.25f, 0, 0.38f),
            new ObjectSpec("imported/tugboat_wood", MaterialLook.WOOD, "Wooden tugboat", 0.28f, 0.49f, 0.30f, 0.18f, 95, 0.72f),
            new ObjectSpec("imported/b_pint_glass_079", MaterialLook.GLASS, "Cup", 0.25f, 0.54f, 0.80f, 0.78f, 0, 0.15f),
            new ObjectSpec("glass-figurine", MaterialLook.GLASS, "Bird figurine", 0.30f, 0.54f, 0.70f, 0.91f, -25, 0.18f),
            new ObjectSpec("imported/goldfish_bowl_1464", MaterialLook.GLASS, "Fish bowl", 0.42f, 0.47f, 0.78f, 0.86f, 0, 0.12f)
    );

    static final int DEFAULT_INDEX = 2;
    static final int COUNT = SPECS.size();

    private final List<Mesh> meshes = new ArrayList<>();
    private final List<Pose> poses = new ArrayList<>();

    DisplayObjects(float floorHeight) {
        try {
            for (ObjectSpec spec : SPECS) {
                float[] geometry = ModelLoader.loadGeometry("/models/" + spec.asset() + ".glb");
                meshes.add(Mesh.withNormals(geometry));
                poses.add(new Pose(geometry, floorHeight, spec));
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    static String name(int index) {
        ObjectSpec spec = SPECS.get(index);
        return spec.material().label + " / " + spec.name();
    }

    static String material(int index) {
        return SPECS.get(index).material().label;
    }

    static String objectName(int index) {
        return SPECS.get(index).name();
    }

    void setLights(StudioLighting lighting, float targetHeight) {
        meshes.forEach(mesh -> mesh.setLights(lighting, targetHeight));
    }

    void setShadows(StudioShadows shadows, float opacity) {
        meshes.forEach(mesh -> mesh.setShadows(shadows, opacity));
    }

    void captureShadow(int index, StudioShadows shadows) {
        shadows.capture(meshes.get(index), poses.get(index).model);
    }

    void rotate(int index, float yaw, float tilt, Matrix4f view) {
        poses.get(index).rotate(yaw, tilt, view);
    }

    static boolean isGlass(int index) {
        return SPECS.get(index).material() == MaterialLook.GLASS;
    }

    void draw(int index, Matrix4f viewProjection, Matrix4f view, Vector3f eye) {
        ObjectSpec spec = SPECS.get(index);
        meshes.get(index).drawMaterial(viewProjection, view, poses.get(index).model, eye,
                spec.red(), spec.green(), spec.blue(), spec.material(), spec.roughness());
    }

    @Override
    public void close() {
        meshes.forEach(Mesh::close);
    }

    private static final class Pose {
        private final float[] points;
        private final float floorHeight;
        private final float size;
        private final Quaternionf orientation = new Quaternionf();
        private final Quaternionf step = new Quaternionf();
        private final Matrix4f model = new Matrix4f();

        Pose(float[] geometry, float floorHeight, ObjectSpec spec) {
            this.floorHeight = floorHeight;
            this.size = spec.height();
            orientation.rotationY((float) Math.toRadians(spec.yaw()));
            points = new float[geometry.length / 2];
            for (int source = 0, point = 0; source < geometry.length; source += 6, point += 3) {
                points[point] = geometry[source];
                points[point + 1] = geometry[source + 1] - 0.5f;
                points[point + 2] = geometry[source + 2];
            }
            updateModel();
        }

        void rotate(float yaw, float tilt, Matrix4f view) {
            step.rotationY(yaw).mul(orientation, orientation);
            // Tilt around the screen's horizontal axis, regardless of which way the object is facing.
            step.rotationAxis(tilt, view.m00(), 0, view.m20()).mul(orientation, orientation).normalize();
            updateModel();
        }

        private void updateModel() {
            model.rotation(orientation);
            float x = model.m01(), y = model.m11(), z = model.m21();
            float lowest = Float.POSITIVE_INFINITY;
            for (int i = 0; i < points.length; i += 3) {
                lowest = Math.min(lowest, x * points[i] + y * points[i + 1] + z * points[i + 2]);
            }
            // Bring the lowest rotated vertex back into contact with the floor.
            model.translation(0, floorHeight - lowest * size, 0)
                    .rotate(orientation).scale(size).translate(0, -0.5f, 0);
        }
    }
}
