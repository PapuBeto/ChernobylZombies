package com.chernobylzombies.data;

/**
 * Controls how Chernobyl Zombies interacts with vanilla/KubeJS spawns on the server.
 */
public enum SpawnerMode {
    INTERNAL_ZONES("Solo Zonas Internas"),
    HYBRID("Hibrido (Zonas + KubeJS)"),
    KUBEJS_ONLY("Solo KubeJS");

    private final String label;

    SpawnerMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return this.label;
    }

    public SpawnerMode next() {
        SpawnerMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static SpawnerMode fromName(String raw) {
        if (raw == null || raw.isBlank()) {
            return INTERNAL_ZONES;
        }
        for (SpawnerMode mode : values()) {
            if (mode.name().equalsIgnoreCase(raw.trim())) {
                return mode;
            }
        }
        return INTERNAL_ZONES;
    }
}
