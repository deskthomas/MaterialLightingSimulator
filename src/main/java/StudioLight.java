enum StudioLight {
    OVERHEAD("Overhead", 0, 2.65f, 0, 9.0f),
    LEFT("Left", -2.55f, 0.95f, -0.45f, 5.0f),
    RIGHT("Right", 2.55f, 0.95f, -0.45f, 5.0f),
    REAR("Rear", 0, 1.45f, -3.1f, 6.0f);

    final String label;
    final float x;
    final float y;
    final float z;
    final float intensity;

    StudioLight(String label, float x, float y, float z, float intensity) {
        this.label = label;
        this.x = x;
        this.y = y;
        this.z = z;
        this.intensity = intensity;
    }
}
