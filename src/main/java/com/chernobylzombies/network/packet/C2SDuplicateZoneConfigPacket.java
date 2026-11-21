package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server packet that clones rules, weights, limits, and schedule from one SpawnZone to another.
 */
public class C2SDuplicateZoneConfigPacket {

    private final UUID sourceZoneId;
    private final UUID targetZoneId;

    public C2SDuplicateZoneConfigPacket(UUID sourceZoneId, UUID targetZoneId) {
        this.sourceZoneId = sourceZoneId;
        this.targetZoneId = targetZoneId;
    }

    public static void encode(C2SDuplicateZoneConfigPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.sourceZoneId);
        buf.writeUUID(packet.targetZoneId);
    }

    public static C2SDuplicateZoneConfigPacket decode(FriendlyByteBuf buf) {
        return new C2SDuplicateZoneConfigPacket(buf.readUUID(), buf.readUUID());
    }

    public static void handle(C2SDuplicateZoneConfigPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(sender.serverLevel());
        Optional<SpawnZone> sourceOpt = data.getZone(packet.sourceZoneId);
        Optional<SpawnZone> targetOpt = data.getZone(packet.targetZoneId);
        if (sourceOpt.isEmpty() || targetOpt.isEmpty()) {
            ctx.setPacketHandled(true);
            return;
        }

        SpawnZone source = sourceOpt.get();
        SpawnZone target = targetOpt.get();
        target.copyRulesFrom(source);
        data.setDirty();

        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] Configuracion copiada de '" + source.getName() + "' a '" + target.getName() + "'.")
                        .withStyle(ChatFormatting.GREEN),
                true
        );
        PacketHandler.syncToAllAdmins(sender.getServer());
        ctx.setPacketHandled(true);
    }
}
