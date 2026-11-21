package com.chernobylzombies.event;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global server-side staggered spawn queue processed at the end of each ServerTickEvent.
 * Caps spawning to at most 2 zombies per tick (40/sec) to eliminate TPS spikes during hordes
 * and simultaneous multi-zone spawn cycles.
 */
public final class HordeSpawnQueue {

    public static final int MAX_SPAWNS_PER_TICK = 2;
    private static final int MAX_POPS_PER_TICK = 8;
    private static final int MAX_QUEUE_SIZE = 512;

    private static final Queue<PendingZombieSpawn> QUEUE = new ArrayDeque<>();
    private static final Map<UUID, Integer> PENDING_ZONE_COUNTS = new ConcurrentHashMap<>();

    private HordeSpawnQueue() {
    }

    public enum SpawnKind {
        ZONE_RANDOM,
        GLOBAL_NEAR_ORIGIN,
        EXACT_POSITION
    }

    public static final class PendingZombieSpawn {
        final SpawnKind kind;
        final ResourceKey<Level> dimension;
        @Nullable final UUID zoneId;
        @Nullable final BlockPos originPos;
        final int modelId;
        final boolean ignoreLight;
        final double exactX;
        final double exactY;
        final double exactZ;
        final float yRot;
        final float xRot;
        final float yHeadRot;
        final float yBodyRot;
        @Nullable final Component customName;
        final boolean customNameVisible;
        final boolean persistenceRequired;

        private PendingZombieSpawn(
                SpawnKind kind,
                ResourceKey<Level> dimension,
                @Nullable UUID zoneId,
                @Nullable BlockPos originPos,
                int modelId,
                boolean ignoreLight,
                double exactX,
                double exactY,
                double exactZ,
                float yRot,
                float xRot,
                float yHeadRot,
                float yBodyRot,
                @Nullable Component customName,
                boolean customNameVisible,
                boolean persistenceRequired
        ) {
            this.kind = kind;
            this.dimension = dimension;
            this.zoneId = zoneId;
            this.originPos = originPos != null ? originPos.immutable() : null;
            this.modelId = modelId;
            this.ignoreLight = ignoreLight;
            this.exactX = exactX;
            this.exactY = exactY;
            this.exactZ = exactZ;
            this.yRot = yRot;
            this.xRot = xRot;
            this.yHeadRot = yHeadRot;
            this.yBodyRot = yBodyRot;
            this.customName = customName;
            this.customNameVisible = customNameVisible;
            this.persistenceRequired = persistenceRequired;
        }

        public static PendingZombieSpawn forZone(
                ResourceKey<Level> dimension,
                UUID zoneId,
                @Nullable BlockPos anchorPos,
                int modelId,
                boolean ignoreLight
        ) {
            return new PendingZombieSpawn(
                    SpawnKind.ZONE_RANDOM, dimension, zoneId, anchorPos, modelId, ignoreLight,
                    0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F, 0.0F, null, false, false
            );
        }

        public static PendingZombieSpawn forGlobal(
                ResourceKey<Level> dimension,
                BlockPos originPos,
                int modelId,
                boolean ignoreLight
        ) {
            return new PendingZombieSpawn(
                    SpawnKind.GLOBAL_NEAR_ORIGIN, dimension, null, originPos, modelId, ignoreLight,
                    0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F, 0.0F, null, false, false
            );
        }

        public static PendingZombieSpawn forExact(
                ResourceKey<Level> dimension,
                @Nullable UUID zoneId,
                double x,
                double y,
                double z,
                float yRot,
                float xRot,
                float yHeadRot,
                float yBodyRot,
                int modelId,
                @Nullable Entity sourceEntity
        ) {
            Component name = (sourceEntity != null && sourceEntity.hasCustomName()) ? sourceEntity.getCustomName() : null;
            boolean nameVisible = sourceEntity != null && sourceEntity.isCustomNameVisible();
            boolean persistent = sourceEntity instanceof Mob mob && mob.isPersistenceRequired();
            return new PendingZombieSpawn(
                    SpawnKind.EXACT_POSITION, dimension, zoneId, null, modelId, true,
                    x, y, z, yRot, xRot, yHeadRot, yBodyRot, name, nameVisible, persistent
            );
        }
    }

