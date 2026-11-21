package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server packet that removes all alive CustomZombieEntity instances inside a SpawnZone.
 */
public class C2SPurgeZonePacket {

    private final UUID zoneId;

    public C2SPurgeZonePacket(UUID zoneId) {
        this.zoneId = zoneId;
    }

    public static void encode(C2SPurgeZonePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.zoneId);
    }

    public static C2SPurgeZonePacket decode(FriendlyByteBuf buf) {
        return new C2SPurgeZonePacket(buf.readUUID());
    }

    public static void handle(C2SPurgeZonePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
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
        ServerLevel zoneLevel = server.getLevel(zone.getDimension());
        if (zoneLevel == null) {
            ctx.setPacketHandled(true);
            return;
        }

        List<CustomZombieEntity> targets = zoneLevel.getEntitiesOfClass(
                CustomZombieEntity.class,
                zone.getBoundingBox(),
                z -> z.isAlive() && zone.contains(z.blockPosition())
        );

        int removed = 0;
        for (CustomZombieEntity zombie : targets) {
            zombie.discard();
            removed++;
        }

        final int count = removed;
        ModCommonEvents.setCachedZoneCount(zone.getId(), 0);
        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] Purgados " + count + " zombies en '" + zone.getName() + "'.")
                        .withStyle(ChatFormatting.YELLOW),
                true
        );
        PacketHandler.syncToAllAdmins(server);
        ctx.setPacketHandled(true);
    }
}
