package com.chernobylzombies.network;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.network.packet.C2SDeleteZonePacket;
import com.chernobylzombies.network.packet.C2SDuplicateZoneConfigPacket;
import com.chernobylzombies.network.packet.C2SPurgeZonePacket;
import com.chernobylzombies.network.packet.C2SReloadConfigPacket;
import com.chernobylzombies.network.packet.C2SSaveGlobalZonePacket;
import com.chernobylzombies.network.packet.C2SSaveZonePacket;
import com.chernobylzombies.network.packet.C2SSetSpawnerModePacket;
import com.chernobylzombies.network.packet.C2SSpawnTestZombiePacket;
import com.chernobylzombies.network.packet.C2STeleportToZonePacket;
import com.chernobylzombies.network.packet.C2STriggerHordePacket;
import com.chernobylzombies.network.packet.C2SUpdateModelStatsPacket;
import com.chernobylzombies.network.packet.C2SWandClearPacket;
import com.chernobylzombies.network.packet.S2CSyncZonesAndStatsPacket;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central Forge SimpleChannel handler for Chernobyl Zombies client-server packets.
 */
public final class PacketHandler {

    private static final String PROTOCOL_VERSION = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ChernobylZombiesMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;
    private static boolean registered = false;

    private PacketHandler() {
    }

    private static int nextId() {
        return packetId++;
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        CHANNEL.messageBuilder(S2CSyncZonesAndStatsPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CSyncZonesAndStatsPacket::encode)
                .decoder(S2CSyncZonesAndStatsPacket::decode)
                .consumerMainThread(S2CSyncZonesAndStatsPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SSaveZonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSaveZonePacket::encode)
                .decoder(C2SSaveZonePacket::decode)
                .consumerMainThread(C2SSaveZonePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SSaveGlobalZonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSaveGlobalZonePacket::encode)
                .decoder(C2SSaveGlobalZonePacket::decode)
                .consumerMainThread(C2SSaveGlobalZonePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SDeleteZonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SDeleteZonePacket::encode)
                .decoder(C2SDeleteZonePacket::decode)
                .consumerMainThread(C2SDeleteZonePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SUpdateModelStatsPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SUpdateModelStatsPacket::encode)
                .decoder(C2SUpdateModelStatsPacket::decode)
                .consumerMainThread(C2SUpdateModelStatsPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2STeleportToZonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2STeleportToZonePacket::encode)
                .decoder(C2STeleportToZonePacket::decode)
                .consumerMainThread(C2STeleportToZonePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SPurgeZonePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SPurgeZonePacket::encode)
                .decoder(C2SPurgeZonePacket::decode)
                .consumerMainThread(C2SPurgeZonePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2STriggerHordePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2STriggerHordePacket::encode)
                .decoder(C2STriggerHordePacket::decode)
                .consumerMainThread(C2STriggerHordePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SSpawnTestZombiePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSpawnTestZombiePacket::encode)
                .decoder(C2SSpawnTestZombiePacket::decode)
                .consumerMainThread(C2SSpawnTestZombiePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SDuplicateZoneConfigPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SDuplicateZoneConfigPacket::encode)
                .decoder(C2SDuplicateZoneConfigPacket::decode)
                .consumerMainThread(C2SDuplicateZoneConfigPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SWandClearPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SWandClearPacket::encode)
                .decoder(C2SWandClearPacket::decode)
                .consumerMainThread(C2SWandClearPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SSetSpawnerModePacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSetSpawnerModePacket::encode)
                .decoder(C2SSetSpawnerModePacket::decode)
                .consumerMainThread(C2SSetSpawnerModePacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SReloadConfigPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SReloadConfigPacket::encode)
                .decoder(C2SReloadConfigPacket::decode)
                .consumerMainThread(C2SReloadConfigPacket::handle)
                .add();
    }

    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendZonesAndStatsToPlayer(ServerPlayer player, boolean openScreen) {
        ZombieZonesSavedData data = ZombieZonesSavedData.get(player.serverLevel());
        sendToPlayer(player, buildSyncPacket(data, openScreen));
    }

    public static void syncToAllAdmins(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
        S2CSyncZonesAndStatsPacket packet = buildSyncPacket(data, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) {
                sendToPlayer(player, packet);
            }
        }
    }

    public static void syncToAllPlayers(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
        S2CSyncZonesAndStatsPacket packet = buildSyncPacket(data, false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(player, packet);
        }
    }

    private static S2CSyncZonesAndStatsPacket buildSyncPacket(ZombieZonesSavedData data, boolean openScreen) {
        Map<UUID, Integer> liveCounts = new LinkedHashMap<>();
        for (SpawnZone zone : data.getZones()) {
            liveCounts.put(zone.getId(), ModCommonEvents.getCachedZoneCount(zone.getId()));
        }
        return new S2CSyncZonesAndStatsPacket(
                data.getZones(),
                data.getGlobalZone(),
                data.getAllModelStats(),
                liveCounts,
                data.getSpawnerMode(),
                openScreen
        );
    }
}
