package com.chernobylzombies.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Configurable base attributes and behavior flags for a single custom zombie model variant (MODEL_ID 0-9).
 */
public record ZombieModelStats(
        double maxHealth,
        double movementSpeed,
        double attackDamage,
        double followRange,
        double knockbackResistance,
        float scale,
        boolean immuneToSunlight,
        boolean immuneToFallDamage,
        boolean persistent,
        int xpReward,
        String customLootTable,
        String customDisplayName,
        boolean showBossBar
) {

    public static final double DEFAULT_MAX_HEALTH = 20.0D;
    public static final double DEFAULT_MOVEMENT_SPEED = 0.23D;
    public static final double DEFAULT_ATTACK_DAMAGE = 3.0D;
    public static final double DEFAULT_FOLLOW_RANGE = 35.0D;
    public static final double DEFAULT_KNOCKBACK_RESISTANCE = 0.0D;
    public static final float DEFAULT_SCALE = 1.0F;
    public static final boolean DEFAULT_IMMUNE_TO_SUNLIGHT = true;
    public static final boolean DEFAULT_IMMUNE_TO_FALL_DAMAGE = false;
    public static final boolean DEFAULT_PERSISTENT = false;
    public static final int DEFAULT_XP_REWARD = 5;
    public static final String DEFAULT_CUSTOM_LOOT_TABLE = "";
    public static final String DEFAULT_CUSTOM_DISPLAY_NAME = "";
    public static final boolean DEFAULT_SHOW_BOSS_BAR = false;

    private static final String TAG_MAX_HEALTH = "MaxHealth";
    private static final String TAG_MOVEMENT_SPEED = "MovementSpeed";
    private static final String TAG_ATTACK_DAMAGE = "AttackDamage";
    private static final String TAG_FOLLOW_RANGE = "FollowRange";
    private static final String TAG_KNOCKBACK_RESISTANCE = "KnockbackResistance";
    private static final String TAG_SCALE = "Scale";
    private static final String TAG_IMMUNE_TO_SUNLIGHT = "ImmuneToSunlight";
    private static final String TAG_IMMUNE_TO_FALL_DAMAGE = "ImmuneToFallDamage";
    private static final String TAG_PERSISTENT = "Persistent";
    private static final String TAG_XP_REWARD = "XpReward";
    private static final String TAG_CUSTOM_LOOT_TABLE = "CustomLootTable";
    private static final String TAG_CUSTOM_DISPLAY_NAME = "CustomDisplayName";
    private static final String TAG_SHOW_BOSS_BAR = "ShowBossBar";

    public ZombieModelStats {
        maxHealth = clampRange(maxHealth, 1.0D, 2048.0D, DEFAULT_MAX_HEALTH);
        movementSpeed = clampRange(movementSpeed, 0.01D, 2.0D, DEFAULT_MOVEMENT_SPEED);
        attackDamage = clampRange(attackDamage, 0.0D, 500.0D, DEFAULT_ATTACK_DAMAGE);
        followRange = clampRange(followRange, 5.0D, 80.0D, DEFAULT_FOLLOW_RANGE);
        knockbackResistance = clampRange(knockbackResistance, 0.0D, 1.0D, DEFAULT_KNOCKBACK_RESISTANCE);
        scale = (float) clampRange(scale, 0.6D, 2.0D, DEFAULT_SCALE);
        xpReward = Math.max(0, Math.min(10000, xpReward));
        customLootTable = customLootTable != null ? customLootTable.trim() : "";
        customDisplayName = customDisplayName != null ? customDisplayName.trim() : "";
    }

    private static double clampRange(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static ZombieModelStats defaults() {
        return new ZombieModelStats(
                DEFAULT_MAX_HEALTH,
                DEFAULT_MOVEMENT_SPEED,
                DEFAULT_ATTACK_DAMAGE,
                DEFAULT_FOLLOW_RANGE,
                DEFAULT_KNOCKBACK_RESISTANCE,
                DEFAULT_SCALE,
                DEFAULT_IMMUNE_TO_SUNLIGHT,
                DEFAULT_IMMUNE_TO_FALL_DAMAGE,
                DEFAULT_PERSISTENT,
                DEFAULT_XP_REWARD,
                DEFAULT_CUSTOM_LOOT_TABLE,
                DEFAULT_CUSTOM_DISPLAY_NAME,
                DEFAULT_SHOW_BOSS_BAR
        );
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble(TAG_MAX_HEALTH, this.maxHealth);
        tag.putDouble(TAG_MOVEMENT_SPEED, this.movementSpeed);
        tag.putDouble(TAG_ATTACK_DAMAGE, this.attackDamage);
        tag.putDouble(TAG_FOLLOW_RANGE, this.followRange);
        tag.putDouble(TAG_KNOCKBACK_RESISTANCE, this.knockbackResistance);
        tag.putFloat(TAG_SCALE, this.scale);
        tag.putBoolean(TAG_IMMUNE_TO_SUNLIGHT, this.immuneToSunlight);
        tag.putBoolean(TAG_IMMUNE_TO_FALL_DAMAGE, this.immuneToFallDamage);
        tag.putBoolean(TAG_PERSISTENT, this.persistent);
        tag.putInt(TAG_XP_REWARD, this.xpReward);
        tag.putString(TAG_CUSTOM_LOOT_TABLE, this.customLootTable);
        tag.putString(TAG_CUSTOM_DISPLAY_NAME, this.customDisplayName);
        tag.putBoolean(TAG_SHOW_BOSS_BAR, this.showBossBar);
        return tag;
    }

    public static ZombieModelStats fromNbt(CompoundTag tag) {
        double health = tag.contains(TAG_MAX_HEALTH) ? tag.getDouble(TAG_MAX_HEALTH) : DEFAULT_MAX_HEALTH;
        double speed = tag.contains(TAG_MOVEMENT_SPEED) ? tag.getDouble(TAG_MOVEMENT_SPEED) : DEFAULT_MOVEMENT_SPEED;
        double damage = tag.contains(TAG_ATTACK_DAMAGE) ? tag.getDouble(TAG_ATTACK_DAMAGE) : DEFAULT_ATTACK_DAMAGE;
        double follow = tag.contains(TAG_FOLLOW_RANGE) ? tag.getDouble(TAG_FOLLOW_RANGE) : DEFAULT_FOLLOW_RANGE;
        double kbRes = tag.contains(TAG_KNOCKBACK_RESISTANCE) ? tag.getDouble(TAG_KNOCKBACK_RESISTANCE) : DEFAULT_KNOCKBACK_RESISTANCE;
        float scale = tag.contains(TAG_SCALE) ? tag.getFloat(TAG_SCALE) : DEFAULT_SCALE;
        boolean sunImmune = !tag.contains(TAG_IMMUNE_TO_SUNLIGHT) || tag.getBoolean(TAG_IMMUNE_TO_SUNLIGHT);
        boolean fallImmune = tag.contains(TAG_IMMUNE_TO_FALL_DAMAGE) && tag.getBoolean(TAG_IMMUNE_TO_FALL_DAMAGE);
        boolean persistent = tag.contains(TAG_PERSISTENT) && tag.getBoolean(TAG_PERSISTENT);
        int xp = tag.contains(TAG_XP_REWARD) ? tag.getInt(TAG_XP_REWARD) : DEFAULT_XP_REWARD;
        String loot = tag.contains(TAG_CUSTOM_LOOT_TABLE) ? tag.getString(TAG_CUSTOM_LOOT_TABLE) : DEFAULT_CUSTOM_LOOT_TABLE;
        String name = tag.contains(TAG_CUSTOM_DISPLAY_NAME) ? tag.getString(TAG_CUSTOM_DISPLAY_NAME) : DEFAULT_CUSTOM_DISPLAY_NAME;
        boolean bossBar = tag.contains(TAG_SHOW_BOSS_BAR) && tag.getBoolean(TAG_SHOW_BOSS_BAR);
        return new ZombieModelStats(health, speed, damage, follow, kbRes, scale, sunImmune, fallImmune, persistent, xp, loot, name, bossBar);
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeDouble(this.maxHealth);
        buf.writeDouble(this.movementSpeed);
        buf.writeDouble(this.attackDamage);
        buf.writeDouble(this.followRange);
        buf.writeDouble(this.knockbackResistance);
        buf.writeFloat(this.scale);
        buf.writeBoolean(this.immuneToSunlight);
        buf.writeBoolean(this.immuneToFallDamage);
        buf.writeBoolean(this.persistent);
        buf.writeVarInt(this.xpReward);
        buf.writeUtf(this.customLootTable, 256);
        buf.writeUtf(this.customDisplayName, 128);
        buf.writeBoolean(this.showBossBar);
    }

    public static ZombieModelStats fromNetwork(FriendlyByteBuf buf) {
        return new ZombieModelStats(
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readUtf(256),
                buf.readUtf(128),
                buf.readBoolean()
        );
    }
}
