package com.chernobylzombies.network.packet;

import com.chernobylzombies.client.ClientHooks;
import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieModelStats;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> Client packet synchronizing SpawnZones, live zombie counts, GlobalSpawnZone, SpawnerMode, and model stats.
 */
public class S2CSyncZonesAndStatsPacket {

    private final List<SpawnZone> zones;
    private final GlobalSpawnZone globalZone;
    private final Map<Integer, ZombieModelStats> modelStats;
    private final Map<UUID, Integer> zoneLiveCounts;
    private final SpawnerMode spawnerMode;
    private final boolean openScreen;

    public S2CSyncZonesAndStatsPacket(
            Collection<SpawnZone> zones,
            GlobalSpawnZone globalZone,
            Map<Integer, ZombieModelStats> modelStats,
            Map<UUID, Integer> zoneLiveCounts,
            SpawnerMode spawnerMode,
            boolean openScreen
    ) {
        this.zones = new ArrayList<>(zones);
        this.globalZone = globalZone != null ? globalZone : GlobalSpawnZone.defaults();
        this.modelStats = new LinkedHashMap<>(modelStats);
        this.zoneLiveCounts = zoneLiveCounts != null ? new LinkedHashMap<>(zoneLiveCounts) : new LinkedHashMap<>();
        this.spawnerMode = spawnerMode != null ? spawnerMode : SpawnerMode.INTERNAL_ZONES;
        this.openScreen = openScreen;
    }

    public static void encode(S2CSyncZonesAndStatsPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.zones.size());
        for (SpawnZone zone : packet.zones) {
            zone.toNetwork(buf);
        }

        packet.globalZone.toNetwork(buf);

        buf.writeVarInt(packet.modelStats.size());
        for (Map.Entry<Integer, ZombieModelStats> entry : packet.modelStats.entrySet()) {
            buf.writeVarInt(entry.getKey());
            entry.getValue().toNetwork(buf);
        }

        buf.writeVarInt(packet.zoneLiveCounts.size());
        for (Map.Entry<UUID, Integer> entry : packet.zoneLiveCounts.entrySet()) {
            buf.writeUUID(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }

        buf.writeEnum(packet.spawnerMode);
        buf.writeBoolean(packet.openScreen);
    }

    public static S2CSyncZonesAndStatsPacket decode(FriendlyByteBuf buf) {
        int zoneCount = buf.readVarInt();
        List<SpawnZone> zones = new ArrayList<>(zoneCount);
        for (int i = 0; i < zoneCount; i++) {
            zones.add(SpawnZone.fromNetwork(buf));
        }

        GlobalSpawnZone globalZone = GlobalSpawnZone.fromNetwork(buf);

        int statsCount = buf.readVarInt();
        Map<Integer, ZombieModelStats> statsMap = new LinkedHashMap<>();
        for (int i = 0; i < statsCount; i++) {
            statsMap.put(buf.readVarInt(), ZombieModelStats.fromNetwork(buf));
        }

        int countSize = buf.readVarInt();
        Map<UUID, Integer> liveCounts = new LinkedHashMap<>();
        for (int i = 0; i < countSize; i++) {
            liveCounts.put(buf.readUUID(), buf.readVarInt());
        }

        SpawnerMode mode = buf.readEnum(SpawnerMode.class);
        boolean openScreen = buf.readBoolean();
        return new S2CSyncZonesAndStatsPacket(zones, globalZone, statsMap, liveCounts, mode, openScreen);
    }

    public static void handle(S2CSyncZonesAndStatsPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.handleSync(
                packet.zones,
                packet.globalZone,
                packet.modelStats,
                packet.zoneLiveCounts,
                packet.spawnerMode,
                packet.openScreen
        ));
        ctx.setPacketHandled(true);
    }
}
