package com.chernobylzombies.command;

import com.chernobylzombies.data.ServerConfigManager;
import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieZonesSavedData;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.event.HordeSpawnQueue;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.item.ZoneWandItem;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.registry.ModItems;
import com.chernobylzombies.zone.SpawnZone;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Registers the /czones admin command (OP level >= 2).
 */
public final class CZonesCommand {

    private static final SuggestionProvider<CommandSourceStack> MODE_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    new String[]{
                            SpawnerMode.INTERNAL_ZONES.name(),
                            SpawnerMode.HYBRID.name(),
                            SpawnerMode.KUBEJS_ONLY.name()
                    },
                    builder
            );

    private CZonesCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("czones")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("wand")
                        .executes(ctx -> giveWand(ctx.getSource())))
                .then(Commands.literal("gui")
                        .executes(ctx -> openGui(ctx.getSource())))
                .then(Commands.literal("reload")
                        .executes(ctx -> reloadConfig(ctx.getSource())))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests(MODE_SUGGESTIONS)
                                .executes(ctx -> setMode(ctx.getSource(), StringArgumentType.getString(ctx, "mode")))))
                .then(Commands.literal("stats")
                        .executes(ctx -> showStats(ctx.getSource())))
                .then(Commands.literal("setpoly")
                        .then(Commands.argument("target", StringArgumentType.greedyString())
                                .executes(ctx -> redefineZonePolygon(ctx.getSource(), StringArgumentType.getString(ctx, "target"))))));
    }

    private static int giveWand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando solo puede ser usado por un jugador."));
            return 0;
        }

        ItemStack wand = new ItemStack(ModItems.ZONE_WAND.get());
        if (!player.getInventory().add(wand)) {
            player.drop(wand, false);
        }

        source.sendSuccess(
                () -> Component.literal("[Chernobyl Zombies] Varita poligonal entregada. Click Der = Anadir Punto, Click Izq = Deshacer, Shift+Click Der = Panel.")
                        .withStyle(ChatFormatting.GREEN),
                false
        );
        return 1;
    }

    private static int openGui(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando solo puede ser usado por un jugador."));
            return 0;
        }

        PacketHandler.sendZonesAndStatsToPlayer(player, true);
        return 1;
    }

    private static int reloadConfig(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        String result = ServerConfigManager.reloadFromDisk(server);
        source.sendSuccess(
                () -> Component.literal("[Chernobyl Zombies] " + result).withStyle(ChatFormatting.GREEN),
                true
        );
        return 1;
    }

    private static int setMode(CommandSourceStack source, String rawMode) {
        SpawnerMode parsed = null;
        for (SpawnerMode mode : SpawnerMode.values()) {
            if (mode.name().equalsIgnoreCase(rawMode.trim())) {
                parsed = mode;
                break;
            }
        }
        if (parsed == null) {
            source.sendFailure(Component.literal(
                    "[Chernobyl Zombies] Modo invalido '" + rawMode + "'. Usa: INTERNAL_ZONES, HYBRID o KUBEJS_ONLY."
            ));
            return 0;
        }

        MinecraftServer server = source.getServer();
        ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
        data.setSpawnerMode(parsed);
        PacketHandler.syncToAllPlayers(server);

        final SpawnerMode finalMode = parsed;
        source.sendSuccess(
                () -> Component.literal("[Chernobyl Zombies] spawnerMode actualizado a: " + finalMode.name()
                        + " (" + finalMode.getLabel() + ").").withStyle(ChatFormatting.AQUA),
                true
        );
        return 1;
    }

    private static int redefineZonePolygon(CommandSourceStack source, String target) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando solo puede ser usado por un jugador."));
            return 0;
        }

        ItemStack wand = ZoneWandItem.findWand(player);
        if (wand.isEmpty()) {
            source.sendFailure(Component.literal("[Chernobyl Zombies] Necesitas tener la varita de zonas en tu inventario."));
            return 0;
        }

        List<BlockPos> vertices = ZoneWandItem.getVertices(wand);
        if (vertices.size() < 3) {
            source.sendFailure(Component.literal("[Chernobyl Zombies] Marca al menos 3 puntos con la varita primero."));
            return 0;
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(player.serverLevel());
        Optional<SpawnZone> zoneOpt = data.findZoneByNameOrId(target);
        if (zoneOpt.isEmpty()) {
            source.sendFailure(Component.literal("[Chernobyl Zombies] No se encontro ninguna zona con nombre o ID: " + target));
            return 0;
        }

        SpawnZone zone = zoneOpt.get();
        ResourceKey<Level> wandDim = ZoneWandItem.getDimension(wand).orElse(player.level().dimension());
        zone.setDimension(wandDim);
        zone.setVertices(vertices);
        zone.setYRange(ZoneWandItem.computeDefaultMinY(vertices), ZoneWandItem.computeDefaultMaxY(vertices));
        data.setDirty();
        PacketHandler.syncToAllAdmins(player.getServer());

        source.sendSuccess(
                () -> Component.literal("[Chernobyl Zombies] Forma de '" + zone.getName() + "' actualizada con "
                        + vertices.size() + " vertices (Y: " + zone.getMinY() + " a " + zone.getMaxY() + ").")
                        .withStyle(ChatFormatting.GREEN),
                false
        );
        return 1;
    }

    private static int showStats(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        int totalAlive = 0;
        int idleThrottled = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CustomZombieEntity zombie && zombie.isAlive()) {
                    totalAlive++;
                    if (zombie.isIdleAiThrottled()) {
                        idleThrottled++;
                    }
                }
            }
        }

        int queuedHorde = HordeSpawnQueue.getPendingCount();
        double avgMs = ModCommonEvents.getAverageTickMs();
        String formattedMs = String.format(Locale.ROOT, "%.4f", avgMs);

        final int aliveFinal = totalAlive;
        final int throttledFinal = idleThrottled;
        source.sendSuccess(
                () -> Component.literal("[Chernobyl Zombies Stats] Zombies vivos: " + aliveFinal
                        + " (IA idle: " + throttledFinal + ")"
                        + " | Cola de horda: " + queuedHorde
                        + " | Tiempo promedio: " + formattedMs + " ms/tick")
                        .withStyle(ChatFormatting.GOLD),
                false
        );
        return 1;
    }
}
