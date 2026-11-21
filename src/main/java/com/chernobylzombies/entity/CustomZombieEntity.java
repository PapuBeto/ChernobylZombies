package com.chernobylzombies.entity;

import com.chernobylzombies.data.ZombieModelStats;
import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.registry.ModEntities;
import com.chernobylzombies.registry.ZombieVariantRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Multi-model GeckoLib zombie entity with dedicated-server TPS/RAM optimizations:
 * - Idle AI/target search throttling (every 10 ticks when no target and no player within 32 blocks)
 * - Long-range (>16 blocks) horde pathfinding recompute throttling (every 10 ticks)
 * - Crowded-group collision suppression (>6 zombies within 2 blocks)
 * - Auto-discard of distant (>48 blocks) idle non-persistent zombies after >45s
 */
public class CustomZombieEntity extends Zombie implements GeoEntity {

    private static final double BOSS_BAR_RANGE_SQ = 25.0D * 25.0D;
    private static final double IDLE_AI_ACTIVE_RANGE_SQ = 32.0D * 32.0D;
    private static final double LONG_RANGE_PATH_SQ = 16.0D * 16.0D;
    private static final double CROWD_RADIUS_SQ = 2.0D * 2.0D;
    private static final int CROWD_ZOMBIE_LIMIT = 6;
    private static final long CROWD_CACHE_TICKS = 5L;
    private static final long IDLE_AI_INTERVAL_TICKS = 10L;
    private static final long LONG_RANGE_PATH_INTERVAL_TICKS = 10L;
    private static final int ORPHAN_CHECK_INTERVAL_TICKS = 100;
    private static final double ORPHAN_PLAYER_RANGE_SQ = 48.0D * 48.0D;
    private static final int ORPHAN_DESPAWN_THRESHOLD_TICKS = 900;

    public static final String VOICE_MALE = "MALE";
    public static final String VOICE_FEMALE = "FEMALE";
    public static final String VOICE_MUTANT = "MUTANT";
    public static final String VOICE_VANILLA = "VANILLA";

