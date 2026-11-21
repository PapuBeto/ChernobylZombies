package com.chernobylzombies.client.gui;

import com.chernobylzombies.client.ClientZoneCache;
import com.chernobylzombies.data.ServerConfigManager;
import com.chernobylzombies.data.SpawnerMode;
import com.chernobylzombies.data.ZombieModelStats;
import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.item.ZoneWandItem;
import com.chernobylzombies.network.PacketHandler;
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
import com.chernobylzombies.registry.ModEntities;
import com.chernobylzombies.registry.ZombieVariantRegistry;
import com.chernobylzombies.zone.GlobalSpawnZone;
import com.chernobylzombies.zone.SpawnZone;
import com.chernobylzombies.zone.ZoneTimeCondition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * Full administrative GUI for Chernobyl Zombies (Phase 4 + Polygonal Zones Update):
 * - Tab 1: Specific Polygonal Zones (list, quick create with wand points, TP/Purge/Horde/Delete, Y min/max, redefine shape, model weights)
 * - Tab 2: Global (Common) Zone
 * - Tab 3: Zombie Model Stats, Behavior & Live 3D GeckoLib Preview
 */
@OnlyIn(Dist.CLIENT)
public class ZoneManagerScreen extends Screen {

    private static final int[] COLOR_PALETTE = {
            0xE63946, 0xF77F00, 0xFCBF49, 0x2A9D8F, 0x06D6A0,
            0x118AB2, 0x3A86FF, 0x8338EC, 0xFF006E, 0x8AC926
    };

    private static final int COL_BACKDROP = 0x9905070A;
    private static final int COL_PANEL_BG = 0xEE0D1117;
    private static final int COL_CARD_BG = 0xEE161B22;
    private static final int COL_BORDER = 0xFF30363D;
    private static final int COL_ACCENT = 0xFF1F6FEB;
    private static final int COL_TEXT_PRIMARY = 0xFFF0F6FC;
    private static final int COL_TEXT_MUTED = 0xFF8B949E;
    private static final int COL_TEXT_GOOD = 0xFF3FB950;
    private static final int COL_TEXT_WARN = 0xFFD29922;

    private enum Tab {
        SPECIFIC_ZONES,
        GLOBAL_ZONE,
        ZOMBIE_STATS
    }

    private Tab activeTab = Tab.SPECIFIC_ZONES;

    private int panelX, panelY, panelW, panelH;
    private int bodyTop, bodyBottom;

    // Tab 1 state
    private UUID selectedZoneId = null;
    private UUID copySourceZoneId = null;
    private ZoneDraft zoneDraft = null;
    private int zoneListScroll = 0;
    private int zoneWeightScroll = 0;
    private boolean zoneOnlyActiveModels = false;
    private int cachedZoneListSize = -1;

    // Tab 2 state
    private GlobalDraft globalDraft = GlobalDraft.from(ClientZoneCache.getGlobalZone());
    private int globalWeightScroll = 0;
    private boolean globalOnlyActiveModels = false;

    // Tab 3 state
    private int selectedModelId = 0;
    private int previewTextureId = 0;
    private final Map<Integer, ZombieModelStats> draftStatsMap = new LinkedHashMap<>();
    private CustomZombieEntity previewZombie;

    // Live UI references
    private final List<NumericSlider> activeWeightSliders = new ArrayList<>();
    private final Map<UUID, Button> zoneListButtons = new LinkedHashMap<>();
    private final List<EditBox> trackedEditBoxes = new ArrayList<>();
    private Button spawnerModeButton;
    private EditBox zoneNameInput, zoneHexInput, zoneMaxInput, zoneIntervalInput, zoneMinYInput, zoneMaxYInput;
    private EditBox globalMaxInput, globalIntervalInput;
    private EditBox statHealthInput, statSpeedInput, statDamageInput, statFollowInput, statKnockbackInput, statXpInput;
    private EditBox statNameInput, statLootInput;

    // Layout rects for custom rendering and mouse scrolling
    private int createBtnX, createBtnY, createBtnW, createBtnH;
    private int colorPaletteX, colorPaletteY;
    private int zoneListX, zoneListY, zoneListW, zoneListH;
    private int polyCardX, polyCardY, polyCardW, polyCardH;
    private int weightListX, weightListY, weightListW, weightListH;
    private int previewBoxX, previewBoxY, previewBoxW, previewBoxH;

    private String statusMessage = "Panel listo.";
    private int statusColor = COL_TEXT_MUTED;

    public ZoneManagerScreen() {
        super(Component.literal("Chernobyl Zombies - Panel de Administración"));
        loadInitialDrafts();
    }

    private void loadInitialDrafts() {
        List<SpawnZone> zones = ClientZoneCache.getZones();
        this.cachedZoneListSize = zones.size();
        if (!zones.isEmpty()) {
            selectZone(zones.get(0));
        }
        this.globalDraft = GlobalDraft.from(ClientZoneCache.getGlobalZone());
        int total = ZombieVariantRegistry.getTotalVariants();
        for (int i = 0; i < total; i++) {
            this.draftStatsMap.put(i, ClientZoneCache.getModelStats(i));
        }
    }

    private void selectZone(SpawnZone zone) {
        if (zone == null) {
            this.selectedZoneId = null;
            this.zoneDraft = null;
            return;
        }
        this.selectedZoneId = zone.getId();
        this.zoneDraft = ZoneDraft.from(zone);
        this.zoneWeightScroll = 0;
        ensureValidCopySource();
    }

    private void ensureValidCopySource() {
        List<SpawnZone> others = getOtherZones();
        if (others.isEmpty()) {
            this.copySourceZoneId = null;
            return;
        }
        if (this.copySourceZoneId == null || others.stream().noneMatch(z -> z.getId().equals(this.copySourceZoneId))) {
            this.copySourceZoneId = others.get(0).getId();
        }
    }

    private List<SpawnZone> getOtherZones() {
        List<SpawnZone> others = new ArrayList<>();
        for (SpawnZone z : ClientZoneCache.getZones()) {
            if (!z.getId().equals(this.selectedZoneId)) {
                others.add(z);
            }
        }
        return others;
    }

    public void onServerCacheUpdated() {
        if (this.spawnerModeButton != null) {
            this.spawnerModeButton.setMessage(Component.literal(formatModeButtonText(ClientZoneCache.getSpawnerMode())));
        }

        List<SpawnZone> zones = ClientZoneCache.getZones();
        boolean listSizeChanged = zones.size() != this.cachedZoneListSize;
        this.cachedZoneListSize = zones.size();

        if (this.selectedZoneId != null) {
            Optional<SpawnZone> match = zones.stream()
                    .filter(z -> z.getId().equals(this.selectedZoneId))
                    .findFirst();
            if (match.isEmpty()) {
                selectZone(zones.isEmpty() ? null : zones.get(0));
                listSizeChanged = true;
            }
        } else if (!zones.isEmpty()) {
            selectZone(zones.get(0));
            listSizeChanged = true;
        }

        if (this.activeTab == Tab.SPECIFIC_ZONES) {
            if (listSizeChanged) {
                rebuildWidgetsPreservingScroll();
            } else {
                for (SpawnZone zone : zones) {
                    Button btn = this.zoneListButtons.get(zone.getId());
                    if (btn != null) {
                        btn.setMessage(Component.literal(formatZoneListTitle(zone)));
                    }
                }
            }
        }
    }

    private static String formatZoneListTitle(SpawnZone zone) {
        int live = ClientZoneCache.getZoneLiveCount(zone.getId());
        String tag = zone.isEnabled() ? "" : "[OFF] ";
        return tag + trimText(zone.getName(), 14) + " " + live + "/" + zone.getMaxZombies();
    }

