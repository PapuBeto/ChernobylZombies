package com.chernobylzombies.client;

import com.chernobylzombies.client.gui.ZoneManagerScreen;
import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieModelStats;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-only UI hooks invoked strictly via DistExecutor after server synchronization packets arrive.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void handleSync(
            List<SpawnZone> zones,
            GlobalSpawnZone globalZone,
            Map<Integer, ZombieModelStats> modelStats,
            Map<UUID, Integer> zoneLiveCounts,
            SpawnerMode spawnerMode,
            boolean openScreen
    ) {
        ClientZoneCache.update(zones, globalZone, modelStats, zoneLiveCounts, spawnerMode);
        if (openScreen) {
            openZoneManagerScreen();
        } else {
            onZonesSynced();
        }
    }

    public static void openZoneManagerScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ZoneManagerScreen());
    }

    public static void onZonesSynced() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ZoneManagerScreen screen) {
            screen.onServerCacheUpdated();
        }
    }
}
