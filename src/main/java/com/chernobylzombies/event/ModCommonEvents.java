package com.chernobylzombies.event;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.command.CZonesCommand;
import com.chernobylzombies.data.ServerConfigManager;
import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.item.ZoneWandItem;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.registry.ModEntities;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Common Forge-bus event handlers optimized for dedicated server TPS:
 * - Zero-allocation early exit when SpawnerMode == KUBEJS_ONLY
 * - Reusable BlockPos.MutableBlockPos for spawn-ground lookups (zero GC pressure)
 * - Staggered HordeSpawnQueue draining at most 2 zombies per tick at the end of ServerTickEvent
 * - Cached zone entity counts updated every 40 ticks via a single lightweight sweep
 */
@Mod.EventBusSubscriber(modid = ChernobylZombiesMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModCommonEvents {

    private static final int MAX_EXTRA_SPAWNS_PER_EVENT = 8;
    private static final double ACTIVE_ZONE_PLAYER_RANGE = 64.0D;
    private static final int MAX_BLOCK_LIGHT_FOR_DARK_SPAWN = 7;
    private static final int TELEMETRY_WINDOW = 100;

    private static final Map<UUID, Integer> CACHED_ZONE_COUNTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<BlockPos.MutableBlockPos> GROUND_CURSOR =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private static final ThreadLocal<BlockPos.MutableBlockPos> PROBE_CURSOR =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static final long[] TICK_NANOS_RING = new long[TELEMETRY_WINDOW];
    private static int telemetryRingIdx = 0;
    private static int telemetryRingCount = 0;
    private static long telemetryRingSumNanos = 0L;
    private static long currentTickAccumulatedNanos = 0L;

    private static int tickCounter = 0;
    private static int secondCounter = 0;

    private ModCommonEvents() {
    }

    public static void recordWorkNanos(long nanos) {
        if (nanos > 0L) {
            currentTickAccumulatedNanos += nanos;
        }
    }

    private static void commitTickTelemetry(long serverTickHandlerNanos) {
        long total = Math.max(0L, currentTickAccumulatedNanos + Math.max(0L, serverTickHandlerNanos));
        currentTickAccumulatedNanos = 0L;
        if (telemetryRingCount < TELEMETRY_WINDOW) {
            telemetryRingSumNanos += total;
            TICK_NANOS_RING[telemetryRingIdx] = total;
            telemetryRingCount++;
        } else {
            telemetryRingSumNanos -= TICK_NANOS_RING[telemetryRingIdx];
            TICK_NANOS_RING[telemetryRingIdx] = total;
            telemetryRingSumNanos += total;
        }
        telemetryRingIdx = (telemetryRingIdx + 1) % TELEMETRY_WINDOW;
    }

    public static double getAverageTickMs() {
        if (telemetryRingCount <= 0) {
            return 0.0D;
        }
        return ((double) telemetryRingSumNanos / telemetryRingCount) / 1_000_000.0D;
    }

    private static void resetTelemetry() {
        telemetryRingIdx = 0;
        telemetryRingCount = 0;
        telemetryRingSumNanos = 0L;
        currentTickAccumulatedNanos = 0L;
        java.util.Arrays.fill(TICK_NANOS_RING, 0L);
    }

    public static int getCachedZoneCount(UUID zoneId) {
        return CACHED_ZONE_COUNTS.getOrDefault(zoneId, 0);
    }

    public static void setCachedZoneCount(UUID zoneId, int count) {
        if (zoneId != null) {
            CACHED_ZONE_COUNTS.put(zoneId, Math.max(0, count));
        }
    }

    public static void incrementCachedZoneCount(UUID zoneId) {
        if (zoneId != null) {
            CACHED_ZONE_COUNTS.merge(zoneId, 1, Integer::sum);
        }
    }

    /**
     * Single lightweight sweep over loaded entities across all levels (every 40 ticks).
     */
    public static void refreshZoneCountCache(MinecraftServer server, ZombieZonesSavedData data) {
        if (server == null) {
            return;
        }
        Map<UUID, Integer> newCounts = new ConcurrentHashMap<>();
        Map<ResourceKey<Level>, List<SpawnZone>> zonesByDim = new ConcurrentHashMap<>();

        for (SpawnZone zone : data.getZones()) {
            newCounts.put(zone.getId(), 0);
            zonesByDim.computeIfAbsent(zone.getDimension(), k -> new ArrayList<>()).add(zone);
        }

        for (Map.Entry<ResourceKey<Level>, List<SpawnZone>> entry : zonesByDim.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            List<SpawnZone> dimZones = entry.getValue();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CustomZombieEntity zombie && zombie.isAlive()) {
                    BlockPos pos = zombie.blockPosition();
                    for (int i = 0; i < dimZones.size(); i++) {
                        SpawnZone zone = dimZones.get(i);
                        if (zone.contains(pos)) {
                            newCounts.merge(zone.getId(), 1, Integer::sum);
                        }
                    }
                }
            }
        }

        CACHED_ZONE_COUNTS.clear();
        CACHED_ZONE_COUNTS.putAll(newCounts);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        HordeSpawnQueue.clear();
        resetTelemetry();
        MinecraftServer server = event.getServer();
        ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
        ServerConfigManager.ensureFilesExist(data);
        refreshZoneCountCache(server, data);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        HordeSpawnQueue.clear();
        CACHED_ZONE_COUNTS.clear();
        resetTelemetry();
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (ZombieZonesSavedData.getActiveSpawnerMode() == SpawnerMode.KUBEJS_ONLY || event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        long startNanos = System.nanoTime();
        try {
            handleEntityJoinServer(event, serverLevel);
        } finally {
            recordWorkNanos(System.nanoTime() - startNanos);
        }
    }

    private static void handleEntityJoinServer(EntityJoinLevelEvent event, ServerLevel serverLevel) {
        Entity entity = event.getEntity();
        if (entity instanceof CustomZombieEntity customZombie) {
            handleDirectCustomZombieJoin(event, serverLevel, customZombie);
            return;
        }

        if (entity.getType() != EntityType.ZOMBIE) {
            return;
        }

        // Always cancel vanilla zombie spawns
        event.setCanceled(true);

        ZombieZonesSavedData data = ZombieZonesSavedData.get(serverLevel);
        BlockPos spawnPos = entity.blockPosition();
        RandomSource random = serverLevel.getRandom();

        // Hierarchy 1: Specific active polygonal SpawnZone
        Optional<SpawnZone> zoneOpt = data.getActiveZoneAt(serverLevel.dimension(), spawnPos);
        if (zoneOpt.isPresent()) {
            SpawnZone zone = zoneOpt.get();
            if (!zone.getTimeCondition().matches(serverLevel) || !passesLightCondition(serverLevel, spawnPos, zone.isIgnoreLightLevel())) {
                return;
            }
            int currentCount = getCachedZoneCount(zone.getId()) + HordeSpawnQueue.getPendingCountForZone(zone.getId());
            int modelId = evaluateAndPickModel(
                    currentCount,
                    zone.getMaxZombies(),
                    zone.getSpawnRateMultiplier(),
                    zone.getTotalWeight(),
                    () -> zone.pickRandomModelId(random),
                    random
            );
            if (modelId < 0) {
                return;
            }

            BlockPos safePos = findSafeGroundSpawnPos(
                    serverLevel,
                    spawnPos.getX(),
                    spawnPos.getZ(),
                    zone.getMinY(),
                    zone.getMaxY(),
                    spawnPos.getY(),
                    zone.isIgnoreLightLevel()
            );
            if (safePos == null) {
                return;
            }

            if (HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forExact(
                    serverLevel.dimension(),
                    zone.getId(),
                    safePos.getX() + 0.5D,
                    safePos.getY(),
                    safePos.getZ() + 0.5D,
                    entity.getYRot(),
                    entity.getXRot(),
                    entity.getYHeadRot(),
                    entity.getVisualRotationYInDegrees(),
                    modelId,
                    entity
            ))) {
                enqueueExtraZombiesInZone(serverLevel, zone, safePos, zone.getMaxZombies() - (currentCount + 1), random);
            }
            return;
        }

        // Hierarchy 2: Global (Common) SpawnZone fallback
        GlobalSpawnZone globalZone = data.getGlobalZone();
        if (!globalZone.isEnabled()
                || !globalZone.getTimeCondition().matches(serverLevel)
                || !passesLightCondition(serverLevel, spawnPos, globalZone.isIgnoreLightLevel())) {
            return;
        }

        int areaCount = countAliveInGlobalArea(serverLevel, globalZone, spawnPos);
        int modelId = evaluateAndPickModel(
                areaCount,
                globalZone.getMaxZombiesPerArea(),
                globalZone.getSpawnRateMultiplier(),
                globalZone.getTotalWeight(),
                () -> globalZone.pickRandomModelId(random),
                random
        );
        if (modelId < 0) {
            return;
        }

        BlockPos safePos = findSafeGroundSpawnPos(
                serverLevel,
                spawnPos.getX(),
                spawnPos.getZ(),
                spawnPos.getY() - 6,
                spawnPos.getY() + 6,
                spawnPos.getY(),
                globalZone.isIgnoreLightLevel()
        );
        if (safePos == null) {
            return;
        }

        if (HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forExact(
                serverLevel.dimension(),
                null,
                safePos.getX() + 0.5D,
                safePos.getY(),
                safePos.getZ() + 0.5D,
                entity.getYRot(),
                entity.getXRot(),
                entity.getYHeadRot(),
                entity.getVisualRotationYInDegrees(),
                modelId,
                entity
        ))) {
            enqueueExtraZombiesGlobal(serverLevel, globalZone, safePos, globalZone.getMaxZombiesPerArea() - (areaCount + 1), random);
        }
    }

    private static void handleDirectCustomZombieJoin(
            EntityJoinLevelEvent event,
            ServerLevel serverLevel,
            CustomZombieEntity customZombie
    ) {
        if (customZombie.isApprovedZoneSpawn() || customZombie.isSkipZoneCheck() || event.loadedFromDisk()) {
            return;
        }

        SpawnerMode mode = ZombieZonesSavedData.getActiveSpawnerMode();
        if (mode == SpawnerMode.HYBRID) {
            customZombie.setApprovedZoneSpawn(true);
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(serverLevel);
        BlockPos spawnPos = customZombie.blockPosition();
        RandomSource random = serverLevel.getRandom();

        Optional<SpawnZone> zoneOpt = data.getActiveZoneAt(serverLevel.dimension(), spawnPos);
        if (zoneOpt.isPresent()) {
            SpawnZone zone = zoneOpt.get();
            if (!zone.getTimeCondition().matches(serverLevel) || !passesLightCondition(serverLevel, spawnPos, zone.isIgnoreLightLevel())) {
                event.setCanceled(true);
                return;
            }
            int currentCount = getCachedZoneCount(zone.getId());
            if (currentCount >= zone.getMaxZombies()) {
                event.setCanceled(true);
                return;
            }
            if (!customZombie.hasExplicitModelFromNbt() && !customZombie.hasFixedEntityModel()) {
                int modelId = evaluateAndPickModel(
                        currentCount,
                        zone.getMaxZombies(),
                        zone.getSpawnRateMultiplier(),
                        zone.getTotalWeight(),
                        () -> zone.pickRandomModelId(random),
                        random
                );
                if (modelId < 0) {
                    event.setCanceled(true);
                    return;
                }
                customZombie.assignVariant(modelId, random);
            }
            customZombie.setApprovedZoneSpawn(true);
            incrementCachedZoneCount(zone.getId());
            return;
        }

        GlobalSpawnZone globalZone = data.getGlobalZone();
        if (!globalZone.isEnabled()
                || !globalZone.getTimeCondition().matches(serverLevel)
                || !passesLightCondition(serverLevel, spawnPos, globalZone.isIgnoreLightLevel())) {
            event.setCanceled(true);
            return;
        }

        int areaCount = countAliveInGlobalArea(serverLevel, globalZone, spawnPos);
        if (areaCount >= globalZone.getMaxZombiesPerArea()) {
            event.setCanceled(true);
            return;
        }
        if (!customZombie.hasExplicitModelFromNbt() && !customZombie.hasFixedEntityModel()) {
            int modelId = evaluateAndPickModel(
                    areaCount,
                    globalZone.getMaxZombiesPerArea(),
                    globalZone.getSpawnRateMultiplier(),
                    globalZone.getTotalWeight(),
                    () -> globalZone.pickRandomModelId(random),
                    random
            );
            if (modelId < 0) {
                event.setCanceled(true);
                return;
            }
            customZombie.assignVariant(modelId, random);
        }
        customZombie.setApprovedZoneSpawn(true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        long startNanos = System.nanoTime();
        try {
            MinecraftServer server = event.getServer() != null ? event.getServer() : ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return;
            }

            if (ZombieZonesSavedData.getActiveSpawnerMode() != SpawnerMode.KUBEJS_ONLY) {
                tickCounter++;
                if (tickCounter % 20 == 0 && server.getPlayerList().getPlayerCount() > 0) {
                    secondCounter++;
                    ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
                    boolean refreshCycle = (tickCounter >= 40);
                    if (refreshCycle) {
                        tickCounter = 0;
                        refreshZoneCountCache(server, data);
                    }

                    enqueueActiveZoneSpawners(server, data);
                    enqueueActiveGlobalSpawner(server, data);

                    if (refreshCycle) {
                        PacketHandler.syncToAllAdmins(server);
                    }
                }
            }

            // Always drain up to 2 staggered spawns at the end of the server tick
            HordeSpawnQueue.tick(server);
        } finally {
            commitTickTelemetry(System.nanoTime() - startNanos);
        }
    }

    private static void enqueueActiveZoneSpawners(MinecraftServer server, ZombieZonesSavedData data) {
        for (SpawnZone zone : data.getZones()) {
            if (!zone.isEnabled()
                    || zone.getVertices().size() < 3
                    || zone.getTotalWeight() <= 0
                    || zone.getSpawnRateMultiplier() <= 0.0F) {
                continue;
            }
            if (secondCounter % zone.getSpawnIntervalSeconds() != 0) {
                continue;
            }

            ServerLevel zoneLevel = server.getLevel(zone.getDimension());
            if (zoneLevel == null || !zone.getTimeCondition().matches(zoneLevel)) {
                continue;
            }
            if (!hasPlayerNearZone(zoneLevel, zone, ACTIVE_ZONE_PLAYER_RANGE)) {
                continue;
            }
            int currentPlusPending = getCachedZoneCount(zone.getId()) + HordeSpawnQueue.getPendingCountForZone(zone.getId());
            if (currentPlusPending >= zone.getMaxZombies()) {
                continue;
            }

            RandomSource random = zoneLevel.getRandom();
            int modelId = zone.pickRandomModelId(random);
            if (modelId < 0) {
                continue;
            }

            HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forZone(
                    zoneLevel.dimension(),
                    zone.getId(),
                    null,
                    modelId,
                    zone.isIgnoreLightLevel()
            ));
        }
    }

    private static void enqueueActiveGlobalSpawner(MinecraftServer server, ZombieZonesSavedData data) {
        GlobalSpawnZone globalZone = data.getGlobalZone();
        if (!globalZone.isEnabled()
                || globalZone.getTotalWeight() <= 0
                || globalZone.getSpawnRateMultiplier() <= 0.0F
                || (secondCounter % globalZone.getSpawnIntervalSeconds() != 0)) {
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (!globalZone.getTimeCondition().matches(level)) {
                continue;
            }
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }

            RandomSource random = level.getRandom();
            ServerPlayer anchorPlayer = players.get(random.nextInt(players.size()));
            if (anchorPlayer.isSpectator()) {
                continue;
            }

            BlockPos spawnPos = findValidGlobalSpawnNearPlayer(level, data, globalZone, anchorPlayer.blockPosition(), random);
            if (spawnPos == null) {
                continue;
            }
            if (countAliveInGlobalArea(level, globalZone, spawnPos) >= globalZone.getMaxZombiesPerArea()) {
                continue;
            }

            int modelId = globalZone.pickRandomModelId(random);
            if (modelId < 0) {
                continue;
            }

            float yaw = random.nextFloat() * 360.0F;
            HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forExact(
                    level.dimension(),
                    null,
                    spawnPos.getX() + 0.5D,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5D,
                    yaw,
                    0.0F,
                    yaw,
                    yaw,
                    modelId,
                    null
            ));
        }
    }

    private static boolean hasPlayerNearZone(ServerLevel level, SpawnZone zone, double range) {
        double rangeSq = range * range;
        AABB zoneBounds = zone.getBoundingBox();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (distanceSqToAABB(zoneBounds, player.position()) <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    private static double distanceSqToAABB(AABB box, Vec3 pos) {
        double dx = Math.max(0.0D, Math.max(box.minX - pos.x, pos.x - box.maxX));
        double dy = Math.max(0.0D, Math.max(box.minY - pos.y, pos.y - box.maxY));
        double dz = Math.max(0.0D, Math.max(box.minZ - pos.z, pos.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    @Nullable
    public static BlockPos findRandomSafeSpawnInZone(
            ServerLevel level,
            SpawnZone zone,
            @Nullable BlockPos anchorPos,
            RandomSource random,
            boolean ignoreLightOverride
    ) {
        List<BlockPos> vertices = zone.getVertices();
        if (vertices.size() < 3) {
            return null;
        }
        AABB bounds = zone.getBoundingBox();
        int minX = Mth.floor(bounds.minX);
        int maxX = Mth.floor(bounds.maxX - 1.0D);
        int minZ = Mth.floor(bounds.minZ);
        int maxZ = Mth.floor(bounds.maxZ - 1.0D);

        if (anchorPos != null) {
            minX = Math.max(minX, anchorPos.getX() - 12);
            maxX = Math.min(maxX, anchorPos.getX() + 12);
            minZ = Math.max(minZ, anchorPos.getZ() - 12);
            maxZ = Math.min(maxZ, anchorPos.getZ() + 12);
            if (minX > maxX || minZ > maxZ) {
                return null;
            }
        }

        boolean ignoreLight = ignoreLightOverride || zone.isIgnoreLightLevel();
        Integer preferredY = anchorPos != null ? anchorPos.getY() : null;

        for (int attempt = 0; attempt < 30; attempt++) {
            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            if (!SpawnZone.isPointInPolygonXZ(x + 0.5D, z + 0.5D, vertices)) {
                continue;
            }
            BlockPos.MutableBlockPos cursor = findSafeGroundMutable(level, x, z, zone.getMinY(), zone.getMaxY(), preferredY, ignoreLight);
            if (cursor != null && cursor.getY() >= zone.getMinY() && cursor.getY() <= zone.getMaxY()) {
                return cursor.immutable();
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findValidGlobalSpawnNearPlayer(
            ServerLevel level,
            ZombieZonesSavedData data,
            GlobalSpawnZone globalZone,
            BlockPos playerPos,
            RandomSource random
    ) {
        BlockPos.MutableBlockPos probe = PROBE_CURSOR.get();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = 24.0D + random.nextDouble() * 24.0D;
            int x = playerPos.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = playerPos.getZ() + (int) Math.round(Math.sin(angle) * distance);

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int minY = Math.min(surfaceY - 4, playerPos.getY() - 10);
            int maxY = Math.max(surfaceY + 2, playerPos.getY() + 10);

            BlockPos.MutableBlockPos candidate = findSafeGroundMutable(
                    level,
                    x,
                    z,
                    minY,
                    maxY,
                    playerPos.getY(),
                    globalZone.isIgnoreLightLevel()
            );
            if (candidate != null) {
                probe.set(candidate);
                if (data.getActiveZoneAt(level.dimension(), probe).isEmpty()) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    @Nullable
    public static BlockPos findSafeGroundSpawnPos(
            ServerLevel level,
            int x,
            int z,
            int minSearchY,
            int maxSearchY,
            @Nullable Integer preferredY,
            boolean ignoreLightLevel
    ) {
        BlockPos.MutableBlockPos found = findSafeGroundMutable(level, x, z, minSearchY, maxSearchY, preferredY, ignoreLightLevel);
        return found != null ? found.immutable() : null;
    }

    @Nullable
    private static BlockPos.MutableBlockPos findSafeGroundMutable(
            ServerLevel level,
            int x,
            int z,
            int minSearchY,
            int maxSearchY,
            @Nullable Integer preferredY,
            boolean ignoreLightLevel
    ) {
        BlockPos.MutableBlockPos cursor = GROUND_CURSOR.get();
        int clampedMin = Math.max(level.getMinBuildHeight() + 1, minSearchY - 1);
        int clampedMax = Math.min(level.getMaxBuildHeight() - 3, maxSearchY);
        if (clampedMin > clampedMax) {
            return null;
        }
        cursor.set(x, Mth.clamp(clampedMin, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1), z);
        if (!level.isLoaded(cursor)) {
            return null;
        }

        if (preferredY != null) {
            int localTop = Math.min(clampedMax, preferredY + 8);
            int localBottom = Math.max(clampedMin, preferredY - 8);
            for (int groundY = localTop; groundY >= localBottom; groundY--) {
                if (isGroundColumnValid(level, x, groundY, z, ignoreLightLevel, cursor)) {
                    return cursor.set(x, groundY + 1, z);
                }
            }
        }

        for (int groundY = clampedMax; groundY >= clampedMin; groundY--) {
            if (isGroundColumnValid(level, x, groundY, z, ignoreLightLevel, cursor)) {
                return cursor.set(x, groundY + 1, z);
            }
        }
        return null;
    }

    private static boolean isGroundColumnValid(
            ServerLevel level,
            int x,
            int groundY,
            int z,
            boolean ignoreLightLevel,
            BlockPos.MutableBlockPos cursor
    ) {
        cursor.set(x, groundY, z);
        BlockState floorState = level.getBlockState(cursor);
        if (floorState.getCollisionShape(level, cursor).isEmpty() || !floorState.getFluidState().isEmpty()) {
            return false;
        }

        cursor.set(x, groundY + 1, z);
        BlockState feetState = level.getBlockState(cursor);
        if (!feetState.getCollisionShape(level, cursor).isEmpty() || !feetState.getFluidState().isEmpty()) {
            return false;
        }

        cursor.set(x, groundY + 2, z);
        BlockState headState = level.getBlockState(cursor);
        if (!headState.getCollisionShape(level, cursor).isEmpty() || !headState.getFluidState().isEmpty()) {
            return false;
        }

        if (!ignoreLightLevel) {
            cursor.set(x, groundY + 1, z);
            return level.getBrightness(LightLayer.BLOCK, cursor) <= MAX_BLOCK_LIGHT_FOR_DARK_SPAWN;
        }
        return true;
    }

    public static int countAliveInGlobalArea(ServerLevel serverLevel, GlobalSpawnZone globalZone, BlockPos center) {
        double maxDistSq = GlobalSpawnZone.DENSITY_RADIUS * GlobalSpawnZone.DENSITY_RADIUS;
        return serverLevel.getEntitiesOfClass(
                CustomZombieEntity.class,
                globalZone.getAreaBoundingBox(center),
                z -> z.isAlive() && z.blockPosition().distSqr(center) <= maxDistSq
        ).size();
    }

    private static int evaluateAndPickModel(
            int currentCount,
            int maxAllowed,
            float rate,
            int totalWeight,
            IntSupplier modelPicker,
            RandomSource random
    ) {
        if (currentCount >= maxAllowed || totalWeight <= 0 || rate <= 0.0F) {
            return -1;
        }
        if (rate < 1.0F && random.nextFloat() > rate) {
            return -1;
        }
        return modelPicker.getAsInt();
    }

    private static boolean passesLightCondition(ServerLevel level, BlockPos pos, boolean ignoreLightLevel) {
        return ignoreLightLevel || level.getBrightness(LightLayer.BLOCK, pos) <= MAX_BLOCK_LIGHT_FOR_DARK_SPAWN;
    }

    private static void enqueueExtraZombiesInZone(
            ServerLevel serverLevel,
            SpawnZone zone,
            BlockPos origin,
            int remainingCapacity,
            RandomSource random
    ) {
        int extraCount = computeExtraCount(zone.getSpawnRateMultiplier(), remainingCapacity, random);
        for (int i = 0; i < extraCount; i++) {
            int extraModelId = zone.pickRandomModelId(random);
            if (extraModelId < 0) {
                break;
            }
            HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forZone(
                    serverLevel.dimension(),
                    zone.getId(),
                    origin,
                    extraModelId,
                    zone.isIgnoreLightLevel()
            ));
        }
    }

    private static void enqueueExtraZombiesGlobal(
            ServerLevel serverLevel,
            GlobalSpawnZone globalZone,
            BlockPos origin,
            int remainingCapacity,
            RandomSource random
    ) {
        int extraCount = computeExtraCount(globalZone.getSpawnRateMultiplier(), remainingCapacity, random);
        for (int i = 0; i < extraCount; i++) {
            int extraModelId = globalZone.pickRandomModelId(random);
            if (extraModelId < 0) {
                break;
            }
            HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forGlobal(
                    serverLevel.dimension(),
                    origin,
                    extraModelId,
                    globalZone.isIgnoreLightLevel()
            ));
        }
    }

    private static int computeExtraCount(float rate, int remainingCapacity, RandomSource random) {
        if (rate <= 1.0F || remainingCapacity <= 0) {
            return 0;
        }
        float extra = rate - 1.0F;
        int extraCount = (int) extra + (random.nextFloat() < (extra - (int) extra) ? 1 : 0);
        return Math.min(extraCount, Math.min(remainingCapacity, MAX_EXTRA_SPAWNS_PER_EVENT));
    }

    @Nullable
    public static BlockPos findNearbySafeGlobalPos(
            ServerLevel level,
            ZombieZonesSavedData data,
            BlockPos origin,
            boolean ignoreLightLevel,
            RandomSource random
    ) {
        BlockPos.MutableBlockPos probe = PROBE_CURSOR.get();
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = origin.getX() + random.nextInt(13) - 6;
            int z = origin.getZ() + random.nextInt(13) - 6;
            BlockPos feet = findSafeGroundSpawnPos(level, x, z, origin.getY() - 5, origin.getY() + 5, origin.getY(), ignoreLightLevel);
            if (feet != null) {
                probe.set(feet);
                if (data.getActiveZoneAt(level.dimension(), probe).isEmpty()) {
                    return feet;
                }
            }
        }
        return null;
    }

    public static boolean spawnApprovedZombie(
            ServerLevel serverLevel,
            @Nullable Entity sourceEntity,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            float yHeadRot,
            float yBodyRot,
            int modelId
    ) {
        Component name = (sourceEntity != null && sourceEntity.hasCustomName()) ? sourceEntity.getCustomName() : null;
        boolean nameVisible = sourceEntity != null && sourceEntity.isCustomNameVisible();
        boolean persistent = sourceEntity instanceof Mob mob && mob.isPersistenceRequired();
        return spawnApprovedZombie(serverLevel, name, nameVisible, persistent, x, y, z, yRot, xRot, yHeadRot, yBodyRot, modelId, null);
    }

    public static boolean spawnApprovedZombie(
            ServerLevel serverLevel,
            @Nullable Component customName,
            boolean customNameVisible,
            boolean persistenceRequired,
            double x,
            double y,
            double z,
            float yRot,
            float xRot,
            float yHeadRot,
            float yBodyRot,
            int modelId,
            @Nullable UUID incrementZoneId
    ) {
        CustomZombieEntity customZombie = ModEntities.getEntityTypeForModel(modelId).create(serverLevel);
        if (customZombie == null) {
            return false;
        }

        customZombie.moveTo(x, y, z, yRot, xRot);
        customZombie.setYHeadRot(yHeadRot);
        customZombie.setYBodyRot(yBodyRot);

        if (customName != null) {
            customZombie.setCustomName(customName);
            customZombie.setCustomNameVisible(customNameVisible);
        }
        if (persistenceRequired) {
            customZombie.setPersistenceRequired();
        }

        customZombie.assignVariant(modelId, serverLevel.getRandom());
        customZombie.finalizeSpawn(
                serverLevel,
                serverLevel.getCurrentDifficultyAt(customZombie.blockPosition()),
                MobSpawnType.NATURAL,
                null,
                null
        );
        customZombie.setApprovedZoneSpawn(true);

        if (!serverLevel.noCollision(customZombie)) {
            BlockPos fallback = findSafeGroundSpawnPos(
                    serverLevel,
                    Mth.floor(x),
                    Mth.floor(z),
                    Mth.floor(y) - 3,
                    Mth.floor(y) + 4,
                    Mth.floor(y),
                    true
            );
            if (fallback != null) {
                customZombie.moveTo(fallback.getX() + 0.5D, fallback.getY(), fallback.getZ() + 0.5D, yRot, xRot);
            }
            if (!serverLevel.noCollision(customZombie)) {
                return false;
            }
        }

        serverLevel.addFreshEntity(customZombie);
        if (incrementZoneId != null) {
            incrementCachedZoneCount(incrementZoneId);
        }
        return true;
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        ItemStack held = player.getItemInHand(event.getHand());
        if (!(held.getItem() instanceof ZoneWandItem)) {
            return;
        }

        event.setCanceled(true);

        if (!event.getLevel().isClientSide()
                && event.getHand() == InteractionHand.MAIN_HAND
                && event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
            if (!player.hasPermissions(2)) {
                player.displayClientMessage(
                        Component.literal("No tienes permisos para usar esta varita.").withStyle(ChatFormatting.RED),
                        true
                );
                return;
            }
            if (player.isShiftKeyDown()) {
                ZoneWandItem.clearVertices(held, player);
            } else {
                ZoneWandItem.removeLastVertex(held, player);
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CZonesCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2)) {
            PacketHandler.sendZonesAndStatsToPlayer(serverPlayer, false);
        }
    }
}
