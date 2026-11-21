package com.chernobylzombies.registry;

import com.chernobylzombies.ChernobylZombiesMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Static array-backed registry of the 10 custom GeckoLib zombie models (IDs 0-9).
 * All ResourceLocations are pre-cached at startup to avoid per-frame allocations.
 */
public final class ZombieVariantRegistry {

    private static final int TOTAL_VARIANTS = 10;
    private static final ResourceLocation MASTER_ANIMATION =
            new ResourceLocation(ChernobylZombiesMod.MODID, "animations/zombie_master.animation.json");

    private static final ZombieVariant[] VARIANTS = new ZombieVariant[TOTAL_VARIANTS];
    private static final ResourceLocation[] MODEL_RESOURCES = new ResourceLocation[TOTAL_VARIANTS];
    private static final ResourceLocation[] ANIMATION_RESOURCES = new ResourceLocation[TOTAL_VARIANTS];
    private static final ResourceLocation[][] TEXTURE_RESOURCES = new ResourceLocation[TOTAL_VARIANTS][];
    private static List<ZombieVariant> allVariantsView = List.of();
    private static boolean initialized = false;

    static {
        init();
    }

    private ZombieVariantRegistry() {
    }

    /**
     * Metadata definition for a single custom zombie model and its texture variants.
     */
    public record ZombieVariant(
            int id,
            String key,
            String displayName,
            ResourceLocation modelLocation,
            ResourceLocation animationLocation,
            List<ResourceLocation> textures
    ) {
        public ResourceLocation getTexture(int textureId) {
            if (textures.isEmpty()) {
                throw new IllegalStateException("Zombie variant " + key + " has no registered textures.");
            }
            if (textureId < 0 || textureId >= textures.size()) {
                return textures.get(0);
            }
            return textures.get(textureId);
        }
    }

    /**
     * Populates the pre-cached arrays with all 10 imported zombie variants (5 male, 5 female).
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Male variants (0 - 4)
        registerVariant(0, "zombie_civil_male_01", "Hombre 1",
                "zombie_civil_male_01.geo.json",
                "zombie_civil_texture.png",
                "zombie_civil_male_02.png",
                "zombie_civil_male_03.png");

        registerVariant(1, "zombie_survivor_hoodie", "Hombre 2",
                "zombie_survivor_hoodie.geo.json",
                "zombie_survivor_hoodie.png",
                "zombie_hodie_texture_02.png",
                "zombie_hodie_texture_03.png");

        registerVariant(2, "zombie_civil_male_03", "Hombre 3",
                "zombie_civil_male_03.geo.json",
                "zombie_mcfly_texture_01.png",
                "zombie_civil_male_texture_03_02.png",
                "zombie_civil_male_texture_03_03.png");

        registerVariant(3, "zombie_worker_male_04", "Hombre 4",
                "zombie_worker_male_04.geo.json",
                "zombie_toji_texture_01.png",
                "zombie_worker_male_05_02.png",
                "zombie_worker_male_05_03.png");

        registerVariant(4, "zombie_tactical_male_06", "Hombre 5",
                "zombie_tactical_male_06.geo.json",
                "zombie_rpd_texture_01.png");

        // Female variants (5 - 9)
        registerVariant(5, "zombie_civil_female_01", "Mujer 1",
                "zombie_civil_female_01.geo.json",
                "zombie_civil_female_01_01.png",
                "zombie_civil_female_01_02.png",
                "zombie_civil_female_01_03.png");

        registerVariant(6, "zombie_military_female_02", "Mujer 2",
                "zombie_military_female_02.geo.json",
                "zombie_military_female_02_01.png",
                "zombie_military_female_02_02.png",
                "zombie_military_female_02_03.png");

        registerVariant(7, "zombie_mutant_female_03", "Mujer 3",
                "zombie_mutant_female_03.geo.json",
                "zombie_mutant_female_03_01.png",
                "zombie_mutant_female_03_02.png",
                "zombie_mutant_female_03_03.png");

        registerVariant(8, "zombie_backpacker_female_04", "Mujer 4",
                "zombie_backpacker_female_04.geo.json",
                "zombie_backpacker_female_04_01.png",
                "zombie_backpacker_female_04_02.png",
                "zombie_backpacker_female_04_03.png");

        registerVariant(9, "zombie_twisted_female_05", "Mujer 5",
                "zombie_twisted_female_05.geo.json",
                "zombie_twisted_female_05_01.png",
                "zombie_twisted_female_05_02.png",
                "zombie_twisted_female_05_03.png");

        allVariantsView = List.of(VARIANTS);
    }

    private static void registerVariant(int id, String key, String displayName, String geoFileName, String... textureFileNames) {
        ResourceLocation modelLoc = new ResourceLocation(ChernobylZombiesMod.MODID, "geo/" + geoFileName);
        ResourceLocation[] textureArray = Arrays.stream(textureFileNames)
                .map(tex -> new ResourceLocation(ChernobylZombiesMod.MODID, "textures/entity/" + tex))
                .toArray(ResourceLocation[]::new);

        MODEL_RESOURCES[id] = modelLoc;
        ANIMATION_RESOURCES[id] = MASTER_ANIMATION;
        TEXTURE_RESOURCES[id] = textureArray;
        VARIANTS[id] = new ZombieVariant(id, key, displayName, modelLoc, MASTER_ANIMATION, List.of(textureArray));
    }

    private static int clampModelIndex(int modelId) {
        return (modelId >= 0 && modelId < TOTAL_VARIANTS) ? modelId : 0;
    }

    public static ZombieVariant getVariant(int modelId) {
        return VARIANTS[clampModelIndex(modelId)];
    }

    public static ResourceLocation getModelResource(int modelId) {
        return MODEL_RESOURCES[clampModelIndex(modelId)];
    }

    public static ResourceLocation getAnimationResource(int modelId) {
        return ANIMATION_RESOURCES[clampModelIndex(modelId)];
    }

    public static ResourceLocation getTextureResource(int modelId, int textureId) {
        ResourceLocation[] textures = TEXTURE_RESOURCES[clampModelIndex(modelId)];
        int texIdx = (textureId >= 0 && textureId < textures.length) ? textureId : 0;
        return textures[texIdx];
    }

    public static int getTextureCount(int modelId) {
        return TEXTURE_RESOURCES[clampModelIndex(modelId)].length;
    }

    public static int getTotalVariants() {
        return TOTAL_VARIANTS;
    }

    public static Collection<ZombieVariant> getAllVariants() {
        return allVariantsView;
    }
}
