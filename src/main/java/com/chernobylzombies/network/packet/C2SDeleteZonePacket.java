package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.network.PacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server packet to delete a SpawnZone by UUID.
 */
public class C2SDeleteZonePacket {

    private final UUID zoneId;

    public C2SDeleteZonePacket(UUID zoneId) {
        this.zoneId = zoneId;
    }

    public static void encode(C2SDeleteZonePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.zoneId);
    }

    public static C2SDeleteZonePacket decode(FriendlyByteBuf buf) {
        return new C2SDeleteZonePacket(buf.readUUID());
    }

    public static void handle(C2SDeleteZonePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(sender.serverLevel());
        if (data.removeZone(packet.zoneId)) {
            PacketHandler.syncToAllAdmins(sender.getServer());
        }
        ctx.setPacketHandled(true);
    }
}
