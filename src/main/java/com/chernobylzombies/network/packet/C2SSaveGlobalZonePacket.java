package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.zone.GlobalSpawnZone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server packet to update the Global (Common) SpawnZone configuration.
 */
public class C2SSaveGlobalZonePacket {

    private final GlobalSpawnZone globalZone;

    public C2SSaveGlobalZonePacket(GlobalSpawnZone globalZone) {
        this.globalZone = globalZone != null ? globalZone : GlobalSpawnZone.defaults();
    }

    public static void encode(C2SSaveGlobalZonePacket packet, FriendlyByteBuf buf) {
        packet.globalZone.toNetwork(buf);
    }

    public static C2SSaveGlobalZonePacket decode(FriendlyByteBuf buf) {
        return new C2SSaveGlobalZonePacket(GlobalSpawnZone.fromNetwork(buf));
    }

    public static void handle(C2SSaveGlobalZonePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(sender.serverLevel());
        data.setGlobalZone(packet.globalZone);
        PacketHandler.syncToAllAdmins(sender.getServer());
        ctx.setPacketHandled(true);
    }
}
