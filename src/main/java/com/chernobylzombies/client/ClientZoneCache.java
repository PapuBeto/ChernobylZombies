package com.chernobylzombies.client;

import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieModelStats;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-only cache of synchronized SpawnZones, SpawnerMode, live zombie counts, GlobalSpawnZone, and model stats.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientZoneCache {

    private static List<SpawnZone> zones = List.of();
    private static GlobalSpawnZone globalZone = GlobalSpawnZone.defaults();
    private static Map<Integer, ZombieModelStats> modelStats = Map.of();
    private static Map<UUID, Integer> zoneLiveCounts = Map.of();
    private static SpawnerMode spawnerMode = SpawnerMode.INTERNAL_ZONES;

    private ClientZoneCache() {
    }

    public static void update(
            List<SpawnZone> newZones,
            GlobalSpawnZone newGlobalZone,
            Map<Integer, ZombieModelStats> newModelStats,
            Map<UUID, Integer> newZoneLiveCounts,
            SpawnerMode newSpawnerMode
    ) {
        zones = newZones != null ? List.copyOf(new ArrayList<>(newZones)) : List.of();
        globalZone = newGlobalZone != null ? newGlobalZone : GlobalSpawnZone.defaults();
        modelStats = newModelStats != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(newModelStats))
                : Map.of();
        zoneLiveCounts = newZoneLiveCounts != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(newZoneLiveCounts))
                : Map.of();
        spawnerMode = newSpawnerMode != null ? newSpawnerMode : SpawnerMode.INTERNAL_ZONES;
    }

    public static void clear() {
        zones = List.of();
        globalZone = GlobalSpawnZone.defaults();
        modelStats = Map.of();
        zoneLiveCounts = Map.of();
        spawnerMode = SpawnerMode.INTERNAL_ZONES;
    }

    public static List<SpawnZone> getZones() {
        return zones;
    }

    public static GlobalSpawnZone getGlobalZone() {
        return globalZone;
    }

    public static Map<Integer, ZombieModelStats> getAllModelStats() {
        return modelStats;
    }

    public static ZombieModelStats getModelStats(int modelId) {
        return modelStats.getOrDefault(modelId, ZombieModelStats.defaults());
    }

    public static int getZoneLiveCount(UUID zoneId) {
        return zoneLiveCounts.getOrDefault(zoneId, 0);
    }

    public static SpawnerMode getSpawnerMode() {
        return spawnerMode;
    }
}
