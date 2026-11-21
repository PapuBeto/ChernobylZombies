package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server packet that teleports an OP admin to the center of a polygonal SpawnZone.
 */
public class C2STeleportToZonePacket {

    private final UUID zoneId;

    public C2STeleportToZonePacket(UUID zoneId) {
        this.zoneId = zoneId;
    }

    public static void encode(C2STeleportToZonePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.zoneId);
    }

    public static C2STeleportToZonePacket decode(FriendlyByteBuf buf) {
        return new C2STeleportToZonePacket(buf.readUUID());
    }

    public static void handle(C2STeleportToZonePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        MinecraftServer server = sender.getServer();
        if (server == null) {
            ctx.setPacketHandled(true);
            return;
        }

        Optional<SpawnZone> zoneOpt = ZombieZonesSavedData.get(sender.serverLevel()).getZone(packet.zoneId);
        if (zoneOpt.isEmpty()) {
            ctx.setPacketHandled(true);
            return;
        }

        SpawnZone zone = zoneOpt.get();
        ServerLevel targetLevel = server.getLevel(zone.getDimension());
        if (targetLevel == null) {
            ctx.setPacketHandled(true);
            return;
        }

        BlockPos center = zone.getCenterPos();
        BlockPos safePos = ModCommonEvents.findSafeGroundSpawnPos(
                targetLevel,
                center.getX(),
                center.getZ(),
                zone.getMinY(),
                zone.getMaxY() + 8,
                center.getY(),
                true
        );
        if (safePos == null) {
            safePos = new BlockPos(center.getX(), zone.getMaxY() + 1, center.getZ());
        }

        sender.teleportTo(
                targetLevel,
                safePos.getX() + 0.5D,
                safePos.getY(),
                safePos.getZ() + 0.5D,
                sender.getYRot(),
                sender.getXRot()
        );
        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] Teletransportado a '" + zone.getName() + "'.").withStyle(ChatFormatting.AQUA),
                true
        );
        ctx.setPacketHandled(true);
    }
}