    public static synchronized boolean enqueue(PendingZombieSpawn task) {
        if (task == null || QUEUE.size() >= MAX_QUEUE_SIZE) {
            return false;
        }
        QUEUE.offer(task);
        if (task.zoneId != null) {
            PENDING_ZONE_COUNTS.merge(task.zoneId, 1, Integer::sum);
        }
        return true;
    }

    public static synchronized int getPendingCount() {
        return QUEUE.size();
    }

    public static int getPendingCountForZone(@Nullable UUID zoneId) {
        return zoneId == null ? 0 : PENDING_ZONE_COUNTS.getOrDefault(zoneId, 0);
    }

    public static synchronized void clear() {
        QUEUE.clear();
        PENDING_ZONE_COUNTS.clear();
    }

    /**
     * Drains up to MAX_SPAWNS_PER_TICK (2) queued zombie spawns at the end of a server tick.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || QUEUE.isEmpty()) {
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
        int spawned = 0;
        int pops = 0;
        boolean drainedAny = false;

        while (spawned < MAX_SPAWNS_PER_TICK && pops < MAX_POPS_PER_TICK) {
            PendingZombieSpawn task;
            synchronized (HordeSpawnQueue.class) {
                task = QUEUE.poll();
                if (task != null && task.zoneId != null) {
                    PENDING_ZONE_COUNTS.computeIfPresent(task.zoneId, (k, v) -> v > 1 ? v - 1 : null);
                }
            }
            if (task == null) {
                break;
            }
            pops++;
            if (processSpawnTask(server, data, task)) {
                spawned++;
                drainedAny = true;
            }
        }

        if (drainedAny && QUEUE.isEmpty()) {
            PacketHandler.syncToAllAdmins(server);
        }
    }

    private static boolean processSpawnTask(MinecraftServer server, ZombieZonesSavedData data, PendingZombieSpawn task) {
        ServerLevel level = server.getLevel(task.dimension);
        if (level == null) {
            return false;
        }
        RandomSource random = level.getRandom();

        return switch (task.kind) {
            case EXACT_POSITION -> ModCommonEvents.spawnApprovedZombie(
                    level,
                    task.customName,
                    task.customNameVisible,
                    task.persistenceRequired,
                    task.exactX,
                    task.exactY,
                    task.exactZ,
                    task.yRot,
                    task.xRot,
                    task.yHeadRot,
                    task.yBodyRot,
                    task.modelId,
                    task.zoneId
            );
            case ZONE_RANDOM -> {
                if (task.zoneId == null) {
                    yield false;
                }
                Optional<SpawnZone> zoneOpt = data.getZone(task.zoneId);
                if (zoneOpt.isEmpty()) {
                    yield false;
                }
                SpawnZone zone = zoneOpt.get();
                BlockPos pos = ModCommonEvents.findRandomSafeSpawnInZone(level, zone, task.originPos, random, task.ignoreLight);
                if (pos == null && task.originPos != null) {
                    pos = ModCommonEvents.findRandomSafeSpawnInZone(level, zone, null, random, task.ignoreLight);
                }
                if (pos == null) {
                    yield false;
                }
                float yaw = random.nextFloat() * 360.0F;
                yield ModCommonEvents.spawnApprovedZombie(
                        level,
                        null,
                        false,
                        false,
                        pos.getX() + 0.5D,
                        pos.getY(),
                        pos.getZ() + 0.5D,
                        yaw,
                        0.0F,
                        yaw,
                        yaw,
                        task.modelId,
                        zone.getId()
                );
            }
            case GLOBAL_NEAR_ORIGIN -> {
                if (task.originPos == null) {
                    yield false;
                }
                GlobalSpawnZone globalZone = data.getGlobalZone();
                BlockPos pos = ModCommonEvents.findNearbySafeGlobalPos(
                        level,
                        data,
                        task.originPos,
                        task.ignoreLight || globalZone.isIgnoreLightLevel(),
                        random
                );
                if (pos == null) {
                    yield false;
                }
                float yaw = random.nextFloat() * 360.0F;
                yield ModCommonEvents.spawnApprovedZombie(
                        level,
                        null,
                        false,
                        false,
                        pos.getX() + 0.5D,
                        pos.getY(),
                        pos.getZ() + 0.5D,
                        yaw,
                        0.0F,
                        yaw,
                        yaw,
                        task.modelId,
                        null
                );
            }
        };
    }
}