    private static final EntityDataAccessor<Integer> MODEL_ID =
            SynchedEntityData.defineId(CustomZombieEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TEXTURE_ID =
            SynchedEntityData.defineId(CustomZombieEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> MODEL_SCALE =
            SynchedEntityData.defineId(CustomZombieEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> IMMUNE_TO_SUNLIGHT =
            SynchedEntityData.defineId(CustomZombieEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<String> VOICE_PROFILE =
            SynchedEntityData.defineId(CustomZombieEntity.class, EntityDataSerializers.STRING);

    public static final String TAG_MODEL_ID = "ModelId";
    public static final String TAG_TEXTURE_ID = "TextureId";
    public static final String TAG_VOICE_PROFILE = "VoiceProfile";
    public static final String TAG_SKIP_ZONE_CHECK = "SkipZoneCheck";
    public static final String TAG_CUSTOM_STATS = "CustomStats";
    private static final String TAG_VARIANT_INITIALIZED = "VariantInitialized";
    private static final String TAG_CONFIGURED_CUSTOM_NAME = "ConfiguredCustomName";

    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("animation.zombie_civil.idle");
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop("animation.zombie_civil.walk");
    private static final RawAnimation ANIM_RUN = RawAnimation.begin().thenLoop("animation.zombie_civil.run");
    private static final RawAnimation ANIM_ATTACK = RawAnimation.begin().thenPlay("animation.zombie_civil.attack");
    private static final RawAnimation ANIM_RUN_ATTACK = RawAnimation.begin().thenPlay("animation.zombie_civil.run_attack");
    private static final RawAnimation ANIM_HURT = RawAnimation.begin().thenPlay("animation.zombie_civil.hurt");
    private static final RawAnimation ANIM_DEATH = RawAnimation.begin().thenPlayAndHold("animation.zombie_civil.death");

    private final AnimatableInstanceCache animCache = GeckoLibUtil.createInstanceCache(this);
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.literal("Zombie"),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS
    );

    private final int fixedModelId;
    private boolean variantInitialized = false;
    private boolean explicitModelFromNbt = false;
    private boolean explicitTextureFromNbt = false;
    private boolean explicitVoiceFromNbt = false;
    private boolean approvedZoneSpawn = false;
    private boolean skipZoneCheck = false;
    private boolean customStats = false;
    private boolean immuneToFallDamage = false;
    private boolean persistentModel = false;
    private int configuredXpReward = ZombieModelStats.DEFAULT_XP_REWARD;
    private String customLootTable = "";
    private boolean showBossBar = false;
    private boolean hasConfiguredCustomName = false;

    // Performance & AI throttling state (server-side)
    private boolean idleAiThrottled = false;
    private boolean idleAiPulseOpen = true;
    private long nextIdleAiPulseGameTime = -1L;
    private long idleAiPulseEndGameTime = -1L;
    private int combatWakeTicks = 0;
    private int idleNoPlayerTicks = 0;
    private boolean cachedCrowdedGroup = false;
    private long lastCrowdCheckGameTime = -100L;

    public CustomZombieEntity(EntityType<? extends Zombie> entityType, Level level) {
        this(entityType, level, ModEntities.getFixedModelId(entityType));
    }

    public CustomZombieEntity(EntityType<? extends Zombie> entityType, Level level, int fixedModelId) {
        super(entityType, level);
        this.fixedModelId = fixedModelId >= 0 ? clampModelId(fixedModelId) : -1;
        this.bossEvent.setVisible(false);
        if (this.fixedModelId >= 0) {
            this.entityData.set(MODEL_ID, this.fixedModelId);
            if (!level.isClientSide()) {
                int texCount = ZombieVariantRegistry.getTextureCount(this.fixedModelId);
                if (texCount > 1) {
                    this.setTextureId(this.random.nextInt(texCount));
                }
            }
            this.entityData.set(VOICE_PROFILE, defaultVoiceForModel(this.fixedModelId));
            this.variantInitialized = true;
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        int initialModel = ModEntities.getFixedModelId(this.getType());
        int resolvedModel = initialModel >= 0 ? clampModelId(initialModel) : 0;
        this.entityData.define(MODEL_ID, resolvedModel);
        this.entityData.define(TEXTURE_ID, 0);
        this.entityData.define(MODEL_SCALE, ZombieModelStats.DEFAULT_SCALE);
        this.entityData.define(IMMUNE_TO_SUNLIGHT, ZombieModelStats.DEFAULT_IMMUNE_TO_SUNLIGHT);
        this.entityData.define(VOICE_PROFILE, defaultVoiceForModel(resolvedModel));
    }

    public boolean hasFixedEntityModel() {
        return this.fixedModelId >= 0;
    }

    public int getModelId() {
        if (this.fixedModelId >= 0) {
            return this.fixedModelId;
        }
        return this.entityData.get(MODEL_ID);
    }

    public void setModelId(int modelId) {
        int clampedModel = this.fixedModelId >= 0 ? this.fixedModelId : clampModelId(modelId);
        this.entityData.set(MODEL_ID, clampedModel);
        this.setTextureId(this.getTextureId());
        if (!this.explicitVoiceFromNbt) {
            this.entityData.set(VOICE_PROFILE, defaultVoiceForModel(clampedModel));
        }
        if (!this.level().isClientSide()) {
            this.applyModelStats(true);
        }
    }

    public int getTextureId() {
        return this.entityData.get(TEXTURE_ID);
    }

    public void setTextureId(int textureId) {
        int count = ZombieVariantRegistry.getTextureCount(this.getModelId());
        int clampedTexture = (textureId >= 0 && textureId < count) ? textureId : 0;
        this.entityData.set(TEXTURE_ID, clampedTexture);
    }

    /**
     * Public KubeJS/Rhino helper to set exact model and texture in one call without random overrides.
     */
    public void setVariant(int modelId, int textureId) {
        this.explicitModelFromNbt = true;
        this.explicitTextureFromNbt = true;
        assignExactVariant(modelId, textureId);
    }

    /**
     * Public KubeJS/Rhino helper to override the voice profile ("MALE", "FEMALE", "MUTANT", "VANILLA").
     */
    public void setVoice(String voice) {
        this.explicitVoiceFromNbt = true;
        this.entityData.set(VOICE_PROFILE, normalizeVoiceProfile(voice, this.getModelId()));
    }

    public String getVoice() {
        return this.entityData.get(VOICE_PROFILE);
    }

    public float getModelScale() {
        float scale = this.entityData.get(MODEL_SCALE);
        if (Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0.01F) {
            return ZombieModelStats.DEFAULT_SCALE;
        }
        return scale;
    }

    public void setModelScale(float scale) {
        float safeScale = (Float.isNaN(scale) || Float.isInfinite(scale) || scale <= 0.01F)
                ? ZombieModelStats.DEFAULT_SCALE
                : scale;
        float clamped = Math.max(0.6F, Math.min(2.0F, safeScale));
        this.entityData.set(MODEL_SCALE, clamped);
        this.refreshDimensions();
    }

    public boolean isVariantInitialized() {
        return this.variantInitialized;
    }

    public boolean hasExplicitModelFromNbt() {
        return this.explicitModelFromNbt;
    }

    public boolean isApprovedZoneSpawn() {
        return this.approvedZoneSpawn;
    }

    public void setApprovedZoneSpawn(boolean approvedZoneSpawn) {
        this.approvedZoneSpawn = approvedZoneSpawn;
    }

    public boolean isSkipZoneCheck() {
        return this.skipZoneCheck || this.getTags().contains(TAG_SKIP_ZONE_CHECK);
    }

    public void setSkipZoneCheck(boolean skipZoneCheck) {
        this.skipZoneCheck = skipZoneCheck;
    }

    public boolean hasCustomStats() {
        return this.customStats;
    }

    public void setCustomStats(boolean customStats) {
        this.customStats = customStats;
    }

    public void assignVariant(int modelId, RandomSource random) {
        int clampedModel = this.fixedModelId >= 0 ? this.fixedModelId : clampModelId(modelId);
        int textureCount = ZombieVariantRegistry.getTextureCount(clampedModel);
        int chosenTexture = textureCount > 1 ? random.nextInt(textureCount) : 0;
        assignExactVariant(clampedModel, chosenTexture);
    }

    public void assignExactVariant(int modelId, int textureId) {
        int clampedModel = this.fixedModelId >= 0 ? this.fixedModelId : clampModelId(modelId);
        this.entityData.set(MODEL_ID, clampedModel);
        this.setTextureId(textureId);
        if (!this.explicitVoiceFromNbt) {
            this.entityData.set(VOICE_PROFILE, defaultVoiceForModel(clampedModel));
        }
        this.variantInitialized = true;
        this.applyModelStats(true);
    }

    public void assignRandomVariant(RandomSource random) {
        int totalModels = ZombieVariantRegistry.getTotalVariants();
        int chosenModel = totalModels > 1 ? random.nextInt(totalModels) : 0;
        this.assignVariant(chosenModel, random);
    }

    /**
     * Applies configured stats and behavior flags unless CustomStats is enabled by KubeJS.
     */
    public void applyModelStats(boolean healToFull) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.customStats) {
            return;
        }

        ZombieModelStats stats = ZombieZonesSavedData.get(serverLevel).getModelStats(this.getModelId());

        setAttributeBase(Attributes.MAX_HEALTH, stats.maxHealth());
        setAttributeBase(Attributes.MOVEMENT_SPEED, stats.movementSpeed());
        setAttributeBase(Attributes.ATTACK_DAMAGE, stats.attackDamage());
        setAttributeBase(Attributes.FOLLOW_RANGE, stats.followRange());
        setAttributeBase(Attributes.KNOCKBACK_RESISTANCE, stats.knockbackResistance());

        setModelScale(stats.scale());
        this.entityData.set(IMMUNE_TO_SUNLIGHT, stats.immuneToSunlight());
        this.immuneToFallDamage = stats.immuneToFallDamage();
        this.persistentModel = stats.persistent();
        this.configuredXpReward = stats.xpReward();
        this.xpReward = stats.xpReward();
        this.customLootTable = stats.customLootTable();
        this.showBossBar = stats.showBossBar();

        if (!stats.customDisplayName().isBlank()) {
            this.setCustomName(Component.literal(stats.customDisplayName()));
            this.setCustomNameVisible(true);
            this.hasConfiguredCustomName = true;
        } else if (this.hasConfiguredCustomName) {
            this.setCustomName(null);
            this.setCustomNameVisible(false);
            this.hasConfiguredCustomName = false;
        }

        if (healToFull || this.getHealth() > this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }

        if (!this.showBossBar) {
            if (!this.bossEvent.getPlayers().isEmpty()) {
                this.bossEvent.removeAllPlayers();
            }
            this.bossEvent.setVisible(false);
        } else {
            updateBossBar();
        }
    }

    private void setAttributeBase(Attribute attribute, double value) {
        AttributeInstance instance = this.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static int clampModelId(int modelId) {
        int total = ZombieVariantRegistry.getTotalVariants();
        return (modelId >= 0 && modelId < total) ? modelId : 0;
    }

    private static String defaultVoiceForModel(int modelId) {
        return modelId >= 5 ? VOICE_FEMALE : VOICE_MALE;
    }

    private static String normalizeVoiceProfile(String voice, int modelId) {
        if (voice == null || voice.isBlank()) {
            return defaultVoiceForModel(modelId);
        }
        return switch (voice.trim().toUpperCase(Locale.ROOT)) {
            case VOICE_MALE -> VOICE_MALE;
            case VOICE_FEMALE -> VOICE_FEMALE;
            case VOICE_MUTANT -> VOICE_MUTANT;
            case VOICE_VANILLA -> VOICE_VANILLA;
            default -> defaultVoiceForModel(modelId);
        };
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (MODEL_SCALE.equals(key)) {
            this.refreshDimensions();
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(this.getModelScale());
    }

    @Override
    protected boolean isSunSensitive() {
        return !this.entityData.get(IMMUNE_TO_SUNLIGHT);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        if (this.immuneToFallDamage) {
            return false;
        }
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        if (this.persistentModel || this.isPersistenceRequired()) {
            return false;
        }
        return super.removeWhenFarAway(distanceToClosestPlayer);
    }

    @Override
    public int getExperienceReward() {
        return this.customStats ? super.getExperienceReward() : this.configuredXpReward;
    }

    @Override
    protected ResourceLocation getDefaultLootTable() {
        if (this.customLootTable != null && !this.customLootTable.isBlank()) {
            ResourceLocation parsed = ResourceLocation.tryParse(this.customLootTable);
            if (parsed != null) {
                return parsed;
            }
        }
        return EntityType.ZOMBIE.getDefaultLootTable();
    }

    @Override
    public float getVoicePitch() {
        return switch (getVoice()) {
            case VOICE_FEMALE -> super.getVoicePitch() * 1.28F;
            case VOICE_MUTANT -> super.getVoicePitch() * 0.68F;
            case VOICE_MALE -> super.getVoicePitch() * 0.92F;
            default -> super.getVoicePitch();
        };
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return VOICE_MUTANT.equals(getVoice()) ? SoundEvents.HUSK_AMBIENT : SoundEvents.ZOMBIE_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return VOICE_MUTANT.equals(getVoice()) ? SoundEvents.HUSK_HURT : SoundEvents.ZOMBIE_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return VOICE_MUTANT.equals(getVoice()) ? SoundEvents.HUSK_DEATH : SoundEvents.ZOMBIE_DEATH;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.tickCount > 0 && (this.tickCount % ORPHAN_CHECK_INTERVAL_TICKS) == 0) {
            if (this.persistentModel
                    || this.isPersistenceRequired()
                    || this.getTarget() != null
                    || hasPlayerWithinSq(ORPHAN_PLAYER_RANGE_SQ)) {
                this.idleNoPlayerTicks = 0;
            } else {
                this.idleNoPlayerTicks += ORPHAN_CHECK_INTERVAL_TICKS;
                if (this.idleNoPlayerTicks > ORPHAN_DESPAWN_THRESHOLD_TICKS) {
                    this.discard();
                    return;
                }
            }
        }
        if (this.showBossBar && (this.tickCount % 20) == 0) {
            updateBossBar();
        }
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        wrapGoalSelector(this.goalSelector);
        wrapGoalSelector(this.targetSelector);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new ThrottledZombieNavigation(this, level);
    }

    @Override
    public void aiStep() {
        if (!this.level().isClientSide()) {
            long startNanos = System.nanoTime();
            updateServerAiThrottleState();
            super.aiStep();
            ModCommonEvents.recordWorkNanos(System.nanoTime() - startNanos);
        } else {
            super.aiStep();
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !this.level().isClientSide()) {
            this.combatWakeTicks = 40;
            this.idleNoPlayerTicks = 0;
            this.idleAiThrottled = false;
            this.idleAiPulseOpen = true;
        }
        return result;
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        super.setTarget(target);
        if (target != null && !this.level().isClientSide()) {
            this.idleNoPlayerTicks = 0;
            this.idleAiThrottled = false;
            this.idleAiPulseOpen = true;
        }
    }

    @Override
    protected void doPush(Entity entity) {
        if (entity instanceof CustomZombieEntity && isCrowdedWithZombies()) {
            return;
        }
        super.doPush(entity);
    }

    @Override
    public void push(Entity entity) {
        if (entity instanceof CustomZombieEntity && isCrowdedWithZombies()) {
            return;
        }
        super.push(entity);
    }

    public boolean isIdleAiThrottled() {
        return this.idleAiThrottled;
    }

    boolean shouldSkipIdleAiTick() {
        return this.idleAiThrottled && !this.idleAiPulseOpen;
    }

    private void updateServerAiThrottleState() {
        if (this.combatWakeTicks > 0) {
            this.combatWakeTicks--;
        }
        long gameTime = this.level().getGameTime();
        boolean active = this.getTarget() != null
                || this.hurtTime > 0
                || this.combatWakeTicks > 0
                || hasPlayerWithinSq(IDLE_AI_ACTIVE_RANGE_SQ);

        if (active) {
            this.idleAiThrottled = false;
            this.idleAiPulseOpen = true;
            this.nextIdleAiPulseGameTime = gameTime;
            return;
        }

        this.idleAiThrottled = true;
        if (this.nextIdleAiPulseGameTime < 0L) {
            this.nextIdleAiPulseGameTime = gameTime + Math.floorMod(this.getId(), IDLE_AI_INTERVAL_TICKS);
        }
        if (gameTime >= this.nextIdleAiPulseGameTime) {
            // Keep open for 2 consecutive ticks so Mob.serverAiStep()'s alternating GoalSelector.tick() catches canUse()
            this.idleAiPulseEndGameTime = gameTime + 1L;
            this.nextIdleAiPulseGameTime = gameTime + IDLE_AI_INTERVAL_TICKS;
        }
        this.idleAiPulseOpen = gameTime <= this.idleAiPulseEndGameTime;
    }

    private boolean hasPlayerWithinSq(double maxDistSq) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        List<ServerPlayer> players = serverLevel.players();
        for (int i = 0, n = players.size(); i < n; i++) {
            ServerPlayer player = players.get(i);
            if (!player.isSpectator() && player.distanceToSqr(this) <= maxDistSq) {
                return true;
            }
        }
        return false;
    }

    private boolean isCrowdedWithZombies() {
        long gameTime = this.level().getGameTime();
        if ((gameTime - this.lastCrowdCheckGameTime) < CROWD_CACHE_TICKS) {
            return this.cachedCrowdedGroup;
        }
        this.lastCrowdCheckGameTime = gameTime;
        List<CustomZombieEntity> nearby = this.level().getEntitiesOfClass(
                CustomZombieEntity.class,
                this.getBoundingBox().inflate(2.0D)
        );
        int count = 0;
        boolean crowded = false;
        for (int i = 0, n = nearby.size(); i < n; i++) {
            CustomZombieEntity other = nearby.get(i);
            if (other != this && other.isAlive() && other.distanceToSqr(this) <= CROWD_RADIUS_SQ) {
                count++;
                if (count > CROWD_ZOMBIE_LIMIT) {
                    crowded = true;
                    break;
                }
            }
        }
        this.cachedCrowdedGroup = crowded;
        return crowded;
    }

    private void wrapGoalSelector(GoalSelector selector) {
        List<WrappedGoal> snapshot = new ArrayList<>(selector.getAvailableGoals());
        if (snapshot.isEmpty()) {
            return;
        }
        selector.removeAllGoals(goal -> true);
        for (WrappedGoal wg : snapshot) {
            Goal goal = wg.getGoal();
            if (goal instanceof ThrottledGoal) {
                selector.addGoal(wg.getPriority(), goal);
            } else {
                selector.addGoal(wg.getPriority(), new ThrottledGoal(this, goal));
            }
        }
    }

    /**
     * Navigation wrapper that throttles path recalculation to once every 10 ticks
     * when chasing targets further than 16 blocks away (prevents horde pathfinding spikes).
     */
    private static final class ThrottledZombieNavigation extends GroundPathNavigation {

        private long lastLongRangePathGameTime = -100L;

        ThrottledZombieNavigation(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        public boolean moveTo(Entity entity, double speedModifier) {
            if (entity != null && this.mob.distanceToSqr(entity) > LONG_RANGE_PATH_SQ) {
                long gameTime = this.level.getGameTime();
                if (this.path != null && !this.path.isDone()
                        && (gameTime - this.lastLongRangePathGameTime) < LONG_RANGE_PATH_INTERVAL_TICKS) {
                    this.speedModifier = speedModifier;
                    return true;
                }
                this.lastLongRangePathGameTime = gameTime;
            }
            return super.moveTo(entity, speedModifier);
        }

        @Override
        public void recomputePath() {
            LivingEntity target = this.mob.getTarget();
            if (target != null && this.mob.distanceToSqr(target) > LONG_RANGE_PATH_SQ) {
                long gameTime = this.level.getGameTime();
                if (this.path != null && !this.path.isDone()
                        && (gameTime - this.lastLongRangePathGameTime) < LONG_RANGE_PATH_INTERVAL_TICKS) {
                    return;
                }
                this.lastLongRangePathGameTime = gameTime;
            }
            super.recomputePath();
        }
    }

    /**
     * Lightweight Goal proxy that skips expensive scanning and idle ticking when the zombie
     * is far from players (>32 blocks) and has no active target or recent damage.
     */
    private static final class ThrottledGoal extends Goal {

        private final CustomZombieEntity zombie;
        private final Goal delegate;

        ThrottledGoal(CustomZombieEntity zombie, Goal delegate) {
            this.zombie = zombie;
            this.delegate = delegate;
        }

        @Override
        public boolean canUse() {
            if (this.zombie.shouldSkipIdleAiTick()) {
                return false;
            }
            return this.delegate.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            if (this.zombie.shouldSkipIdleAiTick()) {
                return false;
            }
            return this.delegate.canContinueToUse();
        }

        @Override
        public boolean isInterruptable() {
            return this.delegate.isInterruptable();
        }

        @Override
        public void start() {
            this.delegate.start();
        }

        @Override
        public void stop() {
            this.delegate.stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return this.delegate.requiresUpdateEveryTick();
        }

        @Override
        public void tick() {
            if (this.zombie.shouldSkipIdleAiTick()) {
                return;
            }
            this.delegate.tick();
        }

        @Override
        public void setFlags(EnumSet<Flag> flags) {
            this.delegate.setFlags(flags);
        }

        @Override
        public EnumSet<Flag> getFlags() {
            return this.delegate.getFlags();
        }
    }

    private void updateBossBar() {
        if (!this.showBossBar || !this.isAlive() || !(this.level() instanceof ServerLevel serverLevel)) {
            if (!this.bossEvent.getPlayers().isEmpty()) {
                this.bossEvent.removeAllPlayers();
            }
            this.bossEvent.setVisible(false);
            return;
        }

        this.bossEvent.setVisible(true);
        this.bossEvent.setName(this.getDisplayName());
        float maxHp = Math.max(1.0F, this.getMaxHealth());
        this.bossEvent.setProgress(Math.max(0.0F, Math.min(1.0F, this.getHealth() / maxHp)));

        for (ServerPlayer player : serverLevel.players()) {
            if (!player.isSpectator() && player.distanceToSqr(this) <= BOSS_BAR_RANGE_SQ) {
                this.bossEvent.addPlayer(player);
            } else {
                this.bossEvent.removePlayer(player);
            }
        }
    }

    @Override
    public void die(DamageSource cause) {
        this.bossEvent.removeAllPlayers();
        super.die(cause);
    }

    @Override
    public void remove(RemovalReason reason) {
        this.bossEvent.removeAllPlayers();
        super.remove(reason);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_MODEL_ID, this.getModelId());
        tag.putInt(TAG_TEXTURE_ID, this.getTextureId());
        tag.putString(TAG_VOICE_PROFILE, this.getVoice());
        tag.putBoolean(TAG_SKIP_ZONE_CHECK, this.skipZoneCheck);
        tag.putBoolean(TAG_CUSTOM_STATS, this.customStats);
        tag.putBoolean(TAG_VARIANT_INITIALIZED, this.variantInitialized);
        tag.putBoolean(TAG_CONFIGURED_CUSTOM_NAME, this.hasConfiguredCustomName);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        readCustomNbtFields(tag);
        readCustomNbtFields(this.getPersistentData());

        if (!this.customStats) {
            float savedHealth = tag.contains("Health") ? tag.getFloat("Health") : -1.0F;
            this.applyModelStats(savedHealth <= 0.0F);
            if (savedHealth > 0.0F) {
                this.setHealth(Math.min(this.getMaxHealth(), savedHealth));
            }
        }
    }

    private void readCustomNbtFields(@Nullable CompoundTag tag) {
        if (tag == null) {
            return;
        }
        if (tag.contains(TAG_SKIP_ZONE_CHECK)) {
            this.skipZoneCheck = tag.getBoolean(TAG_SKIP_ZONE_CHECK);
        }
        if (tag.contains(TAG_CUSTOM_STATS)) {
            this.customStats = tag.getBoolean(TAG_CUSTOM_STATS);
        }
        if (this.fixedModelId >= 0) {
            this.entityData.set(MODEL_ID, this.fixedModelId);
            this.explicitModelFromNbt = true;
            this.variantInitialized = true;
            if (!this.explicitVoiceFromNbt) {
                this.entityData.set(VOICE_PROFILE, defaultVoiceForModel(this.fixedModelId));
            }
        } else if (tag.contains(TAG_MODEL_ID)) {
            int m = clampModelId(tag.getInt(TAG_MODEL_ID));
            this.entityData.set(MODEL_ID, m);
            this.explicitModelFromNbt = true;
            this.variantInitialized = true;
            if (!this.explicitVoiceFromNbt) {
                this.entityData.set(VOICE_PROFILE, defaultVoiceForModel(m));
            }
        }
        if (tag.contains(TAG_TEXTURE_ID)) {
            this.setTextureId(tag.getInt(TAG_TEXTURE_ID));
            this.explicitTextureFromNbt = true;
        }
        if (tag.contains(TAG_VOICE_PROFILE)) {
            this.explicitVoiceFromNbt = true;
            this.entityData.set(VOICE_PROFILE, normalizeVoiceProfile(tag.getString(TAG_VOICE_PROFILE), this.getModelId()));
        }
        if (tag.contains(TAG_VARIANT_INITIALIZED)) {
            this.variantInitialized = tag.getBoolean(TAG_VARIANT_INITIALIZED);
        }
        if (tag.contains(TAG_CONFIGURED_CUSTOM_NAME)) {
            this.hasConfiguredCustomName = tag.getBoolean(TAG_CONFIGURED_CUSTOM_NAME);
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            MobSpawnType reason,
            @Nullable SpawnGroupData spawnData,
            @Nullable CompoundTag dataTag
    ) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        this.setBaby(false);

        if (reason == MobSpawnType.COMMAND
                || reason == MobSpawnType.EVENT
                || reason == MobSpawnType.SPAWN_EGG
                || reason == MobSpawnType.DISPENSER) {
            this.skipZoneCheck = true;
        }
        readCustomNbtFields(dataTag);
        readCustomNbtFields(this.getPersistentData());

        if (!this.variantInitialized && !this.explicitModelFromNbt && !this.hasFixedEntityModel()) {
            this.assignRandomVariant(level.getRandom());
        } else {
            if ((this.explicitModelFromNbt || this.hasFixedEntityModel()) && !this.explicitTextureFromNbt) {
                int texCount = ZombieVariantRegistry.getTextureCount(this.getModelId());
                this.setTextureId(texCount > 1 ? level.getRandom().nextInt(texCount) : 0);
            }
            if (!this.explicitVoiceFromNbt) {
                this.entityData.set(VOICE_PROFILE, defaultVoiceForModel(this.getModelId()));
            }
            this.variantInitialized = true;
            this.applyModelStats(true);
        }

        return result;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "zombieController", 4, this::animationPredicate));
    }

    private PlayState animationPredicate(AnimationState<CustomZombieEntity> state) {
        if (this.isDeadOrDying() || this.deathTime > 0) {
            return state.setAndContinue(ANIM_DEATH);
        }

        if (this.hurtTime > 0) {
            return state.setAndContinue(ANIM_HURT);
        }

        if (this.swinging) {
            return state.setAndContinue(state.isMoving() ? ANIM_RUN_ATTACK : ANIM_ATTACK);
        }

        if (state.isMoving()) {
            return state.setAndContinue((this.isAggressive() || this.isSprinting()) ? ANIM_RUN : ANIM_WALK);
        }

        return state.setAndContinue(ANIM_IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animCache;
    }
}
