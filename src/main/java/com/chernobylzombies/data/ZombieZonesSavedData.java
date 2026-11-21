package com.chernobylzombies.data;

import com.chernobylzombies.registry.ZombieVariantRegistry;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * World-persisted storage for polygonal SpawnZones, GlobalSpawnZone, SpawnerMode, and per-model stats.
 */
public class ZombieZonesSavedData extends SavedData {

    public static final String DATA_NAME = "chernobylzombies_zones_data";

    private static final String TAG_ZONES = "Zones";
    private static final String TAG_GLOBAL_ZONE = "GlobalZone";
    private static final String TAG_MODEL_STATS = "ModelStats";
    private static final String TAG_SPAWNER_MODE = "SpawnerMode";

    private static volatile SpawnerMode activeSpawnerMode = SpawnerMode.INTERNAL_ZONES;

    private final Map<UUID, SpawnZone> zones = new LinkedHashMap<>();
    private GlobalSpawnZone globalZone = GlobalSpawnZone.defaults();
    private SpawnerMode spawnerMode = SpawnerMode.INTERNAL_ZONES;
    private final Map<Integer, ZombieModelStats> modelStats = new LinkedHashMap<>();

    public ZombieZonesSavedData() {
        ensureDefaultStats();
    }

    public static SpawnerMode getActiveSpawnerMode() {
        return activeSpawnerMode;
    }

    public static void setActiveSpawnerMode(SpawnerMode mode) {
        activeSpawnerMode = mode != null ? mode : SpawnerMode.INTERNAL_ZONES;
    }

