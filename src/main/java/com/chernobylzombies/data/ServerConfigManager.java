package com.chernobylzombies.data;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.registry.ZombieVariantRegistry;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import com.chernobylzombies.zone.ZoneTimeCondition;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Server-safe external JSON config, import folder loader, and KubeJS documentation generator
 * under config/chernobylzombies/. Has zero client dependencies.
 */
public final class ServerConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String CONFIG_DIR_NAME = "chernobylzombies";
    public static final String MAIN_CONFIG_FILE = "chernobylzombies_config.json";
    public static final String BACKUP_CONFIG_FILE = "chernobylzombies_backup.json";
    public static final String IMPORT_SUBDIR = "import";
    public static final String EXAMPLES_FILE = "KUBEJS_EXAMPLES.txt";

    private ServerConfigManager() {
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_DIR_NAME);
    }

    public static Path getMainConfigPath() {
        return getConfigDir().resolve(MAIN_CONFIG_FILE);
    }

    public static Path getBackupConfigPath() {
        return getConfigDir().resolve(BACKUP_CONFIG_FILE);
    }

    public static Path getImportDir() {
        return getConfigDir().resolve(IMPORT_SUBDIR);
    }

    public static Path getExamplesPath() {
        return getConfigDir().resolve(EXAMPLES_FILE);
    }

    public static void ensureFilesExist(ZombieZonesSavedData data) {
        try {
            Files.createDirectories(getConfigDir());
            Files.createDirectories(getImportDir());

            if (!Files.exists(getMainConfigPath())) {
                writeConfigFile(
                        getMainConfigPath(),
                        data.getSpawnerMode(),
                        data.getZones(),
                        data.getGlobalZone(),
                        data.getAllModelStats()
                );
            }
            if (!Files.exists(getExamplesPath())) {
                Files.writeString(getExamplesPath(), buildKubeJsExamplesText(), StandardCharsets.UTF_8);
            } else {
                String existing = Files.readString(getExamplesPath(), StandardCharsets.UTF_8);
                if (!existing.contains("zombie_hombre_1") || !existing.contains("/czones stats")) {
                    Files.writeString(getExamplesPath(), buildKubeJsExamplesText(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            ChernobylZombiesMod.LOGGER.error("Failed to initialize config/chernobylzombies files", e);
        }
    }

    public static String exportSnapshot(
            SpawnerMode mode,
            Collection<SpawnZone> zones,
            GlobalSpawnZone globalZone,
            Map<Integer, ZombieModelStats> modelStats
    ) {
        try {
            Files.createDirectories(getConfigDir());
            Files.createDirectories(getImportDir());
            writeConfigFile(getMainConfigPath(), mode, zones, globalZone, modelStats);
            writeConfigFile(getBackupConfigPath(), mode, zones, globalZone, modelStats);
            return "Exportado: config/" + CONFIG_DIR_NAME + "/" + MAIN_CONFIG_FILE;
        } catch (IOException e) {
            ChernobylZombiesMod.LOGGER.error("Failed to write chernobylzombies_config.json", e);
            return "Error exportando JSON: " + e.getMessage();
        }
    }

    private static void writeConfigFile(
            Path path,
            SpawnerMode mode,
            Collection<SpawnZone> zones,
            GlobalSpawnZone globalZone,
            Map<Integer, ZombieModelStats> modelStats
    ) throws IOException {
        ConfigPayload payload = new ConfigPayload();
        payload.spawnerMode = mode != null ? mode.name() : SpawnerMode.INTERNAL_ZONES.name();
        payload.globalZone = GlobalZoneDto.fromGlobal(globalZone != null ? globalZone : GlobalSpawnZone.defaults());
        for (SpawnZone zone : zones) {
            payload.zones.add(ZoneDto.fromZone(zone));
        }
        int total = ZombieVariantRegistry.getTotalVariants();
        for (int i = 0; i < total; i++) {
            ZombieModelStats stats = modelStats != null ? modelStats.getOrDefault(i, ZombieModelStats.defaults()) : ZombieModelStats.defaults();
            payload.modelStats.put(i, ModelStatsDto.fromStats(stats));
        }
        Files.writeString(path, GSON.toJson(payload), StandardCharsets.UTF_8);
    }

    /**
     * Reloads config/chernobylzombies/chernobylzombies_config.json and any extra *.json inside config/chernobylzombies/import/,
     * persists into ZombieZonesSavedData, updates living CustomZombieEntity stats, and syncs connected players.
     */
    public static String reloadFromDisk(MinecraftServer server) {
        if (server == null) {
            return "Servidor no disponible.";
        }

        ZombieZonesSavedData data = ZombieZonesSavedData.get(server.overworld());
        ensureFilesExist(data);

        SpawnerMode loadedMode = data.getSpawnerMode();
        Map<UUID, SpawnZone> mergedZones = new LinkedHashMap<>();
        for (SpawnZone z : data.getZones()) {
            mergedZones.put(z.getId(), z);
        }
        GlobalSpawnZone loadedGlobal = data.getGlobalZone();
        Map<Integer, ZombieModelStats> mergedStats = new LinkedHashMap<>(data.getAllModelStats());

        List<Path> jsonFiles = new ArrayList<>();
        if (Files.exists(getMainConfigPath())) {
            jsonFiles.add(getMainConfigPath());
            // When main config exists, replace zones list from main config cleanly
            mergedZones.clear();
        } else if (Files.exists(getBackupConfigPath())) {
            jsonFiles.add(getBackupConfigPath());
            mergedZones.clear();
        }
        if (Files.exists(getImportDir())) {
            try (Stream<Path> stream = Files.list(getImportDir())) {
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(jsonFiles::add);
            } catch (IOException e) {
                ChernobylZombiesMod.LOGGER.error("Error listing config/chernobylzombies/import", e);
            }
        }

        int filesLoaded = 0;
        for (Path file : jsonFiles) {
            try {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                ConfigPayload payload = GSON.fromJson(raw, ConfigPayload.class);
                if (payload == null) {
                    continue;
                }
                if (payload.spawnerMode != null && !payload.spawnerMode.isBlank()) {
                    loadedMode = SpawnerMode.fromName(payload.spawnerMode);
                }
                if (payload.globalZone != null) {
                    loadedGlobal = payload.globalZone.toGlobal();
                }
                if (payload.zones != null) {
                    for (ZoneDto dto : payload.zones) {
                        if (dto == null) {
                            continue;
                        }
                        SpawnZone zone = dto.toZone();
                        if (zone.getVertices().size() >= 3) {
                            mergedZones.put(zone.getId(), zone);
                        }
                    }
                }
                if (payload.modelStats != null) {
                    for (Map.Entry<Integer, ModelStatsDto> entry : payload.modelStats.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            mergedStats.put(entry.getKey(), entry.getValue().toStats());
                        }
                    }
                }
                filesLoaded++;
            } catch (Exception e) {
                ChernobylZombiesMod.LOGGER.error("Error parsing JSON config file {}", file, e);
                return "Error leyendo " + file.getFileName() + ": " + e.getMessage();
            }
        }

        data.replaceAllFromConfig(loadedMode, new ArrayList<>(mergedZones.values()), loadedGlobal, mergedStats);

        // Apply updated stats to alive zombies (skipping entities with CustomStats: 1b)
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CustomZombieEntity zombie && zombie.isAlive() && !zombie.hasCustomStats()) {
                    zombie.applyModelStats(true);
                }
            }
        }

        ModCommonEvents.refreshZoneCountCache(server, data);
        PacketHandler.syncToAllPlayers(server);
        return "Recargados " + filesLoaded + " JSON (" + mergedZones.size() + " zonas, modo " + loadedMode.name() + ").";
    }

    private static String buildKubeJsExamplesText() {
        return """
                ========================================================================
                Chernobyl Zombies (Forge 1.20.1) - Guia e Integracion Nativa con KubeJS
                ========================================================================

                1. ENTIDADES INDIVIDUALES (ModelId 0 - 9) Y TEXTURAS (TextureId):
                   - 0: chernobylzombies:zombie_hombre_1 ("Hombre 1" - Civil)      -> TextureId: 0, 1, 2
                   - 1: chernobylzombies:zombie_hombre_2 ("Hombre 2" - Hoodie)     -> TextureId: 0, 1, 2
                   - 2: chernobylzombies:zombie_hombre_3 ("Hombre 3" - McFly)      -> TextureId: 0, 1, 2
                   - 3: chernobylzombies:zombie_hombre_4 ("Hombre 4" - Trabajador) -> TextureId: 0, 1, 2
                   - 4: chernobylzombies:zombie_hombre_5 ("Hombre 5" - R.P.D.)     -> TextureId: 0
                   - 5: chernobylzombies:zombie_mujer_1  ("Mujer 1"  - Civil)      -> TextureId: 0, 1, 2
                   - 6: chernobylzombies:zombie_mujer_2  ("Mujer 2"  - Militar)    -> TextureId: 0, 1, 2
                   - 7: chernobylzombies:zombie_mujer_3  ("Mujer 3"  - Mutante)    -> TextureId: 0, 1, 2
                   - 8: chernobylzombies:zombie_mujer_4  ("Mujer 4"  - Mochilera)  -> TextureId: 0, 1, 2
                   - 9: chernobylzombies:zombie_mujer_5  ("Mujer 5"  - Retorcida)  -> TextureId: 0, 1, 2
                   (Alias legado multi-modelo: chernobylzombies:custom_zombie)

                2. ETIQUETAS NBT SOPORTADAS:
                   - ModelId (int 0-9, automatico en las 10 entidades especificas)
                   - TextureId (int 0-2)
                   - VoiceProfile (String): "MALE", "FEMALE", "MUTANT", "VANILLA"
                   - SkipZoneCheck (1b/0b): 1b evita que sea cancelado fuera de zonas
                   - CustomStats (1b/0b): 1b evita que el mod sobrescriba atributos de KubeJS

                3. MODOS DE OPERACION (spawnerMode en chernobylzombies_config.json o /czones mode):
                   - INTERNAL_ZONES : Zonas poligonales y spawner propio del mod.
                   - HYBRID         : Zonas del mod + permite cualquier zombie invocado por KubeJS.
                   - KUBEJS_ONLY    : Desactiva por completo el spawner/listeners del mod (0 CPU en spawns).

                ------------------------------------------------------------------------
                EJEMPLO A (KubeJS server_scripts): Invocar entidad especifica con NBT
                ------------------------------------------------------------------------
                const ZOMBIE_IDS = [
                    'chernobylzombies:zombie_hombre_1',
                    'chernobylzombies:zombie_hombre_2',
                    'chernobylzombies:zombie_hombre_3',
                    'chernobylzombies:zombie_hombre_4',
                    'chernobylzombies:zombie_hombre_5',
                    'chernobylzombies:zombie_mujer_1',
                    'chernobylzombies:zombie_mujer_2',
                    'chernobylzombies:zombie_mujer_3',
                    'chernobylzombies:zombie_mujer_4',
                    'chernobylzombies:zombie_mujer_5'
                ];

                function spawnChernobylZombie(level, x, y, z, modelId, textureId, voice) {
                    const entityId = ZOMBIE_IDS[modelId] || ZOMBIE_IDS[0];
                    const zmb = level.createEntity(entityId);
                    if (!zmb) return null;
                    zmb.setPosition(x, y, z);
                    zmb.mergeNbt({
                        TextureId: textureId,
                        VoiceProfile: voice,      // "MALE", "FEMALE", "MUTANT", "VANILLA"
                        SkipZoneCheck: 1,
                        CustomStats: 1
                    });
                    zmb.setAttributeBaseValue('minecraft:generic.max_health', 45.0);
                    zmb.setHealth(45.0);
                    zmb.setAttributeBaseValue('minecraft:generic.movement_speed', 0.28);
                    zmb.setAttributeBaseValue('minecraft:generic.attack_damage', 6.0);
                    zmb.spawn();
                    return zmb;
                }

                ------------------------------------------------------------------------
                EJEMPLO B (KubeJS Rhino): Invocar Mutante (Mujer 3) directamente
                ------------------------------------------------------------------------
                const mutant = level.createEntity('chernobylzombies:zombie_mujer_3');
                mutant.setPosition(x, y, z);
                mutant.setVariant(7, 2);          // Textura 2 (ModelId 7 ya es fijo)
                mutant.setVoice('MUTANT');        // Voz grave de mutante
                mutant.setSkipZoneCheck(true);    // Nunca cancelado por reglas de zona
                mutant.setCustomStats(true);      // Protege tus atributos en KubeJS
                mutant.setAttributeBaseValue('minecraft:generic.max_health', 80.0);
                mutant.setHealth(80.0);
                mutant.spawn();

                ------------------------------------------------------------------------
                EJEMPLO C: Reemplazar Zombies Vanilla desde KubeJS (en modo KUBEJS_ONLY)
                ------------------------------------------------------------------------
                EntityEvents.spawned('minecraft:zombie', event => {
                    const level = event.level;
                    if (level.isClientSide()) return;
                    const e = event.entity;
                    const modelId = Math.floor(Math.random() * ZOMBIE_IDS.length);
                    const cz = level.createEntity(ZOMBIE_IDS[modelId]);
                    if (!cz) return;
                    const texCount = (modelId === 4) ? 1 : 3;
                    const texId = Math.floor(Math.random() * texCount);
                    cz.setPositionAndRotation(e.x, e.y, e.z, e.yRot, e.xRot);
                    cz.setVariant(modelId, texId);
                    cz.setSkipZoneCheck(true);
                    cz.spawn();
                    event.cancel();
                });

                COMANDOS ADMIN (OP >= 2):
                   /czones wand
                   /czones gui
                   /czones stats
                   /czones mode <INTERNAL_ZONES|HYBRID|KUBEJS_ONLY>
                   /czones reload
                   /czones setpoly <nombre_o_id>
                """;
    }

    private static final class ConfigPayload {
        String _comment = "spawnerMode: INTERNAL_ZONES | HYBRID | KUBEJS_ONLY";
        String spawnerMode = SpawnerMode.INTERNAL_ZONES.name();
        GlobalZoneDto globalZone;
        List<ZoneDto> zones = new ArrayList<>();
        Map<Integer, ModelStatsDto> modelStats = new LinkedHashMap<>();
    }

    private static final class VertexDto {
        int x, y, z;

        static VertexDto fromPos(BlockPos pos) {
            VertexDto dto = new VertexDto();
            dto.x = pos.getX();
            dto.y = pos.getY();
            dto.z = pos.getZ();
            return dto;
        }

        BlockPos toPos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final class BoxDto {
        int minX, minY, minZ, maxX, maxY, maxZ;
    }

    private static final class ZoneDto {
        String id;
        String name;
        int color;
        String dimension;
        List<VertexDto> vertices = new ArrayList<>();
        List<BoxDto> boxes = new ArrayList<>();
        int minY;
        int maxY;
        int maxZombies;
        float spawnRateMultiplier;
        String timeCondition;
        boolean ignoreLightLevel;
        int spawnIntervalSeconds;
        boolean enabled;
        Map<Integer, Integer> allowedModelWeights = new LinkedHashMap<>();

        static ZoneDto fromZone(SpawnZone zone) {
            ZoneDto dto = new ZoneDto();
            dto.id = zone.getId().toString();
            dto.name = zone.getName();
            dto.color = zone.getColor();
            dto.dimension = zone.getDimension().location().toString();
            for (BlockPos v : zone.getVertices()) {
                dto.vertices.add(VertexDto.fromPos(v));
            }
            dto.minY = zone.getMinY();
            dto.maxY = zone.getMaxY();
            dto.maxZombies = zone.getMaxZombies();
            dto.spawnRateMultiplier = zone.getSpawnRateMultiplier();
            dto.timeCondition = zone.getTimeCondition().name();
            dto.ignoreLightLevel = zone.isIgnoreLightLevel();
            dto.spawnIntervalSeconds = zone.getSpawnIntervalSeconds();
            dto.enabled = zone.isEnabled();
            dto.allowedModelWeights.putAll(zone.getAllowedModelWeights());
            return dto;
        }

        SpawnZone toZone() {
            UUID parsedId;
            try {
                parsedId = UUID.fromString(this.id);
            } catch (Exception e) {
                parsedId = UUID.randomUUID();
            }
            ResourceLocation dimLoc = ResourceLocation.tryParse(this.dimension != null ? this.dimension : "minecraft:overworld");
            ResourceKey<Level> dim = dimLoc != null ? ResourceKey.create(Registries.DIMENSION, dimLoc) : Level.OVERWORLD;

            List<BlockPos> parsedVertices = new ArrayList<>();
            int yMin = this.minY;
            int yMax = this.maxY;
            if (this.vertices != null && !this.vertices.isEmpty()) {
                for (VertexDto v : this.vertices) {
                    if (v != null) {
                        parsedVertices.add(v.toPos());
                    }
                }
            } else if (this.boxes != null && !this.boxes.isEmpty()) {
                BoxDto b = this.boxes.get(0);
                parsedVertices.add(new BlockPos(b.minX, b.minY, b.minZ));
                parsedVertices.add(new BlockPos(b.maxX, b.minY, b.minZ));
                parsedVertices.add(new BlockPos(b.maxX, b.minY, b.maxZ));
                parsedVertices.add(new BlockPos(b.minX, b.minY, b.maxZ));
                yMin = Math.min(b.minY, b.maxY);
                yMax = Math.max(b.minY, b.maxY);
            }

            return new SpawnZone(
                    parsedId,
                    this.name,
                    this.color,
                    dim,
                    parsedVertices,
                    yMin,
                    yMax,
                    this.maxZombies,
                    this.spawnRateMultiplier,
                    ZoneTimeCondition.fromName(this.timeCondition),
                    this.ignoreLightLevel,
                    this.spawnIntervalSeconds,
                    this.allowedModelWeights,
                    this.enabled
            );
        }
    }

    private static final class GlobalZoneDto {
        boolean enabled;
        int maxZombiesPerArea;
        float spawnRateMultiplier;
        String timeCondition;
        boolean ignoreLightLevel;
        int spawnIntervalSeconds;
        Map<Integer, Integer> allowedModelWeights = new LinkedHashMap<>();

        static GlobalZoneDto fromGlobal(GlobalSpawnZone zone) {
            GlobalZoneDto dto = new GlobalZoneDto();
            dto.enabled = zone.isEnabled();
            dto.maxZombiesPerArea = zone.getMaxZombiesPerArea();
            dto.spawnRateMultiplier = zone.getSpawnRateMultiplier();
            dto.timeCondition = zone.getTimeCondition().name();
            dto.ignoreLightLevel = zone.isIgnoreLightLevel();
            dto.spawnIntervalSeconds = zone.getSpawnIntervalSeconds();
            dto.allowedModelWeights.putAll(zone.getAllowedModelWeights());
            return dto;
        }

        GlobalSpawnZone toGlobal() {
            return new GlobalSpawnZone(
                    this.enabled,
                    this.maxZombiesPerArea,
                    this.spawnRateMultiplier,
                    ZoneTimeCondition.fromName(this.timeCondition),
                    this.ignoreLightLevel,
                    this.spawnIntervalSeconds,
                    this.allowedModelWeights
            );
        }
    }

    private static final class ModelStatsDto {
        double maxHealth;
        double movementSpeed;
        double attackDamage;
        double followRange;
        double knockbackResistance;
        float scale;
        boolean immuneToSunlight;
        boolean immuneToFallDamage;
        boolean persistent;
        int xpReward;
        String customLootTable;
        String customDisplayName;
        boolean showBossBar;

        static ModelStatsDto fromStats(ZombieModelStats s) {
            ModelStatsDto dto = new ModelStatsDto();
            dto.maxHealth = s.maxHealth();
            dto.movementSpeed = s.movementSpeed();
            dto.attackDamage = s.attackDamage();
            dto.followRange = s.followRange();
            dto.knockbackResistance = s.knockbackResistance();
            dto.scale = s.scale();
            dto.immuneToSunlight = s.immuneToSunlight();
            dto.immuneToFallDamage = s.immuneToFallDamage();
            dto.persistent = s.persistent();
            dto.xpReward = s.xpReward();
            dto.customLootTable = s.customLootTable();
            dto.customDisplayName = s.customDisplayName();
            dto.showBossBar = s.showBossBar();
            return dto;
        }

        ZombieModelStats toStats() {
            return new ZombieModelStats(
                    maxHealth, movementSpeed, attackDamage, followRange,
                    knockbackResistance, scale, immuneToSunlight, immuneToFallDamage,
                    persistent, xpReward, customLootTable, customDisplayName, showBossBar
            );
        }
    }
}
