package com.chernobylzombies.zone;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.Map;

/**
 * Fallback "Global (Common)" spawn rules applied anywhere outside specific SpawnZones.
 * Disabled by default so no zombies spawn outside zones unless explicitly enabled by an admin.
 */
public class GlobalSpawnZone {

    public static final double DENSITY_RADIUS = 64.0D;

    private static final String TAG_ENABLED = "Enabled";
    private static final String TAG_MAX_ZOMBIES_PER_AREA = "MaxZombiesPerArea";
    private static final String TAG_SPAWN_RATE_MULTIPLIER = "SpawnRateMultiplier";
    private static final String TAG_TIME_CONDITION = "TimeCondition";
    private static final String TAG_IGNORE_LIGHT_LEVEL = "IgnoreLightLevel";
    private static final String TAG_SPAWN_INTERVAL_SECONDS = "SpawnIntervalSeconds";
    private static final String TAG_WEIGHTS = "Weights";

    private boolean enabled;
    private int maxZombiesPerArea;
    private float spawnRateMultiplier;
    private ZoneTimeCondition timeCondition;
    private boolean ignoreLightLevel;
    private int spawnIntervalSeconds;
    private Map<Integer, Integer> allowedModelWeights;

    public GlobalSpawnZone(
            boolean enabled,
            int maxZombiesPerArea,
            float spawnRateMultiplier,
            Map<Integer, Integer> allowedModelWeights
    ) {
        this(
                enabled,
                maxZombiesPerArea,
                spawnRateMultiplier,
                ZoneTimeCondition.ALWAYS,
                true,
                SpawnZone.DEFAULT_SPAWN_INTERVAL_SECONDS,
                allowedModelWeights
        );
    }

    public GlobalSpawnZone(
            boolean enabled,
            int maxZombiesPerArea,
            float spawnRateMultiplier,
            ZoneTimeCondition timeCondition,
            boolean ignoreLightLevel,
            int spawnIntervalSeconds,
            Map<Integer, Integer> allowedModelWeights
    ) {
        this.enabled = enabled;
        this.maxZombiesPerArea = Math.max(0, maxZombiesPerArea);
        this.spawnRateMultiplier = Math.max(0.0F, spawnRateMultiplier);
        this.timeCondition = timeCondition != null ? timeCondition : ZoneTimeCondition.ALWAYS;
        this.ignoreLightLevel = ignoreLightLevel;
        this.spawnIntervalSeconds = SpawnZone.clampInterval(spawnIntervalSeconds);
        this.allowedModelWeights = SpawnZone.normalizeWeights(allowedModelWeights);
    }