    public static ZombieZonesSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        ZombieZonesSavedData data = overworld.getDataStorage().computeIfAbsent(
                ZombieZonesSavedData::load,
                ZombieZonesSavedData::new,
                DATA_NAME
        );
        activeSpawnerMode = data.spawnerMode;
        return data;
    }

    private void ensureDefaultStats() {
        int total = ZombieVariantRegistry.getTotalVariants();
        for (int i = 0; i < total; i++) {
            this.modelStats.putIfAbsent(i, ZombieModelStats.defaults());
        }
    }

    public SpawnerMode getSpawnerMode() {
        return this.spawnerMode;
    }

    public void setSpawnerMode(SpawnerMode spawnerMode) {
        this.spawnerMode = spawnerMode != null ? spawnerMode : SpawnerMode.INTERNAL_ZONES;
        activeSpawnerMode = this.spawnerMode;
        setDirty();
    }

    public Collection<SpawnZone> getZones() {
        return Collections.unmodifiableCollection(this.zones.values());
    }

    public Optional<SpawnZone> getZone(UUID id) {
        return Optional.ofNullable(this.zones.get(id));
    }

    public Optional<SpawnZone> findZoneByNameOrId(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String trimmed = query.trim();
        try {
            UUID uuid = UUID.fromString(trimmed);
            SpawnZone byId = this.zones.get(uuid);
            if (byId != null) {
                return Optional.of(byId);
            }
        } catch (IllegalArgumentException ignored) {
        }
        for (SpawnZone zone : this.zones.values()) {
            if (zone.getName().equalsIgnoreCase(trimmed)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public void putZone(SpawnZone zone) {
        if (zone == null) {
            return;
        }
        this.zones.put(zone.getId(), zone);
        setDirty();
    }

    public boolean removeZone(UUID id) {
        if (id == null) {
            return false;
        }
        boolean removed = this.zones.remove(id) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public Optional<SpawnZone> getActiveZoneAt(ResourceKey<Level> dimension, BlockPos pos) {
        for (SpawnZone zone : this.zones.values()) {
            if (zone.isEnabled() && zone.contains(dimension, pos)) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public GlobalSpawnZone getGlobalZone() {
        return this.globalZone;
    }

    public void setGlobalZone(GlobalSpawnZone globalZone) {
        if (globalZone == null) {
            return;
        }
        this.globalZone = globalZone;
        setDirty();
    }

    public ZombieModelStats getModelStats(int modelId) {
        return this.modelStats.getOrDefault(modelId, ZombieModelStats.defaults());
    }

    public Map<Integer, ZombieModelStats> getAllModelStats() {
        return Collections.unmodifiableMap(this.modelStats);
    }

    public void setModelStats(int modelId, ZombieModelStats stats) {
        int total = ZombieVariantRegistry.getTotalVariants();
        if (modelId < 0 || modelId >= total || stats == null) {
            return;
        }
        this.modelStats.put(modelId, stats);
        setDirty();
    }

    public void setAllModelStats(Map<Integer, ZombieModelStats> updatedStats) {
        if (updatedStats == null || updatedStats.isEmpty()) {
            return;
        }
        int total = ZombieVariantRegistry.getTotalVariants();
        boolean changed = false;
        for (Map.Entry<Integer, ZombieModelStats> entry : updatedStats.entrySet()) {
            int modelId = entry.getKey();
            ZombieModelStats stats = entry.getValue();
            if (modelId >= 0 && modelId < total && stats != null) {
                this.modelStats.put(modelId, stats);
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    public void replaceAllFromConfig(
            SpawnerMode mode,
            List<SpawnZone> newZones,
            GlobalSpawnZone newGlobal,
            Map<Integer, ZombieModelStats> newStats
    ) {
        if (mode != null) {
            this.spawnerMode = mode;
            activeSpawnerMode = mode;
        }
        if (newZones != null) {
            this.zones.clear();
            for (SpawnZone z : newZones) {
                this.zones.put(z.getId(), z);
            }
        }
        if (newGlobal != null) {
            this.globalZone = newGlobal;
        }
        if (newStats != null && !newStats.isEmpty()) {
            setAllModelStats(newStats);
        }
        ensureDefaultStats();
        setDirty();
    }

    public static ZombieZonesSavedData load(CompoundTag tag) {
        ZombieZonesSavedData data = new ZombieZonesSavedData();

        if (tag.contains(TAG_SPAWNER_MODE)) {
            data.spawnerMode = SpawnerMode.fromName(tag.getString(TAG_SPAWNER_MODE));
        }
        activeSpawnerMode = data.spawnerMode;

        ListTag zonesList = tag.getList(TAG_ZONES, Tag.TAG_COMPOUND);
        for (int i = 0; i < zonesList.size(); i++) {
            SpawnZone zone = SpawnZone.fromNbt(zonesList.getCompound(i));
            data.zones.put(zone.getId(), zone);
        }

        if (tag.contains(TAG_GLOBAL_ZONE, Tag.TAG_COMPOUND)) {
            data.globalZone = GlobalSpawnZone.fromNbt(tag.getCompound(TAG_GLOBAL_ZONE));
        }

        CompoundTag statsTag = tag.getCompound(TAG_MODEL_STATS);
        int total = ZombieVariantRegistry.getTotalVariants();
        for (String key : statsTag.getAllKeys()) {
            try {
                int modelId = Integer.parseInt(key);
                if (modelId >= 0 && modelId < total) {
                    data.modelStats.put(modelId, ZombieModelStats.fromNbt(statsTag.getCompound(key)));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        data.ensureDefaultStats();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString(TAG_SPAWNER_MODE, this.spawnerMode.name());

        ListTag zonesList = new ListTag();
        for (SpawnZone zone : this.zones.values()) {
            zonesList.add(zone.toNbt());
        }
        tag.put(TAG_ZONES, zonesList);
        tag.put(TAG_GLOBAL_ZONE, this.globalZone.toNbt());

        CompoundTag statsTag = new CompoundTag();
        for (Map.Entry<Integer, ZombieModelStats> entry : this.modelStats.entrySet()) {
            statsTag.put(String.valueOf(entry.getKey()), entry.getValue().toNbt());
        }
        tag.put(TAG_MODEL_STATS, statsTag);
        return tag;
    }
}
