package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.item.ZoneWandItem;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Client -> Server packet to create or update a polygonal SpawnZone.
 */
public class C2SSaveZonePacket {

    private final SpawnZone zone;
    private final boolean useWandPositions;

    public C2SSaveZonePacket(SpawnZone zone, boolean useWandPositions) {
        this.zone = zone;
        this.useWandPositions = useWandPositions;
    }

    public static void encode(C2SSaveZonePacket packet, FriendlyByteBuf buf) {
        packet.zone.toNetwork(buf);
        buf.writeBoolean(packet.useWandPositions);
    }

    public static C2SSaveZonePacket decode(FriendlyByteBuf buf) {
        SpawnZone zone = SpawnZone.fromNetwork(buf);
        boolean useWand = buf.readBoolean();
        return new C2SSaveZonePacket(zone, useWand);
    }

    public static void handle(C2SSaveZonePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        SpawnZone zoneToSave = packet.zone;
        if (packet.useWandPositions) {
            ItemStack wand = ZoneWandItem.findWand(sender);
            List<BlockPos> vertices = wand.isEmpty() ? List.of() : ZoneWandItem.getVertices(wand);
            if (vertices.size() < 3) {
                sender.displayClientMessage(
                        Component.literal("[Chernobyl Zombies] Marca al menos 3 puntos con la varita primero.")
                                .withStyle(ChatFormatting.RED),
                        false
                );
                ctx.setPacketHandled(true);
                return;
            }
            ResourceKey<Level> dim = ZoneWandItem.getDimension(wand).orElse(sender.level().dimension());
            int minY = ZoneWandItem.computeDefaultMinY(vertices);
            int maxY = ZoneWandItem.computeDefaultMaxY(vertices);
            zoneToSave = zoneToSave.copyWithPolygon(vertices, minY, maxY, dim);
        } else if (zoneToSave.getVertices().size() < 3) {
            sender.displayClientMessage(
                    Component.literal("[Chernobyl Zombies] Una zona poligonal requiere al menos 3 vertices.")
                            .withStyle(ChatFormatting.RED),
                    false
            );
            ctx.setPacketHandled(true);
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(sender.serverLevel());
        data.putZone(zoneToSave);
        PacketHandler.syncToAllAdmins(sender.getServer());
        ctx.setPacketHandled(true);
    }
}
