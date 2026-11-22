enum MaterialLook {
    METAL("Metal", 1),
    FABRIC("Fabric", 2),
    WOOD("Wood", 3),
    GLASS("Glass", 4),
    BULB_GLASS("Bulb glass", 5),
    BULB_METAL("Bulb metal", 7),
    POLISHED_BRASS("Metal", 8),
    POLISHED_STEEL("Metal", 9),
    IVORY_WOOD("Wood", 10);

    final String label;
    final int shaderId;

    MaterialLook(String label, int shaderId) {
        this.label = label;
        this.shaderId = shaderId;
    }
}
