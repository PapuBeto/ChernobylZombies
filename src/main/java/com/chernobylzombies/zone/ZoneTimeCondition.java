package com.chernobylzombies.zone;

import net.minecraft.world.level.Level;

/**
 * Time-of-day restriction for zombie spawning inside a SpawnZone or GlobalSpawnZone.
 */
public enum ZoneTimeCondition {
    ALWAYS("Siempre"),
    NIGHT_ONLY("Solo Noche"),
    DAY_ONLY("Solo Dia");

    private final String label;

    ZoneTimeCondition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return this.label;
    }

    public boolean matches(Level level) {
        if (this == ALWAYS || level == null) {
            return true;
        }
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        boolean isNight = dayTime >= 13000L;
        return this == NIGHT_ONLY ? isNight : !isNight;
    }

    public ZoneTimeCondition next() {
        ZoneTimeCondition[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static ZoneTimeCondition fromName(String name) {
        if (name == null || name.isBlank()) {
            return ALWAYS;
        }
        for (ZoneTimeCondition condition : values()) {
            if (condition.name().equalsIgnoreCase(name.trim())) {
                return condition;
            }
        }
        return ALWAYS;
    }
}
