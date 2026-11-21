package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.network.PacketHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server packet to switch SpawnerMode (INTERNAL_ZONES / HYBRID / KUBEJS_ONLY).
 */
public class C2SSetSpawnerModePacket {

    private final SpawnerMode spawnerMode;

    public C2SSetSpawnerModePacket(SpawnerMode spawnerMode) {
        this.spawnerMode = spawnerMode != null ? spawnerMode : SpawnerMode.INTERNAL_ZONES;
    }

    public static void encode(C2SSetSpawnerModePacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.spawnerMode);
    }

    public static C2SSetSpawnerModePacket decode(FriendlyByteBuf buf) {
        return new C2SSetSpawnerModePacket(buf.readEnum(SpawnerMode.class));
    }

    public static void handle(C2SSetSpawnerModePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(sender.serverLevel());
        data.setSpawnerMode(packet.spawnerMode);
        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] Modo cambiado a: " + packet.spawnerMode.name())
                        .withStyle(ChatFormatting.AQUA),
                true
        );
        PacketHandler.syncToAllPlayers(sender.getServer());
        ctx.setPacketHandled(true);
    }
}
