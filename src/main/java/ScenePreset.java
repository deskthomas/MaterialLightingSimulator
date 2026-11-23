import org.joml.Matrix4f;

enum ScenePreset {
    AUTO_WORKSHOP("Auto Workshop", "auto-workshop", 0.14f, 0.15f, 0.16f, 0.15f),
    POOL_LEVEL_1("Pool Level 1", "pool-level-1", 0.23f, 0.31f, 0.37f, 0.16f),
    TV_ROOM("TV Room", "tv-room", 0.23f, 0.22f, 0.20f, 0.22f);

    final String resource;
    final float backgroundRed;
    final float backgroundGreen;
    final float backgroundBlue;
    final float ambient;
    private final String label;

    ScenePreset(String label, String asset, float red, float green, float blue, float ambient) {
        this.label = label;
        resource = "/models/scenery/" + asset + ".glb";
        backgroundRed = red;
        backgroundGreen = green;
        backgroundBlue = blue;
        this.ambient = ambient;
    }

    Matrix4f placement() {
        if (this == AUTO_WORKSHOP) {
            return new Matrix4f().translation(0, -1.50f, 0)
                    .rotateY((float) (Math.PI / 2)).scale(2.65f).translate(2.20f, 0, 0);
        }
        if (this == TV_ROOM) {
            return new Matrix4f().translation(0, -1.50f, 0)
                    .scale(3.90f).translate(-0.73f, -0.10f, -0.10f);
        }
        // Place the display on the deck alongside the central pool.
        return new Matrix4f().translation(0, -1.50f, 0)
                .rotateY((float) (-Math.PI / 2)).scale(1.55f).translate(-23.3f, 0, -3.0f);
    }

    @Override
    public String toString() {
        return label;
    }
}
