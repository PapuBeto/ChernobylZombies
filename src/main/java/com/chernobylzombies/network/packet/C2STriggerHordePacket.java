package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.event.HordeSpawnQueue;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server packet that immediately spawns a 12-zombie horde on solid ground inside a polygonal SpawnZone.
 */
public class C2STriggerHordePacket {

    private static final int HORDE_SIZE = 12;

    private final UUID zoneId;

    public C2STriggerHordePacket(UUID zoneId) {
        this.zoneId = zoneId;
    }

    public static void encode(C2STriggerHordePacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.zoneId);
    }

    public static C2STriggerHordePacket decode(FriendlyByteBuf buf) {
        return new C2STriggerHordePacket(buf.readUUID());
    }

    public static void handle(C2STriggerHordePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
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
        if (zone.getVertices().size() < 3 || zone.getTotalWeight() <= 0) {
            sender.displayClientMessage(
                    Component.literal("[Chernobyl Zombies] La zona necesita al menos 3 vertices y un modelo con peso > 0.")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            ctx.setPacketHandled(true);
            return;
        }

        ServerLevel zoneLevel = server.getLevel(zone.getDimension());
        if (zoneLevel == null) {
            ctx.setPacketHandled(true);
            return;
        }

        List<ServerPlayer> playersInZone = zoneLevel.players().stream()
                .filter(p -> !p.isSpectator() && zone.contains(p.blockPosition()))
                .toList();

        RandomSource random = zoneLevel.getRandom();
        BlockPos fallbackCenter = zone.getCenterPos();
        int queued = 0;

        for (int i = 0; i < HORDE_SIZE; i++) {
            int modelId = zone.pickRandomModelId(random);
            if (modelId < 0) {
                break;
            }
            BlockPos anchor = playersInZone.isEmpty()
                    ? null
                    : playersInZone.get(random.nextInt(playersInZone.size())).blockPosition();

            if (HordeSpawnQueue.enqueue(HordeSpawnQueue.PendingZombieSpawn.forZone(
                    zoneLevel.dimension(),
                    zone.getId(),
                    anchor,
                    modelId,
                    true
            ))) {
                queued++;
            }
        }

        BlockPos soundAnchor = playersInZone.isEmpty() ? fallbackCenter : playersInZone.get(0).blockPosition();
        zoneLevel.playSound(null, soundAnchor, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 2.0F, 0.85F);

        final int totalQueued = queued;
        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] Horda detonada en '" + zone.getName() + "': "
                        + totalQueued + " zombies en cola escalonada (2/tick).")
                        .withStyle(ChatFormatting.RED),
                true
        );
        PacketHandler.syncToAllAdmins(server);
        ctx.setPacketHandled(true);
    }
}