    private void setStatus(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    @Override
    protected void init() {
        super.init();
        this.panelW = Math.min(this.width - 16, 660);
        this.panelH = Math.min(this.height - 14, 382);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;
        this.bodyTop = this.panelY + 42;
        this.bodyBottom = this.panelY + this.panelH - 28;

        this.activeWeightSliders.clear();
        this.zoneListButtons.clear();
        this.trackedEditBoxes.clear();
        clearDraftInputRefs();

        buildHeaderTabs();
        buildFooterButtons();

        switch (this.activeTab) {
            case SPECIFIC_ZONES -> buildSpecificZonesTab();
            case GLOBAL_ZONE -> buildGlobalZoneTab();
            case ZOMBIE_STATS -> buildZombieStatsTab();
        }
    }

    private void rebuildWidgetsPreservingScroll() {
        syncInputsToActiveDraft();
        this.rebuildWidgets();
    }

    private void clearDraftInputRefs() {
        this.spawnerModeButton = null;
        this.zoneNameInput = null;
        this.zoneHexInput = null;
        this.zoneMaxInput = null;
        this.zoneIntervalInput = null;
        this.zoneMinYInput = null;
        this.zoneMaxYInput = null;
        this.globalMaxInput = null;
        this.globalIntervalInput = null;
        this.statHealthInput = null;
        this.statSpeedInput = null;
        this.statDamageInput = null;
        this.statFollowInput = null;
        this.statKnockbackInput = null;
        this.statXpInput = null;
        this.statNameInput = null;
        this.statLootInput = null;
    }

    private void syncInputsToActiveDraft() {
        if (this.zoneDraft != null) {
            if (this.zoneNameInput != null) {
                this.zoneDraft.name = this.zoneNameInput.getValue();
            }
            if (this.zoneHexInput != null) {
                Integer parsed = parseHexColor(this.zoneHexInput.getValue());
                if (parsed != null) {
                    this.zoneDraft.color = parsed;
                }
            }
            if (this.zoneMaxInput != null) {
                this.zoneDraft.maxZombies = parseIntOr(this.zoneMaxInput.getValue(), this.zoneDraft.maxZombies, 0, 500);
            }
            if (this.zoneIntervalInput != null) {
                this.zoneDraft.spawnIntervalSeconds = SpawnZone.clampInterval(
                        parseIntOr(this.zoneIntervalInput.getValue(), this.zoneDraft.spawnIntervalSeconds, 2, 300)
                );
            }
            if (this.zoneMinYInput != null && this.zoneMaxYInput != null) {
                int y1 = parseIntOr(this.zoneMinYInput.getValue(), this.zoneDraft.minY, -64, 320);
                int y2 = parseIntOr(this.zoneMaxYInput.getValue(), this.zoneDraft.maxY, -64, 320);
                this.zoneDraft.minY = Math.min(y1, y2);
                this.zoneDraft.maxY = Math.max(y1, y2);
            }
        }

        if (this.globalMaxInput != null) {
            this.globalDraft.maxZombiesPerArea = parseIntOr(this.globalMaxInput.getValue(), this.globalDraft.maxZombiesPerArea, 0, 500);
        }
        if (this.globalIntervalInput != null) {
            this.globalDraft.spawnIntervalSeconds = SpawnZone.clampInterval(
                    parseIntOr(this.globalIntervalInput.getValue(), this.globalDraft.spawnIntervalSeconds, 2, 300)
            );
        }

        syncStatsInputsToDraft();
    }

    private void syncStatsInputsToDraft() {
        if (this.statHealthInput == null) {
            return;
        }
        ZombieModelStats cur = this.draftStatsMap.getOrDefault(this.selectedModelId, ZombieModelStats.defaults());
        ZombieModelStats updated = new ZombieModelStats(
                parseDoubleOr(this.statHealthInput.getValue(), cur.maxHealth()),
                parseDoubleOr(this.statSpeedInput.getValue(), cur.movementSpeed()),
                parseDoubleOr(this.statDamageInput.getValue(), cur.attackDamage()),
                parseDoubleOr(this.statFollowInput.getValue(), cur.followRange()),
                parseDoubleOr(this.statKnockbackInput.getValue(), cur.knockbackResistance() * 100.0D) / 100.0D,
                cur.scale(),
                cur.immuneToSunlight(),
                cur.immuneToFallDamage(),
                cur.persistent(),
                parseIntOr(this.statXpInput.getValue(), cur.xpReward(), 0, 10000),
                this.statLootInput != null ? this.statLootInput.getValue() : cur.customLootTable(),
                this.statNameInput != null ? this.statNameInput.getValue() : cur.customDisplayName(),
                cur.showBossBar()
        );
        this.draftStatsMap.put(this.selectedModelId, updated);
    }

    private void buildHeaderTabs() {
        int tabY = this.panelY + 18;
        int tabW = (this.panelW - 24) / 3;
        addRenderableWidget(Button.builder(
                Component.literal((this.activeTab == Tab.SPECIFIC_ZONES ? "> " : "") + "1. Zonas Especificas"),
                b -> switchTab(Tab.SPECIFIC_ZONES)
        ).bounds(this.panelX + 8, tabY, tabW - 2, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal((this.activeTab == Tab.GLOBAL_ZONE ? "> " : "") + "2. Zona Global (Comun)"),
                b -> switchTab(Tab.GLOBAL_ZONE)
        ).bounds(this.panelX + 8 + tabW, tabY, tabW - 2, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal((this.activeTab == Tab.ZOMBIE_STATS ? "> " : "") + "3. Stats y Pruebas"),
                b -> switchTab(Tab.ZOMBIE_STATS)
        ).bounds(this.panelX + 8 + tabW * 2, tabY, tabW - 2, 20).build());
    }

    private void switchTab(Tab target) {
        if (this.activeTab == target) {
            return;
        }
        syncInputsToActiveDraft();
        this.activeTab = target;
        if (target == Tab.GLOBAL_ZONE) {
            this.globalDraft = GlobalDraft.from(ClientZoneCache.getGlobalZone());
        } else if (target == Tab.ZOMBIE_STATS) {
            int total = ZombieVariantRegistry.getTotalVariants();
            for (int i = 0; i < total; i++) {
                this.draftStatsMap.put(i, ClientZoneCache.getModelStats(i));
            }
        }
        this.rebuildWidgets();
    }

    private static String formatModeButtonText(SpawnerMode mode) {
        return switch (mode) {
            case INTERNAL_ZONES -> "Modo: ZONAS";
            case HYBRID -> "Modo: HIBRIDO";
            case KUBEJS_ONLY -> "Modo: KUBEJS";
        };
    }

    private void buildFooterButtons() {
        int btnY = this.panelY + this.panelH - 24;
        int closeW = 54;
        int reloadW = 88;
        int exportW = 88;
        int modeW = 94;
        int rightEdge = this.panelX + this.panelW - 8;

        addRenderableWidget(Button.builder(Component.literal("Cerrar"), b -> onClose())
                .bounds(rightEdge - closeW, btnY, closeW, 18).build());

        int reloadX = rightEdge - closeW - 4 - reloadW;
        addRenderableWidget(Button.builder(Component.literal("Recargar JSON"), b -> {
            PacketHandler.sendToServer(new C2SReloadConfigPacket());
            setStatus("Recargando config/chernobylzombies/...", COL_TEXT_GOOD);
        }).bounds(reloadX, btnY, reloadW, 18).build());

        int exportX = reloadX - 4 - exportW;
        addRenderableWidget(Button.builder(Component.literal("Exportar JSON"), b -> {
            String msg = ServerConfigManager.exportSnapshot(
                    ClientZoneCache.getSpawnerMode(),
                    ClientZoneCache.getZones(),
                    ClientZoneCache.getGlobalZone(),
                    ClientZoneCache.getAllModelStats()
            );
            setStatus(msg, msg.startsWith("Error") ? COL_TEXT_WARN : COL_TEXT_GOOD);
        }).bounds(exportX, btnY, exportW, 18).build());

        int modeX = exportX - 4 - modeW;
        this.spawnerModeButton = addRenderableWidget(Button.builder(
                Component.literal(formatModeButtonText(ClientZoneCache.getSpawnerMode())),
                b -> {
                    SpawnerMode next = ClientZoneCache.getSpawnerMode().next();
                    PacketHandler.sendToServer(new C2SSetSpawnerModePacket(next));
                    b.setMessage(Component.literal(formatModeButtonText(next)));
                    setStatus("Cambiando modo a " + next.name() + "...", COL_TEXT_GOOD);
                }
        ).bounds(modeX, btnY, modeW, 18).build());
    }

    // ==================== TAB 1: SPECIFIC ZONES ====================

    private void buildSpecificZonesTab() {
        int leftW = Math.max(185, (int) (this.panelW * 0.34F));
        int leftX = this.panelX + 8;
        int rightX = leftX + leftW + 8;
        int rightW = this.panelX + this.panelW - 8 - rightX;

        List<BlockPos> wandVertices = getClientWandVertices();
        boolean wandReady = wandVertices.size() >= 3;
        String createLabel = wandReady
                ? "+ Crear Zona (" + wandVertices.size() + " Puntos)"
                : "+ Nueva Zona (Marca 3+ Puntos)";

        this.createBtnX = leftX;
        this.createBtnY = this.bodyTop;
        this.createBtnW = leftW;
        this.createBtnH = 18;
        addRenderableWidget(Button.builder(Component.literal(createLabel), b -> createNewZoneFromWand())
                .bounds(this.createBtnX, this.createBtnY, this.createBtnW, this.createBtnH).build());

        this.zoneListX = leftX;
        this.zoneListY = this.bodyTop + 22;
        this.zoneListW = leftW;
        this.zoneListH = this.bodyBottom - this.zoneListY;

        List<SpawnZone> zones = ClientZoneCache.getZones();
        int cardH = 38;
        int visibleCards = Math.max(1, this.zoneListH / cardH);
        int maxScroll = Math.max(0, zones.size() - visibleCards);
        this.zoneListScroll = Math.max(0, Math.min(this.zoneListScroll, maxScroll));

        for (int i = 0; i < visibleCards && (i + this.zoneListScroll) < zones.size(); i++) {
            SpawnZone zone = zones.get(i + this.zoneListScroll);
            int cardY = this.zoneListY + i * cardH;

            Button selectBtn = addRenderableWidget(Button.builder(Component.literal(formatZoneListTitle(zone)), b -> {
                syncInputsToActiveDraft();
                selectZone(zone);
                this.rebuildWidgets();
            }).bounds(leftX + 12, cardY + 2, leftW - 14, 16).build());
            this.zoneListButtons.put(zone.getId(), selectBtn);

            int actionW = (leftW - 12) / 4;
            int ay = cardY + 19;
            addRenderableWidget(Button.builder(Component.literal("TP"), b -> {
                PacketHandler.sendToServer(new C2STeleportToZonePacket(zone.getId()));
                setStatus("Teletransportando a '" + zone.getName() + "'...", COL_TEXT_GOOD);
            }).bounds(leftX + 2, ay, actionW - 1, 15).build());

            addRenderableWidget(Button.builder(Component.literal("Purgar"), b -> {
                PacketHandler.sendToServer(new C2SPurgeZonePacket(zone.getId()));
                setStatus("Purgando zombies en '" + zone.getName() + "'...", COL_TEXT_WARN);
            }).bounds(leftX + 2 + actionW, ay, actionW - 1, 15).build());

            addRenderableWidget(Button.builder(Component.literal("Horda"), b -> {
                PacketHandler.sendToServer(new C2STriggerHordePacket(zone.getId()));
                setStatus("Horda detonada en '" + zone.getName() + "'!", COL_TEXT_WARN);
            }).bounds(leftX + 2 + actionW * 2, ay, actionW - 1, 15).build());

            addRenderableWidget(Button.builder(Component.literal("Elim"), b -> {
                PacketHandler.sendToServer(new C2SDeleteZonePacket(zone.getId()));
                setStatus("Zona '" + zone.getName() + "' eliminada.", COL_TEXT_WARN);
            }).bounds(leftX + 2 + actionW * 3, ay, actionW - 1, 15).build());
        }

        if (this.zoneDraft == null) {
            return;
        }

        int y = this.bodyTop;
        int nameW = Math.max(100, rightW - 146);
        this.zoneNameInput = addEditBox(rightX, y, nameW, 18, this.zoneDraft.name, 64,
                val -> this.zoneDraft.name = val);

        this.zoneHexInput = addEditBox(rightX + nameW + 4, y, 58, 18, formatHex(this.zoneDraft.color), 7, val -> {
            Integer parsed = parseHexColor(val);
            if (parsed != null) {
                this.zoneDraft.color = parsed;
            }
        });

        addRenderableWidget(Button.builder(
                Component.literal(this.zoneDraft.enabled ? "Activa: ON" : "Activa: OFF"),
                b -> {
                    this.zoneDraft.enabled = !this.zoneDraft.enabled;
                    b.setMessage(Component.literal(this.zoneDraft.enabled ? "Activa: ON" : "Activa: OFF"));
                }
        ).bounds(rightX + nameW + 66, y, 80, 18).build());

        // Color palette row + Copy Config controls
        y += 22;
        this.colorPaletteX = rightX + 34;
        this.colorPaletteY = y + 2;

        List<SpawnZone> otherZones = getOtherZones();
        if (!otherZones.isEmpty()) {
            ensureValidCopySource();
            SpawnZone srcZone = otherZones.stream()
                    .filter(z -> z.getId().equals(this.copySourceZoneId))
                    .findFirst()
                    .orElse(otherZones.get(0));
            int copyBtnX = rightX + 162;
            int copyBtnWidth = Math.max(40, rightW - 162 - 48);
            addRenderableWidget(Button.builder(
                    Component.literal("De: " + trimText(srcZone.getName(), 10)),
                    b -> {
                        int idx = -1;
                        for (int k = 0; k < otherZones.size(); k++) {
                            if (otherZones.get(k).getId().equals(this.copySourceZoneId)) {
                                idx = k;
                                break;
                            }
                        }
                        SpawnZone next = otherZones.get((idx + 1) % otherZones.size());
                        this.copySourceZoneId = next.getId();
                        b.setMessage(Component.literal("De: " + trimText(next.getName(), 10)));
                    }
            ).bounds(copyBtnX, y, copyBtnWidth, 16).build());

            addRenderableWidget(Button.builder(Component.literal("Copiar"), b -> {
                if (this.copySourceZoneId != null && this.selectedZoneId != null) {
                    PacketHandler.sendToServer(new C2SDuplicateZoneConfigPacket(this.copySourceZoneId, this.selectedZoneId));
                    ClientZoneCache.getZones().stream()
                            .filter(z -> z.getId().equals(this.copySourceZoneId))
                            .findFirst()
                            .ifPresent(src -> {
                                this.zoneDraft.copyRulesFrom(src);
                                rebuildWidgetsPreservingScroll();
                                setStatus("Reglas copiadas de '" + src.getName() + "'.", COL_TEXT_GOOD);
                            });
                }
            }).bounds(copyBtnX + copyBtnWidth + 2, y, 46, 16).build());
        }

        // Numeric parameters row: Max Zombies, Rate Slider, Interval
        y += 20;
        int colW = (rightW - 8) / 3;
        this.zoneMaxInput = addEditBox(rightX + 32, y, colW - 36, 16, String.valueOf(this.zoneDraft.maxZombies), 4,
                val -> this.zoneDraft.maxZombies = parseIntOr(val, this.zoneDraft.maxZombies, 0, 500));

        addRenderableWidget(new NumericSlider(
                rightX + colW + 4, y, colW - 4, 16,
                0.1D, 5.0D, 0.1D, this.zoneDraft.spawnRateMultiplier,
                val -> Component.literal(String.format(Locale.US, "Rate: %.1fx", val)),
                val -> this.zoneDraft.spawnRateMultiplier = (float) val
        ));

        this.zoneIntervalInput = addEditBox(rightX + colW * 2 + 44, y, colW - 44, 16,
                String.valueOf(this.zoneDraft.spawnIntervalSeconds), 4,
                val -> this.zoneDraft.spawnIntervalSeconds = SpawnZone.clampInterval(parseIntOr(val, this.zoneDraft.spawnIntervalSeconds, 2, 300)));

        // Height (Y Min / Y Max), Schedule & Ignore Light row
        y += 20;
        int yFieldW = 40;
        this.zoneMinYInput = addEditBox(rightX + 34, y, yFieldW, 16, String.valueOf(this.zoneDraft.minY), 5, null);
        this.zoneMaxYInput = addEditBox(rightX + 114, y, yFieldW, 16, String.valueOf(this.zoneDraft.maxY), 5, null);

        int toggleStartX = rightX + 160;
        int toggleTotalW = Math.max(80, rightW - 160);
        int schedW = (toggleTotalW - 4) / 2;
        addRenderableWidget(Button.builder(
                Component.literal("Hor: " + this.zoneDraft.timeCondition.getLabel()),
                b -> {
                    this.zoneDraft.timeCondition = this.zoneDraft.timeCondition.next();
                    b.setMessage(Component.literal("Hor: " + this.zoneDraft.timeCondition.getLabel()));
                }
        ).bounds(toggleStartX, y, schedW, 16).build());

        addRenderableWidget(Button.builder(
                Component.literal("Luz: " + (this.zoneDraft.ignoreLightLevel ? "Ignorar" : "Oscuro")),
                b -> {
                    this.zoneDraft.ignoreLightLevel = !this.zoneDraft.ignoreLightLevel;
                    b.setMessage(Component.literal("Luz: " + (this.zoneDraft.ignoreLightLevel ? "Ignorar" : "Oscuro")));
                }
        ).bounds(toggleStartX + schedW + 4, y, toggleTotalW - schedW - 4, 16).build());

        // Polygon shape section + Redefine button
        y += 20;
        this.polyCardX = rightX;
        this.polyCardY = y;
        this.polyCardW = rightW;
        this.polyCardH = 34;

        addRenderableWidget(Button.builder(
                Component.literal("Redefinir Forma (Usar Puntos de Varita)"),
                b -> redefineDraftPolygonFromWand()
        ).bounds(rightX + 4, y + 16, rightW - 8, 15).build());

        // Model weights list header
        y = this.polyCardY + this.polyCardH + 5;
        addRenderableWidget(Button.builder(
                Component.literal("Solo activos: " + (this.zoneOnlyActiveModels ? "SI" : "NO")),
                b -> {
                    this.zoneOnlyActiveModels = !this.zoneOnlyActiveModels;
                    this.zoneWeightScroll = 0;
                    rebuildWidgetsPreservingScroll();
                }
        ).bounds(rightX + rightW - 102, y, 102, 15).build());

        this.weightListX = rightX;
        this.weightListY = y + 17;
        this.weightListW = rightW;
        this.weightListH = (this.bodyBottom - 22) - this.weightListY;

        this.zoneWeightScroll = buildWeightSliders(
                this.zoneDraft.weights,
                this.zoneOnlyActiveModels,
                this.zoneWeightScroll,
                this.weightListX,
                this.weightListY,
                this.weightListW,
                this.weightListH
        );

        addRenderableWidget(Button.builder(Component.literal("Guardar Cambios de Zona"), b -> {
            syncInputsToActiveDraft();
            if (this.zoneDraft.vertices.size() < 3) {
                setStatus("La zona necesita al menos 3 vertices.", COL_TEXT_WARN);
                return;
            }
            SpawnZone updated = this.zoneDraft.toSpawnZone();
            PacketHandler.sendToServer(new C2SSaveZonePacket(updated, false));
            setStatus("Guardando zona '" + updated.getName() + "'...", COL_TEXT_GOOD);
        }).bounds(rightX, this.bodyBottom - 19, rightW, 18).build());
    }

    private List<BlockPos> getClientWandVertices() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return List.of();
        }
        ItemStack wand = ZoneWandItem.findWand(mc.player);
        return wand.isEmpty() ? List.of() : ZoneWandItem.getVertices(wand);
    }

    private void createNewZoneFromWand() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ItemStack wand = ZoneWandItem.findWand(mc.player);
        List<BlockPos> vertices = wand.isEmpty() ? List.of() : ZoneWandItem.getVertices(wand);
        if (vertices.size() < 3) {
            setStatus("Marca al menos 3 puntos con Click Derecho de la varita primero.", COL_TEXT_WARN);
            return;
        }

        ResourceKey<Level> dim = ZoneWandItem.getDimension(wand).orElse(mc.player.level().dimension());
        int index = ClientZoneCache.getZones().size();
        int color = COLOR_PALETTE[index % COLOR_PALETTE.length];
        int minY = ZoneWandItem.computeDefaultMinY(vertices);
        int maxY = ZoneWandItem.computeDefaultMaxY(vertices);

        SpawnZone newZone = new SpawnZone(
                UUID.randomUUID(),
                "Zona " + (index + 1),
                color,
                dim,
                vertices,
                minY,
                maxY,
                15,
                1.0F,
                ZoneTimeCondition.ALWAYS,
                true,
                SpawnZone.DEFAULT_SPAWN_INTERVAL_SECONDS,
                SpawnZone.createDefaultWeights(),
                true
        );
        this.selectedZoneId = newZone.getId();
        selectZone(newZone);
        PacketHandler.sendToServer(new C2SSaveZonePacket(newZone, false));
        setStatus("Creando '" + newZone.getName() + "' con " + vertices.size() + " vertices...", COL_TEXT_GOOD);
    }

    private void redefineDraftPolygonFromWand() {
        if (this.zoneDraft == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ItemStack wand = ZoneWandItem.findWand(mc.player);
        List<BlockPos> vertices = wand.isEmpty() ? List.of() : ZoneWandItem.getVertices(wand);
        if (vertices.size() < 3) {
            setStatus("Necesitas al menos 3 puntos marcados en la varita para redefinir.", COL_TEXT_WARN);
            return;
        }

        syncInputsToActiveDraft();
        this.zoneDraft.dimension = ZoneWandItem.getDimension(wand).orElse(mc.player.level().dimension());
        this.zoneDraft.vertices.clear();
        this.zoneDraft.vertices.addAll(vertices);
        this.zoneDraft.minY = ZoneWandItem.computeDefaultMinY(vertices);
        this.zoneDraft.maxY = ZoneWandItem.computeDefaultMaxY(vertices);

        if (this.zoneMinYInput != null) {
            this.zoneMinYInput.setValue(String.valueOf(this.zoneDraft.minY));
        }
        if (this.zoneMaxYInput != null) {
            this.zoneMaxYInput.setValue(String.valueOf(this.zoneDraft.maxY));
        }

        setStatus("Poligono actualizado con " + vertices.size() + " puntos. Pulsa 'Guardar Cambios'.", COL_TEXT_GOOD);
    }

    // ==================== TAB 2: GLOBAL ZONE ====================

    private void buildGlobalZoneTab() {
        int leftX = this.panelX + 10;
        int fullW = this.panelW - 20;
        int y = this.bodyTop + 26;

        int colW = (fullW - 12) / 4;
        addRenderableWidget(Button.builder(
                Component.literal(this.globalDraft.enabled ? "Global: ACTIVA" : "Global: OFF"),
                b -> {
                    this.globalDraft.enabled = !this.globalDraft.enabled;
                    b.setMessage(Component.literal(this.globalDraft.enabled ? "Global: ACTIVA" : "Global: OFF"));
                }
        ).bounds(leftX, y, colW, 18).build());

        this.globalMaxInput = addEditBox(leftX + colW + 60, y, colW - 58, 18,
                String.valueOf(this.globalDraft.maxZombiesPerArea), 4,
                val -> this.globalDraft.maxZombiesPerArea = parseIntOr(val, this.globalDraft.maxZombiesPerArea, 0, 500));

        addRenderableWidget(new NumericSlider(
                leftX + colW * 2 + 6, y, colW, 18,
                0.1D, 5.0D, 0.1D, this.globalDraft.spawnRateMultiplier,
                val -> Component.literal(String.format(Locale.US, "Rate: %.1fx", val)),
                val -> this.globalDraft.spawnRateMultiplier = (float) val
        ));

        this.globalIntervalInput = addEditBox(leftX + colW * 3 + 54, y, colW - 54, 18,
                String.valueOf(this.globalDraft.spawnIntervalSeconds), 4,
                val -> this.globalDraft.spawnIntervalSeconds = SpawnZone.clampInterval(
                        parseIntOr(val, this.globalDraft.spawnIntervalSeconds, 2, 300)));

        y += 22;
        int thirdW = (fullW - 8) / 3;
        addRenderableWidget(Button.builder(
                Component.literal("Horario: " + this.globalDraft.timeCondition.getLabel()),
                b -> {
                    this.globalDraft.timeCondition = this.globalDraft.timeCondition.next();
                    b.setMessage(Component.literal("Horario: " + this.globalDraft.timeCondition.getLabel()));
                }
        ).bounds(leftX, y, thirdW, 16).build());

        addRenderableWidget(Button.builder(
                Component.literal("Ignorar Luz: " + (this.globalDraft.ignoreLightLevel ? "SI" : "NO")),
                b -> {
                    this.globalDraft.ignoreLightLevel = !this.globalDraft.ignoreLightLevel;
                    b.setMessage(Component.literal("Ignorar Luz: " + (this.globalDraft.ignoreLightLevel ? "SI" : "NO")));
                }
        ).bounds(leftX + thirdW + 4, y, thirdW, 16).build());

        addRenderableWidget(Button.builder(
                Component.literal("Solo activos: " + (this.globalOnlyActiveModels ? "SI" : "NO")),
                b -> {
                    this.globalOnlyActiveModels = !this.globalOnlyActiveModels;
                    this.globalWeightScroll = 0;
                    rebuildWidgetsPreservingScroll();
                }
        ).bounds(leftX + thirdW * 2 + 8, y, thirdW, 16).build());

        y += 22;
        this.weightListX = leftX;
        this.weightListY = y;
        this.weightListW = fullW;
        this.weightListH = (this.bodyBottom - 24) - this.weightListY;

        this.globalWeightScroll = buildWeightSliders(
                this.globalDraft.weights,
                this.globalOnlyActiveModels,
                this.globalWeightScroll,
                this.weightListX,
                this.weightListY,
                this.weightListW,
                this.weightListH
        );

        addRenderableWidget(Button.builder(Component.literal("Guardar Zona Global"), b -> {
            syncInputsToActiveDraft();
            PacketHandler.sendToServer(new C2SSaveGlobalZonePacket(this.globalDraft.toGlobalZone()));
            setStatus("Zona Global guardada y sincronizada.", COL_TEXT_GOOD);
        }).bounds(leftX, this.bodyBottom - 20, fullW, 18).build());
    }

    // ==================== TAB 3: ZOMBIE STATS & PREVIEW ====================

    private void buildZombieStatsTab() {
        int leftX = this.panelX + 8;
        int leftW = 114;
        int centerX = leftX + leftW + 8;
        int centerW = 164;
        int rightX = centerX + centerW + 8;
        int rightW = (this.panelX + this.panelW - 8) - rightX;

        int totalModels = ZombieVariantRegistry.getTotalVariants();
        int btnH = Math.max(18, (this.bodyBottom - this.bodyTop - 18) / totalModels);
        for (int i = 0; i < totalModels; i++) {
            final int modelId = i;
            ZombieVariantRegistry.ZombieVariant variant = ZombieVariantRegistry.getVariant(i);
            String label = (i == this.selectedModelId ? "> " : "") + variant.displayName();
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                syncStatsInputsToDraft();
                this.selectedModelId = modelId;
                this.previewTextureId = 0;
                this.rebuildWidgets();
            }).bounds(leftX, this.bodyTop + i * btnH, leftW, btnH - 2).build());
        }

        this.previewBoxX = centerX;
        this.previewBoxY = this.bodyTop;
        this.previewBoxW = centerW;
        this.previewBoxH = (this.bodyBottom - this.bodyTop) - 46;

        int texCount = ZombieVariantRegistry.getTextureCount(this.selectedModelId);
        this.previewTextureId = Math.max(0, Math.min(this.previewTextureId, Math.max(0, texCount - 1)));
        int texBarY = this.previewBoxY + this.previewBoxH + 4;

        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            int count = ZombieVariantRegistry.getTextureCount(this.selectedModelId);
            if (count > 0) {
                this.previewTextureId = (this.previewTextureId - 1 + count) % count;
            }
        }).bounds(centerX, texBarY, 24, 18).build());

        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            int count = ZombieVariantRegistry.getTextureCount(this.selectedModelId);
            if (count > 0) {
                this.previewTextureId = (this.previewTextureId + 1) % count;
            }
        }).bounds(centerX + centerW - 24, texBarY, 24, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Invocar Aqui"), b -> {
            PacketHandler.sendToServer(new C2SSpawnTestZombiePacket(this.selectedModelId, this.previewTextureId));
            setStatus("Zombie de prueba invocado frente a ti.", COL_TEXT_GOOD);
        }).bounds(centerX, texBarY + 22, centerW, 18).build());

        ZombieModelStats stats = this.draftStatsMap.getOrDefault(this.selectedModelId, ZombieModelStats.defaults());
        int colW = (rightW - 6) / 2;
        int col2X = rightX + colW + 6;
        int y = this.bodyTop + 10;

        this.statHealthInput = addEditBox(rightX, y, colW, 14, formatDouble(stats.maxHealth()), 8, null);
        this.statSpeedInput = addEditBox(col2X, y, colW, 14, formatDouble(stats.movementSpeed()), 8, null);

        y += 24;
        this.statDamageInput = addEditBox(rightX, y, colW, 14, formatDouble(stats.attackDamage()), 8, null);
        this.statFollowInput = addEditBox(col2X, y, colW, 14, formatDouble(stats.followRange()), 8, null);

        y += 24;
        this.statKnockbackInput = addEditBox(rightX, y, colW, 14, formatDouble(stats.knockbackResistance() * 100.0D), 6, null);
        this.statXpInput = addEditBox(col2X, y, colW, 14, String.valueOf(stats.xpReward()), 6, null);

        y += 18;
        addRenderableWidget(new NumericSlider(
                rightX, y, rightW, 15,
                0.6D, 2.0D, 0.05D, stats.scale(),
                val -> Component.literal(String.format(Locale.US, "Escala / Tamano: %.2fx", val)),
                val -> {
                    syncStatsInputsToDraft();
                    ZombieModelStats cur = this.draftStatsMap.getOrDefault(this.selectedModelId, ZombieModelStats.defaults());
                    this.draftStatsMap.put(this.selectedModelId, withScale(cur, (float) val));
                }
        ));

        y += 26;
        this.statNameInput = addEditBox(rightX, y, colW, 14, stats.customDisplayName(), 64, null);
        this.statLootInput = addEditBox(col2X, y, colW, 14, stats.customLootTable(), 128, null);

        y += 18;
        addRenderableWidget(Button.builder(
                Component.literal("Inmune Sol: " + (stats.immuneToSunlight() ? "SI" : "NO")),
                b -> toggleStatFlag(b, "Inmune Sol: ", StatFlag.SUNLIGHT)
        ).bounds(rightX, y, colW, 15).build());

        addRenderableWidget(Button.builder(
                Component.literal("Inmune Caidas: " + (stats.immuneToFallDamage() ? "SI" : "NO")),
                b -> toggleStatFlag(b, "Inmune Caidas: ", StatFlag.FALL)
        ).bounds(col2X, y, colW, 15).build());

        y += 17;
        addRenderableWidget(Button.builder(
                Component.literal("Persistente: " + (stats.persistent() ? "SI" : "NO")),
                b -> toggleStatFlag(b, "Persistente: ", StatFlag.PERSISTENT)
        ).bounds(rightX, y, colW, 15).build());

        addRenderableWidget(Button.builder(
                Component.literal("BossBar: " + (stats.showBossBar() ? "SI" : "NO")),
                b -> toggleStatFlag(b, "BossBar: ", StatFlag.BOSSBAR)
        ).bounds(col2X, y, colW, 15).build());

        addRenderableWidget(Button.builder(Component.literal("Guardar Stats"), b -> {
            syncStatsInputsToDraft();
            PacketHandler.sendToServer(new C2SUpdateModelStatsPacket(this.draftStatsMap));
            setStatus("Estadisticas guardadas y aplicadas en el servidor.", COL_TEXT_GOOD);
        }).bounds(rightX, this.bodyBottom - 19, rightW, 18).build());
    }

    private enum StatFlag {
        SUNLIGHT, FALL, PERSISTENT, BOSSBAR
    }

    private void toggleStatFlag(Button button, String prefix, StatFlag flag) {
        syncStatsInputsToDraft();
        ZombieModelStats cur = this.draftStatsMap.getOrDefault(this.selectedModelId, ZombieModelStats.defaults());
        ZombieModelStats updated = switch (flag) {
            case SUNLIGHT -> new ZombieModelStats(cur.maxHealth(), cur.movementSpeed(), cur.attackDamage(),
                    cur.followRange(), cur.knockbackResistance(), cur.scale(), !cur.immuneToSunlight(),
                    cur.immuneToFallDamage(), cur.persistent(), cur.xpReward(), cur.customLootTable(),
                    cur.customDisplayName(), cur.showBossBar());
            case FALL -> new ZombieModelStats(cur.maxHealth(), cur.movementSpeed(), cur.attackDamage(),
                    cur.followRange(), cur.knockbackResistance(), cur.scale(), cur.immuneToSunlight(),
                    !cur.immuneToFallDamage(), cur.persistent(), cur.xpReward(), cur.customLootTable(),
                    cur.customDisplayName(), cur.showBossBar());
            case PERSISTENT -> new ZombieModelStats(cur.maxHealth(), cur.movementSpeed(), cur.attackDamage(),
                    cur.followRange(), cur.knockbackResistance(), cur.scale(), cur.immuneToSunlight(),
                    cur.immuneToFallDamage(), !cur.persistent(), cur.xpReward(), cur.customLootTable(),
                    cur.customDisplayName(), cur.showBossBar());
            case BOSSBAR -> new ZombieModelStats(cur.maxHealth(), cur.movementSpeed(), cur.attackDamage(),
                    cur.followRange(), cur.knockbackResistance(), cur.scale(), cur.immuneToSunlight(),
                    cur.immuneToFallDamage(), cur.persistent(), cur.xpReward(), cur.customLootTable(),
                    cur.customDisplayName(), !cur.showBossBar());
        };
        this.draftStatsMap.put(this.selectedModelId, updated);
        boolean newState = switch (flag) {
            case SUNLIGHT -> updated.immuneToSunlight();
            case FALL -> updated.immuneToFallDamage();
            case PERSISTENT -> updated.persistent();
            case BOSSBAR -> updated.showBossBar();
        };
        button.setMessage(Component.literal(prefix + (newState ? "SI" : "NO")));
    }

    private static ZombieModelStats withScale(ZombieModelStats cur, float scale) {
        return new ZombieModelStats(
                cur.maxHealth(), cur.movementSpeed(), cur.attackDamage(),
                cur.followRange(), cur.knockbackResistance(), scale,
                cur.immuneToSunlight(), cur.immuneToFallDamage(), cur.persistent(),
                cur.xpReward(), cur.customLootTable(), cur.customDisplayName(), cur.showBossBar()
        );
    }

    // ==================== SHARED WEIGHT SLIDERS ====================

    private int buildWeightSliders(
            Map<Integer, Integer> weights,
            boolean onlyActive,
            int scroll,
            int x,
            int y,
            int w,
            int h
    ) {
        List<Integer> modelIds = getFilteredModelIds(weights, onlyActive);
        int rowH = 19;
        int visibleRows = Math.max(1, h / rowH);
        int maxScroll = Math.max(0, modelIds.size() - visibleRows);
        int clampedScroll = Math.max(0, Math.min(scroll, maxScroll));

        int sliderX = x + 76;
        int sliderW = Math.max(60, w - 76 - 48);

        for (int i = 0; i < visibleRows && (i + clampedScroll) < modelIds.size(); i++) {
            int modelId = modelIds.get(i + clampedScroll);
            int rowY = y + i * rowH;
            int initialWeight = weights.getOrDefault(modelId, 0);

            NumericSlider slider = new NumericSlider(
                    sliderX, rowY, sliderW, 16,
                    0.0D, 100.0D, 1.0D, initialWeight,
                    val -> Component.literal("Peso: " + (int) Math.round(val)),
                    val -> {
                        weights.put(modelId, (int) Math.round(val));
                        refreshAllWeightSliderMessages();
                    }
            );
            this.activeWeightSliders.add(slider);
            addRenderableWidget(slider);
        }
        return clampedScroll;
    }

    private void refreshAllWeightSliderMessages() {
        for (NumericSlider slider : this.activeWeightSliders) {
            slider.refreshMessage();
        }
    }

    private static List<Integer> getFilteredModelIds(Map<Integer, Integer> weights, boolean onlyActive) {
        List<Integer> ids = new ArrayList<>();
        int total = ZombieVariantRegistry.getTotalVariants();
        for (int i = 0; i < total; i++) {
            if (!onlyActive || weights.getOrDefault(i, 0) > 0) {
                ids.add(i);
            }
        }
        return ids;
    }

    private EditBox addEditBox(
            int x, int y, int w, int h,
            String initialValue,
            int maxLen,
            java.util.function.Consumer<String> responder
    ) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.empty());
        box.setMaxLength(maxLen);
        box.setValue(initialValue != null ? initialValue : "");
        if (responder != null) {
            box.setResponder(responder);
        }
        this.trackedEditBoxes.add(box);
        addRenderableWidget(box);
        return box;
    }

    // ==================== TICK, RENDER, INPUT ====================

    @Override
    public void tick() {
        super.tick();
        for (EditBox box : this.trackedEditBoxes) {
            box.tick();
        }
        if (this.previewZombie != null && this.activeTab == Tab.ZOMBIE_STATS) {
            this.previewZombie.tickCount++;
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, COL_BACKDROP);
        guiGraphics.fill(this.panelX, this.panelY, this.panelX + this.panelW, this.panelY + this.panelH, COL_PANEL_BG);
        drawBorder(guiGraphics, this.panelX, this.panelY, this.panelW, this.panelH, COL_BORDER);
        guiGraphics.drawCenteredString(this.font, this.title, this.panelX + this.panelW / 2, this.panelY + 5, COL_TEXT_PRIMARY);
        guiGraphics.hLine(this.panelX + 1, this.panelX + this.panelW - 2, this.panelY + 39, COL_BORDER);
        guiGraphics.hLine(this.panelX + 1, this.panelX + this.panelW - 2, this.bodyBottom + 2, COL_BORDER);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        switch (this.activeTab) {
            case SPECIFIC_ZONES -> renderSpecificZonesOverlay(guiGraphics);
            case GLOBAL_ZONE -> renderGlobalZoneOverlay(guiGraphics);
            case ZOMBIE_STATS -> renderZombieStatsOverlay(guiGraphics);
        }

        guiGraphics.drawString(
                this.font,
                trimText(this.statusMessage, 40),
                this.panelX + 10,
                this.panelY + this.panelH - 19,
                this.statusColor,
                false
        );
    }

    private void renderSpecificZonesOverlay(GuiGraphics guiGraphics) {
        int leftW = Math.max(185, (int) (this.panelW * 0.34F));
        int leftX = this.panelX + 8;
        int rightX = leftX + leftW + 8;
        int rightW = this.panelX + this.panelW - 8 - rightX;

        if (getClientWandVertices().size() >= 3) {
            drawBorder(guiGraphics, this.createBtnX - 1, this.createBtnY - 1, this.createBtnW + 2, this.createBtnH + 2, COL_TEXT_GOOD);
        }

        guiGraphics.fill(this.zoneListX, this.zoneListY, this.zoneListX + this.zoneListW, this.bodyBottom, COL_CARD_BG);
        drawBorder(guiGraphics, this.zoneListX, this.zoneListY, this.zoneListW, this.bodyBottom - this.zoneListY, COL_BORDER);

        List<SpawnZone> zones = ClientZoneCache.getZones();
        int cardH = 38;
        int visibleCards = Math.max(1, this.zoneListH / cardH);
        for (int i = 0; i < visibleCards && (i + this.zoneListScroll) < zones.size(); i++) {
            SpawnZone zone = zones.get(i + this.zoneListScroll);
            int cardY = this.zoneListY + i * cardH;
            if (zone.getId().equals(this.selectedZoneId)) {
                drawBorder(guiGraphics, leftX + 1, cardY + 1, leftW - 2, cardH - 2, COL_ACCENT);
            }
            int color = 0xFF000000 | zone.getColor();
            guiGraphics.fill(leftX + 3, cardY + 4, leftX + 10, cardY + 16, color);
        }

        if (this.zoneDraft == null) {
            guiGraphics.drawCenteredString(
                    this.font,
                    "No hay zonas. Marca 3+ puntos con la varita y pulsa '+ Crear Zona'.",
                    rightX + rightW / 2,
                    this.bodyTop + 60,
                    COL_TEXT_MUTED
            );
            return;
        }

        // Color palette swatches
        guiGraphics.drawString(this.font, "Color:", rightX, this.colorPaletteY + 2, COL_TEXT_MUTED, false);
        for (int i = 0; i < COLOR_PALETTE.length; i++) {
            int sx = this.colorPaletteX + i * 12;
            int sy = this.colorPaletteY;
            guiGraphics.fill(sx, sy, sx + 10, sy + 10, 0xFF000000 | COLOR_PALETTE[i]);
            if ((this.zoneDraft.color & 0xFFFFFF) == COLOR_PALETTE[i]) {
                drawBorder(guiGraphics, sx - 1, sy - 1, 12, 12, 0xFFFFFFFF);
            }
        }

        // Numeric labels
        int paramY = this.bodyTop + 42;
        int colW = (rightW - 8) / 3;
        guiGraphics.drawString(this.font, "Max:", rightX, paramY + 4, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "Int(s):", rightX + colW * 2 + 6, paramY + 4, COL_TEXT_MUTED, false);

        // Y Min / Y Max labels
        int heightY = this.bodyTop + 62;
        guiGraphics.drawString(this.font, "Y Min:", rightX, heightY + 4, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "Y Max:", rightX + 78, heightY + 4, COL_TEXT_MUTED, false);

        // Polygon info card
        guiGraphics.fill(this.polyCardX, this.polyCardY, this.polyCardX + this.polyCardW, this.polyCardY + this.polyCardH, COL_CARD_BG);
        drawBorder(guiGraphics, this.polyCardX, this.polyCardY, this.polyCardW, this.polyCardH, COL_BORDER);
        String polySummary = "Poligono: " + this.zoneDraft.vertices.size() + " vertices | Dim: " + this.zoneDraft.dimension.location().getPath();
        guiGraphics.drawString(this.font, trimText(polySummary, 54), this.polyCardX + 6, this.polyCardY + 4, COL_TEXT_PRIMARY, false);

        // Weights header & rows
        guiGraphics.drawString(this.font, "Modelos Permitidos (Peso 0-100):", rightX, this.weightListY - 13, COL_TEXT_PRIMARY, false);
        renderWeightRowsOverlay(guiGraphics, this.zoneDraft.weights, this.zoneOnlyActiveModels, this.zoneWeightScroll);
    }

    private void renderGlobalZoneOverlay(GuiGraphics guiGraphics) {
        int leftX = this.panelX + 10;
        int fullW = this.panelW - 20;
        int colW = (fullW - 12) / 4;
        int y = this.bodyTop + 26;

        guiGraphics.drawString(
                this.font,
                "Aplica automaticamente fuera de zonas especificas (bosques, carreteras). Si esta OFF, no habra spawns fuera de zonas.",
                leftX,
                this.bodyTop + 4,
                COL_TEXT_MUTED,
                false
        );

        guiGraphics.drawString(this.font, "Max(64m):", leftX + colW + 4, y + 5, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "Int(s):", leftX + colW * 3 + 8, y + 5, COL_TEXT_MUTED, false);

        renderWeightRowsOverlay(guiGraphics, this.globalDraft.weights, this.globalOnlyActiveModels, this.globalWeightScroll);
    }

    private void renderWeightRowsOverlay(
            GuiGraphics guiGraphics,
            Map<Integer, Integer> weights,
            boolean onlyActive,
            int scroll
    ) {
        guiGraphics.fill(this.weightListX, this.weightListY, this.weightListX + this.weightListW, this.weightListY + this.weightListH, COL_CARD_BG);
        drawBorder(guiGraphics, this.weightListX, this.weightListY, this.weightListW, this.weightListH, COL_BORDER);

        List<Integer> ids = getFilteredModelIds(weights, onlyActive);
        int totalWeight = SpawnZone.computeTotalWeight(weights);
        int rowH = 19;
        int visibleRows = Math.max(1, this.weightListH / rowH);

        for (int i = 0; i < visibleRows && (i + scroll) < ids.size(); i++) {
            int modelId = ids.get(i + scroll);
            int rowY = this.weightListY + i * rowH;
            int w = weights.getOrDefault(modelId, 0);
            double pct = totalWeight > 0 ? (w * 100.0D) / totalWeight : 0.0D;

            String modelName = ZombieVariantRegistry.getVariant(modelId).displayName();
            guiGraphics.drawString(this.font, modelName, this.weightListX + 6, rowY + 4, w > 0 ? COL_TEXT_PRIMARY : COL_TEXT_MUTED, false);

            String pctStr = String.format(Locale.US, "%.1f%%", pct);
            guiGraphics.drawString(
                    this.font,
                    pctStr,
                    this.weightListX + this.weightListW - 42,
                    rowY + 4,
                    w > 0 ? COL_TEXT_GOOD : COL_TEXT_MUTED,
                    false
            );
        }
    }

    private void renderZombieStatsOverlay(GuiGraphics guiGraphics) {
        guiGraphics.fill(this.previewBoxX, this.previewBoxY, this.previewBoxX + this.previewBoxW, this.previewBoxY + this.previewBoxH, 0xFF090C10);
        drawBorder(guiGraphics, this.previewBoxX, this.previewBoxY, this.previewBoxW, this.previewBoxH, COL_BORDER);

        ZombieVariantRegistry.ZombieVariant variant = ZombieVariantRegistry.getVariant(this.selectedModelId);
        guiGraphics.drawCenteredString(this.font, variant.displayName(), this.previewBoxX + this.previewBoxW / 2, this.previewBoxY + 6, COL_TEXT_PRIMARY);

        renderEntityPreview3D(guiGraphics);

        int texCount = ZombieVariantRegistry.getTextureCount(this.selectedModelId);
        int texBarY = this.previewBoxY + this.previewBoxH + 4;
        String texLabel = "Textura " + (this.previewTextureId + 1) + "/" + Math.max(1, texCount);
        guiGraphics.drawCenteredString(this.font, texLabel, this.previewBoxX + this.previewBoxW / 2, texBarY + 5, COL_TEXT_PRIMARY);

        int rightX = this.previewBoxX + this.previewBoxW + 8;
        int rightW = (this.panelX + this.panelW - 8) - rightX;
        int colW = (rightW - 6) / 2;
        int col2X = rightX + colW + 6;
        int y = this.bodyTop + 1;

        guiGraphics.drawString(this.font, "Vida Maxima:", rightX, y, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "Velocidad:", col2X, y, COL_TEXT_MUTED, false);

        y += 24;
        guiGraphics.drawString(this.font, "Dano Ataque:", rightX, y, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "Rango Detec. (5-80):", col2X, y, COL_TEXT_MUTED, false);

        y += 24;
        guiGraphics.drawString(this.font, "Knockback Res (%):", rightX, y, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "XP al morir:", col2X, y, COL_TEXT_MUTED, false);

        y += 44;
        guiGraphics.drawString(this.font, "Nombre Flotante (opc):", rightX, y, COL_TEXT_MUTED, false);
        guiGraphics.drawString(this.font, "Loot Table (opc):", col2X, y, COL_TEXT_MUTED, false);
    }

    private void renderEntityPreview3D(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (this.previewZombie == null || this.previewZombie.level() != mc.level) {
            this.previewZombie = ModEntities.CUSTOM_ZOMBIE.get().create(mc.level);
        }
        if (this.previewZombie == null) {
            return;
        }

        ZombieModelStats draftStats = this.draftStatsMap.getOrDefault(this.selectedModelId, ZombieModelStats.defaults());
        this.previewZombie.setModelId(this.selectedModelId);
        this.previewZombie.setTextureId(this.previewTextureId);
        this.previewZombie.setModelScale(draftStats.scale());
        this.previewZombie.setCustomName(null);
        this.previewZombie.setCustomNameVisible(false);

        float yaw = ((System.currentTimeMillis() % 6500L) / 6500.0F) * 360.0F;
        this.previewZombie.yBodyRot = yaw;
        this.previewZombie.yBodyRotO = yaw;
        this.previewZombie.setYRot(yaw);
        this.previewZombie.setYHeadRot(yaw);
        this.previewZombie.yHeadRotO = yaw;
        this.previewZombie.setXRot(0.0F);
        this.previewZombie.xRotO = 0.0F;

        int centerX = this.previewBoxX + this.previewBoxW / 2;
        int footY = this.previewBoxY + this.previewBoxH - 16;

        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf cam = new Quaternionf().rotateX(0.18F);
        pose.mul(cam);

        guiGraphics.enableScissor(
                this.previewBoxX + 1,
                this.previewBoxY + 18,
                this.previewBoxX + this.previewBoxW - 1,
                this.previewBoxY + this.previewBoxH - 1
        );
        try {
            InventoryScreen.renderEntityInInventory(guiGraphics, centerX, footY, 44, pose, cam, this.previewZombie);
        } finally {
            guiGraphics.disableScissor();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.activeTab == Tab.SPECIFIC_ZONES && this.zoneDraft != null && button == 0) {
            for (int i = 0; i < COLOR_PALETTE.length; i++) {
                int sx = this.colorPaletteX + i * 12;
                int sy = this.colorPaletteY;
                if (mouseX >= sx && mouseX <= sx + 10 && mouseY >= sy && mouseY <= sy + 10) {
                    this.zoneDraft.color = COLOR_PALETTE[i];
                    if (this.zoneHexInput != null) {
                        this.zoneHexInput.setValue(formatHex(this.zoneDraft.color));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int step = delta > 0 ? -1 : 1;

        if (this.activeTab == Tab.SPECIFIC_ZONES) {
            if (isInside(mouseX, mouseY, this.zoneListX, this.zoneListY, this.zoneListW, this.zoneListH)) {
                this.zoneListScroll = Math.max(0, this.zoneListScroll + step);
                rebuildWidgetsPreservingScroll();
                return true;
            }
            if (isInside(mouseX, mouseY, this.weightListX, this.weightListY, this.weightListW, this.weightListH)) {
                this.zoneWeightScroll = Math.max(0, this.zoneWeightScroll + step);
                rebuildWidgetsPreservingScroll();
                return true;
            }
        } else if (this.activeTab == Tab.GLOBAL_ZONE) {
            if (isInside(mouseX, mouseY, this.weightListX, this.weightListY, this.weightListW, this.weightListH)) {
                this.globalWeightScroll = Math.max(0, this.globalWeightScroll + step);
                rebuildWidgetsPreservingScroll();
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== HELPERS & DRAFTS ====================

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.hLine(x, x + w - 1, y, color);
        g.hLine(x, x + w - 1, y + h - 1, color);
        g.vLine(x, y, y + h - 1, color);
        g.vLine(x + w - 1, y, y + h - 1, color);
    }

    private static String trimText(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, Math.max(0, maxChars - 2)) + "..";
    }

    private static String formatHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static Integer parseHexColor(String text) {
        if (text == null) {
            return null;
        }
        String clean = text.trim();
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(clean, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntOr(String s, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatDouble(double val) {
        if (Math.abs(val - Math.rint(val)) < 1.0E-4D) {
            return String.format(Locale.US, "%.1f", val);
        }
        return String.format(Locale.US, "%.2f", val);
    }

    private static final class NumericSlider extends AbstractSliderButton {

        private final double minValue;
        private final double maxValue;
        private final double step;
        private final Function<Double, Component> labelProvider;
        private final DoubleConsumer onChange;
        private double currentValue;

        NumericSlider(
                int x, int y, int width, int height,
                double minValue, double maxValue, double step, double initialValue,
                Function<Double, Component> labelProvider,
                DoubleConsumer onChange
        ) {
            super(x, y, width, height, Component.empty(), 0.0D);
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.step = step;
            this.labelProvider = labelProvider;
            this.onChange = onChange;
            this.currentValue = clampAndSnap(initialValue);
            this.value = (this.currentValue - minValue) / Math.max(1.0E-6D, maxValue - minValue);
            updateMessage();
        }

        private double clampAndSnap(double raw) {
            double clamped = Math.max(this.minValue, Math.min(this.maxValue, raw));
            if (this.step > 0.0D) {
                clamped = Math.round((clamped - this.minValue) / this.step) * this.step + this.minValue;
                clamped = Math.max(this.minValue, Math.min(this.maxValue, clamped));
            }
            return clamped;
        }

        void refreshMessage() {
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(this.labelProvider.apply(this.currentValue));
        }

        @Override
        protected void applyValue() {
            this.currentValue = clampAndSnap(this.minValue + this.value * (this.maxValue - this.minValue));
            this.onChange.accept(this.currentValue);
            updateMessage();
        }
    }

    private static final class ZoneDraft {
        final UUID id;
        String name;
        int color;
        ResourceKey<Level> dimension;
        final List<BlockPos> vertices;
        int minY;
        int maxY;
        int maxZombies;
        float spawnRateMultiplier;
        ZoneTimeCondition timeCondition;
        boolean ignoreLightLevel;
        int spawnIntervalSeconds;
        final Map<Integer, Integer> weights;
        boolean enabled;

        private ZoneDraft(
                UUID id, String name, int color, ResourceKey<Level> dimension,
                List<BlockPos> vertices, int minY, int maxY, int maxZombies, float spawnRateMultiplier,
                ZoneTimeCondition timeCondition, boolean ignoreLightLevel,
                int spawnIntervalSeconds, Map<Integer, Integer> weights, boolean enabled
        ) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.dimension = dimension;
            this.vertices = new ArrayList<>(vertices);
            this.minY = minY;
            this.maxY = maxY;
            this.maxZombies = maxZombies;
            this.spawnRateMultiplier = spawnRateMultiplier;
            this.timeCondition = timeCondition;
            this.ignoreLightLevel = ignoreLightLevel;
            this.spawnIntervalSeconds = spawnIntervalSeconds;
            this.weights = new LinkedHashMap<>(weights);
            this.enabled = enabled;
        }

        static ZoneDraft from(SpawnZone zone) {
            return new ZoneDraft(
                    zone.getId(),
                    zone.getName(),
                    zone.getColor(),
                    zone.getDimension(),
                    zone.getVertices(),
                    zone.getMinY(),
                    zone.getMaxY(),
                    zone.getMaxZombies(),
                    zone.getSpawnRateMultiplier(),
                    zone.getTimeCondition(),
                    zone.isIgnoreLightLevel(),
                    zone.getSpawnIntervalSeconds(),
                    zone.getAllowedModelWeights(),
                    zone.isEnabled()
            );
        }

        void copyRulesFrom(SpawnZone source) {
            this.maxZombies = source.getMaxZombies();
            this.spawnRateMultiplier = source.getSpawnRateMultiplier();
            this.timeCondition = source.getTimeCondition();
            this.ignoreLightLevel = source.isIgnoreLightLevel();
            this.spawnIntervalSeconds = source.getSpawnIntervalSeconds();
            this.weights.clear();
            this.weights.putAll(source.getAllowedModelWeights());
        }

        SpawnZone toSpawnZone() {
            return new SpawnZone(
                    this.id, this.name, this.color, this.dimension, this.vertices,
                    this.minY, this.maxY, this.maxZombies, this.spawnRateMultiplier,
                    this.timeCondition, this.ignoreLightLevel, this.spawnIntervalSeconds,
                    this.weights, this.enabled
            );
        }
    }

    private static final class GlobalDraft {
        boolean enabled;
        int maxZombiesPerArea;
        float spawnRateMultiplier;
        ZoneTimeCondition timeCondition;
        boolean ignoreLightLevel;
        int spawnIntervalSeconds;
        final Map<Integer, Integer> weights;

        private GlobalDraft(
                boolean enabled, int maxZombiesPerArea, float spawnRateMultiplier,
                ZoneTimeCondition timeCondition, boolean ignoreLightLevel,
                int spawnIntervalSeconds, Map<Integer, Integer> weights
        ) {
            this.enabled = enabled;
            this.maxZombiesPerArea = maxZombiesPerArea;
            this.spawnRateMultiplier = spawnRateMultiplier;
            this.timeCondition = timeCondition;
            this.ignoreLightLevel = ignoreLightLevel;
            this.spawnIntervalSeconds = spawnIntervalSeconds;
            this.weights = new LinkedHashMap<>(weights);
        }

        static GlobalDraft from(GlobalSpawnZone global) {
            return new GlobalDraft(
                    global.isEnabled(),
                    global.getMaxZombiesPerArea(),
                    global.getSpawnRateMultiplier(),
                    global.getTimeCondition(),
                    global.isIgnoreLightLevel(),
                    global.getSpawnIntervalSeconds(),
                    global.getAllowedModelWeights()
            );
        }

        GlobalSpawnZone toGlobalZone() {
            return new GlobalSpawnZone(
                    this.enabled, this.maxZombiesPerArea, this.spawnRateMultiplier,
                    this.timeCondition, this.ignoreLightLevel, this.spawnIntervalSeconds, this.weights
            );
        }
    }
}