    public static GlobalSpawnZone defaults() {
        return new GlobalSpawnZone(
                false,
                15,
                1.0F,
                ZoneTimeCondition.ALWAYS,
                true,
                SpawnZone.DEFAULT_SPAWN_INTERVAL_SECONDS,
                SpawnZone.createDefaultWeights()
        );
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxZombiesPerArea() {
        return this.maxZombiesPerArea;
    }

    public void setMaxZombiesPerArea(int maxZombiesPerArea) {
        this.maxZombiesPerArea = Math.max(0, maxZombiesPerArea);
    }

    public float getSpawnRateMultiplier() {
        return this.spawnRateMultiplier;
    }

    public void setSpawnRateMultiplier(float spawnRateMultiplier) {
        this.spawnRateMultiplier = Math.max(0.0F, spawnRateMultiplier);
    }

    public ZoneTimeCondition getTimeCondition() {
        return this.timeCondition;
    }

    public void setTimeCondition(ZoneTimeCondition timeCondition) {
        this.timeCondition = timeCondition != null ? timeCondition : ZoneTimeCondition.ALWAYS;
    }

    public boolean isIgnoreLightLevel() {
        return this.ignoreLightLevel;
    }

    public void setIgnoreLightLevel(boolean ignoreLightLevel) {
        this.ignoreLightLevel = ignoreLightLevel;
    }

    public int getSpawnIntervalSeconds() {
        return this.spawnIntervalSeconds;
    }

    public void setSpawnIntervalSeconds(int spawnIntervalSeconds) {
        this.spawnIntervalSeconds = SpawnZone.clampInterval(spawnIntervalSeconds);
    }

    public Map<Integer, Integer> getAllowedModelWeights() {
        return Collections.unmodifiableMap(this.allowedModelWeights);
    }

    public void setAllowedModelWeights(Map<Integer, Integer> allowedModelWeights) {
        this.allowedModelWeights = SpawnZone.normalizeWeights(allowedModelWeights);
    }

    public int getModelWeight(int modelId) {
        return this.allowedModelWeights.getOrDefault(modelId, 0);
    }

    public int getTotalWeight() {
        return SpawnZone.computeTotalWeight(this.allowedModelWeights);
    }

    public int pickRandomModelId(RandomSource random) {
        return SpawnZone.pickWeightedModelId(this.allowedModelWeights, random);
    }

    public AABB getAreaBoundingBox(BlockPos center) {
        return new AABB(center).inflate(DENSITY_RADIUS);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TAG_ENABLED, this.enabled);
        tag.putInt(TAG_MAX_ZOMBIES_PER_AREA, this.maxZombiesPerArea);
        tag.putFloat(TAG_SPAWN_RATE_MULTIPLIER, this.spawnRateMultiplier);
        tag.putString(TAG_TIME_CONDITION, this.timeCondition.name());
        tag.putBoolean(TAG_IGNORE_LIGHT_LEVEL, this.ignoreLightLevel);
        tag.putInt(TAG_SPAWN_INTERVAL_SECONDS, this.spawnIntervalSeconds);
        tag.put(TAG_WEIGHTS, SpawnZone.writeWeightsToNbt(this.allowedModelWeights));
        return tag;
    }

    public static GlobalSpawnZone fromNbt(CompoundTag tag) {
        boolean enabled = tag.contains(TAG_ENABLED) && tag.getBoolean(TAG_ENABLED);
        int maxZombies = tag.contains(TAG_MAX_ZOMBIES_PER_AREA) ? tag.getInt(TAG_MAX_ZOMBIES_PER_AREA) : 15;
        float rate = tag.contains(TAG_SPAWN_RATE_MULTIPLIER) ? tag.getFloat(TAG_SPAWN_RATE_MULTIPLIER) : 1.0F;
        ZoneTimeCondition timeCondition = ZoneTimeCondition.fromName(tag.getString(TAG_TIME_CONDITION));
        boolean ignoreLight = !tag.contains(TAG_IGNORE_LIGHT_LEVEL) || tag.getBoolean(TAG_IGNORE_LIGHT_LEVEL);
        int interval = tag.contains(TAG_SPAWN_INTERVAL_SECONDS)
                ? tag.getInt(TAG_SPAWN_INTERVAL_SECONDS)
                : SpawnZone.DEFAULT_SPAWN_INTERVAL_SECONDS;
        Map<Integer, Integer> weights = tag.contains(TAG_WEIGHTS)
                ? SpawnZone.readWeightsFromNbt(tag.getCompound(TAG_WEIGHTS))
                : SpawnZone.createDefaultWeights();
        return new GlobalSpawnZone(enabled, maxZombies, rate, timeCondition, ignoreLight, interval, weights);
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeBoolean(this.enabled);
        buf.writeVarInt(this.maxZombiesPerArea);
        buf.writeFloat(this.spawnRateMultiplier);
        buf.writeEnum(this.timeCondition);
        buf.writeBoolean(this.ignoreLightLevel);
        buf.writeVarInt(this.spawnIntervalSeconds);
        SpawnZone.writeWeightsToNetwork(buf, this.allowedModelWeights);
    }

    public static GlobalSpawnZone fromNetwork(FriendlyByteBuf buf) {
        boolean enabled = buf.readBoolean();
        int maxZombies = buf.readVarInt();
        float rate = buf.readFloat();
        ZoneTimeCondition timeCondition = buf.readEnum(ZoneTimeCondition.class);
        boolean ignoreLight = buf.readBoolean();
        int interval = buf.readVarInt();
        Map<Integer, Integer> weights = SpawnZone.readWeightsFromNetwork(buf);
        return new GlobalSpawnZone(enabled, maxZombies, rate, timeCondition, ignoreLight, interval, weights);
    }
}
