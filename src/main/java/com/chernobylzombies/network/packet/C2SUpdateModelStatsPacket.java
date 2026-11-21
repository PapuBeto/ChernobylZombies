package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieModelStats;
import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.network.PacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client -> Server packet to update base stats for one or more zombie MODEL_IDs.
 */
public class C2SUpdateModelStatsPacket {

    private final Map<Integer, ZombieModelStats> updatedStats;

    public C2SUpdateModelStatsPacket(int modelId, ZombieModelStats stats) {
        this.updatedStats = Map.of(modelId, stats);
    }

    public C2SUpdateModelStatsPacket(Map<Integer, ZombieModelStats> updatedStats) {
        this.updatedStats = new LinkedHashMap<>(updatedStats);
    }

    public static void encode(C2SUpdateModelStatsPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.updatedStats.size());
        for (Map.Entry<Integer, ZombieModelStats> entry : packet.updatedStats.entrySet()) {
            buf.writeVarInt(entry.getKey());
            entry.getValue().toNetwork(buf);
        }
    }

    public static C2SUpdateModelStatsPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<Integer, ZombieModelStats> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            map.put(buf.readVarInt(), ZombieModelStats.fromNetwork(buf));
        }
        return new C2SUpdateModelStatsPacket(map);
    }

    public static void handle(C2SUpdateModelStatsPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        MinecraftServer server = sender.getServer();
        ZombieZonesSavedData data = ZombieZonesSavedData.get(sender.serverLevel());
        data.setAllModelStats(packet.updatedStats);

        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof CustomZombieEntity zombie
                            && zombie.isAlive()
                            && !zombie.hasCustomStats()
                            && packet.updatedStats.containsKey(zombie.getModelId())) {
                        zombie.applyModelStats(true);
                    }
                }
            }
            PacketHandler.syncToAllAdmins(server);
        }

        ctx.setPacketHandled(true);
    }
}
