package com.chernobylzombies.client;

import com.chernobylzombies.ChernobylZombiesMod;
import com.chernobylzombies.item.ZoneWandItem;
import com.chernobylzombies.network.PacketHandler;
import com.chernobylzombies.network.packet.C2SWandClearPacket;
import com.chernobylzombies.zone.SpawnZone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

/**
 * Client-only 3D renderer and HUD for polygonal zones and ZoneWandItem selection.
 * Exits immediately on frame 0 when the player is not holding the wand, and culls zones > 128 blocks away.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ChernobylZombiesMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ZoneWorldRenderer {

    private static final double MAX_ZONE_RENDER_DISTANCE_SQ = 128.0D * 128.0D;

    private ZoneWorldRenderer() {
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (event.getHand() == InteractionHand.MAIN_HAND
                && event.getEntity().isShiftKeyDown()
                && event.getItemStack().getItem() instanceof ZoneWandItem) {
            PacketHandler.sendToServer(new C2SWandClearPacket());
        }
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null || mc.options.hideGui) {
            return;
        }

        ItemStack heldWand = getHeldWand(player);
        if (heldWand.isEmpty()) {
            return;
        }

        List<BlockPos> vertices = ZoneWandItem.getVertices(heldWand);
        int count = vertices.size();
        boolean ready = count >= 3;

        String line1 = "Varita de Zonas | Puntos marcados: " + count
                + (ready ? " (Listo para crear zona)" : " (Minimo 3 puntos)");
        String line2 = "Click Der: Anadir Punto | Click Izq: Deshacer | Shift+Click Izq: Limpiar | Shift+Click Der: Abrir Panel";

        GuiGraphics g = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int textW = Math.max(mc.font.width(line1), mc.font.width(line2));
        int boxW = textW + 16;
        int boxH = 26;
        int x = (screenW - boxW) / 2;
        int y = 6;

        int borderCol = ready ? 0xFF3FB950 : 0xFF38BDF8;
        g.fill(x, y, x + boxW, y + boxH, 0xB80D1117);
        g.hLine(x, x + boxW - 1, y, borderCol);
        g.hLine(x, x + boxW - 1, y + boxH - 1, borderCol);
        g.vLine(x, y, y + boxH - 1, borderCol);
        g.vLine(x + boxW - 1, y, y + boxH - 1, borderCol);

        g.drawCenteredString(mc.font, line1, screenW / 2, y + 4, ready ? 0xFF56D364 : 0xFF79C0FF);
        g.drawCenteredString(mc.font, line2, screenW / 2, y + 14, 0xFFC9D1D9);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        ItemStack heldWand = getHeldWand(player);
        if (heldWand.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        Vec3 playerPos = player.position();
        ResourceKey<Level> currentDim = mc.level.dimension();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        List<BlockPos> wandVertices = getWandVerticesForLevel(heldWand, currentDim);
        int wandMinY = ZoneWandItem.computeDefaultMinY(wandVertices);
        int wandMaxY = ZoneWandItem.computeDefaultMaxY(wandVertices);
        List<int[]> wandTriangles = wandVertices.size() >= 3 ? SpawnZone.triangulatePolygonXZ(wandVertices) : List.of();
        List<SpawnZone> zones = ClientZoneCache.getZones();

        // 1. Translucent faces (walls, floor, ceiling, vertex cubes)
        VertexConsumer quadConsumer = bufferSource.getBuffer(RenderType.debugQuads());
        for (int i = 0; i < zones.size(); i++) {
            SpawnZone zone = zones.get(i);
            if (!shouldRenderZone(zone, currentDim, playerPos)) {
                continue;
            }
            int color = zone.getColor();
            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float b = (color & 0xFF) / 255.0F;
            float faceAlpha = zone.isEnabled() ? 0.18F : 0.07F;

            renderPolygonFaces(poseStack, quadConsumer, zone.getVertices(), zone.getCachedTriangles(), zone.getMinY(), zone.getMaxY() + 1.0F, r, g, b, faceAlpha);
        }

        if (!wandVertices.isEmpty()) {
            if (wandVertices.size() >= 2) {
                renderPolygonFaces(poseStack, quadConsumer, wandVertices, wandTriangles, wandMinY, wandMaxY + 1.0F, 0.15F, 0.95F, 1.0F, 0.20F);
            }
            for (int i = 0; i < wandVertices.size(); i++) {
                BlockPos v = wandVertices.get(i);
                renderSmallCubeQuads(poseStack, quadConsumer, v.getX() + 0.5F, v.getY() + 0.5F, v.getZ() + 0.5F, 0.28F, 0.25F, 1.0F, 1.0F, 0.70F);
            }
        }
        bufferSource.endBatch(RenderType.debugQuads());

        // 2. Crisp wireframe edges & vertical pillars
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());
        for (int i = 0; i < zones.size(); i++) {
            SpawnZone zone = zones.get(i);
            if (!shouldRenderZone(zone, currentDim, playerPos)) {
                continue;
            }
            int color = zone.getColor();
            float r = ((color >> 16) & 0xFF) / 255.0F;
            float g = ((color >> 8) & 0xFF) / 255.0F;
            float b = (color & 0xFF) / 255.0F;
            float lineAlpha = zone.isEnabled() ? 0.85F : 0.40F;

            renderPolygonWireframe(poseStack, lineConsumer, zone.getVertices(), zone.getMinY(), zone.getMaxY() + 1.0F, r, g, b, lineAlpha, true);
        }

        if (!wandVertices.isEmpty()) {
            renderPolygonWireframe(poseStack, lineConsumer, wandVertices, wandMinY, wandMaxY + 1.0F, 0.20F, 1.0F, 1.0F, 0.95F, wandVertices.size() >= 3);
            for (int i = 0; i < wandVertices.size(); i++) {
                AABB markerBox = new AABB(wandVertices.get(i)).inflate(0.02D);
                LevelRenderer.renderLineBox(poseStack, lineConsumer, markerBox, 1.0F, 0.95F, 0.25F, 0.95F);
            }
        }
        bufferSource.endBatch(RenderType.lines());

        // 3. Billboard labels
        for (int i = 0; i < zones.size(); i++) {
            SpawnZone zone = zones.get(i);
            if (!shouldRenderZone(zone, currentDim, playerPos)) {
                continue;
            }
            int liveCount = ClientZoneCache.getZoneLiveCount(zone.getId());
            String statusSuffix = zone.isEnabled() ? "" : " (OFF)";
            String text = zone.getName() + " [" + liveCount + "/" + zone.getMaxZombies() + "]" + statusSuffix;
            BlockPos anchor = zone.getLabelAnchorPos();
            renderBillboardText(mc, poseStack, bufferSource, text, anchor.getX() + 0.5D, anchor.getY() + 0.6D, anchor.getZ() + 0.5D, 0xFF000000 | (zone.getColor() & 0xFFFFFF), cameraPos);
        }

        for (int i = 0; i < wandVertices.size(); i++) {
            BlockPos v = wandVertices.get(i);
            renderBillboardText(mc, poseStack, bufferSource, "P" + (i + 1), v.getX() + 0.5D, v.getY() + 1.35D, v.getZ() + 0.5D, 0xFF55FFFF, cameraPos);
        }
        bufferSource.endBatch();

        poseStack.popPose();
    }

    private static boolean shouldRenderZone(SpawnZone zone, ResourceKey<Level> currentDim, Vec3 playerPos) {
        if (!zone.getDimension().equals(currentDim) || zone.getVertices().size() < 3) {
            return false;
        }
        AABB box = zone.getBoundingBox();
        double dx = Math.max(0.0D, Math.max(box.minX - playerPos.x, playerPos.x - box.maxX));
        double dy = Math.max(0.0D, Math.max(box.minY - playerPos.y, playerPos.y - box.maxY));
        double dz = Math.max(0.0D, Math.max(box.minZ - playerPos.z, playerPos.z - box.maxZ));
        return (dx * dx + dy * dy + dz * dz) <= MAX_ZONE_RENDER_DISTANCE_SQ;
    }

    private static void renderPolygonFaces(
            PoseStack poseStack,
            VertexConsumer consumer,
            List<BlockPos> vertices,
            List<int[]> triangles,
            float yMin,
            float yMax,
            float r, float g, float b, float a
    ) {
        int n = vertices.size();
        if (n < 2) {
            return;
        }

        Matrix4f mat = poseStack.last().pose();
        int edgeCount = n >= 3 ? n : n - 1;
        for (int i = 0; i < edgeCount; i++) {
            BlockPos v1 = vertices.get(i);
            BlockPos v2 = vertices.get((i + 1) % n);
            float x1 = v1.getX() + 0.5F;
            float z1 = v1.getZ() + 0.5F;
            float x2 = v2.getX() + 0.5F;
            float z2 = v2.getZ() + 0.5F;

            consumer.vertex(mat, x1, yMin, z1).color(r, g, b, a).endVertex();
            consumer.vertex(mat, x2, yMin, z2).color(r, g, b, a).endVertex();
            consumer.vertex(mat, x2, yMax, z2).color(r, g, b, a).endVertex();
            consumer.vertex(mat, x1, yMax, z1).color(r, g, b, a).endVertex();
        }

        if (n >= 3 && !triangles.isEmpty()) {
            float capAlpha = a * 0.75F;
            for (int i = 0; i < triangles.size(); i++) {
                int[] tri = triangles.get(i);
                BlockPos p0 = vertices.get(tri[0]);
                BlockPos p1 = vertices.get(tri[1]);
                BlockPos p2 = vertices.get(tri[2]);

                emitTriangleQuad(mat, consumer, p0, p1, p2, yMin + 0.01F, r, g, b, capAlpha);
                emitTriangleQuad(mat, consumer, p0, p1, p2, yMax - 0.01F, r, g, b, capAlpha);
            }
        }
    }

    private static void emitTriangleQuad(
            Matrix4f mat, VertexConsumer consumer,
            BlockPos p0, BlockPos p1, BlockPos p2, float y,
            float r, float g, float b, float a
    ) {
        consumer.vertex(mat, p0.getX() + 0.5F, y, p0.getZ() + 0.5F).color(r, g, b, a).endVertex();
        consumer.vertex(mat, p1.getX() + 0.5F, y, p1.getZ() + 0.5F).color(r, g, b, a).endVertex();
        consumer.vertex(mat, p2.getX() + 0.5F, y, p2.getZ() + 0.5F).color(r, g, b, a).endVertex();
        consumer.vertex(mat, p2.getX() + 0.5F, y, p2.getZ() + 0.5F).color(r, g, b, a).endVertex();
    }

    private static void renderSmallCubeQuads(
            PoseStack poseStack, VertexConsumer consumer,
            float cx, float cy, float cz, float halfSize,
            float r, float g, float b, float a
    ) {
        Matrix4f mat = poseStack.last().pose();
        float x0 = cx - halfSize, x1 = cx + halfSize;
        float y0 = cy - halfSize, y1 = cy + halfSize;
        float z0 = cz - halfSize, z1 = cz + halfSize;

        emitQuad(mat, consumer, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        emitQuad(mat, consumer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        emitQuad(mat, consumer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        emitQuad(mat, consumer, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a);
        emitQuad(mat, consumer, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, b, a);
        emitQuad(mat, consumer, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    private static void emitQuad(
            Matrix4f mat, VertexConsumer c,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float r, float g, float b, float a
    ) {
        c.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        c.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        c.vertex(mat, x2, y2, z2).color(r, g, b, a).endVertex();
        c.vertex(mat, x3, y3, z3).color(r, g, b, a).endVertex();
    }

    private static void renderPolygonWireframe(
            PoseStack poseStack,
            VertexConsumer consumer,
            List<BlockPos> vertices,
            float yMin,
            float yMax,
            float r, float g, float b, float a,
            boolean closedLoop
    ) {
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            BlockPos v = vertices.get(i);
            float vx = v.getX() + 0.5F;
            float vz = v.getZ() + 0.5F;
            drawLine(poseStack, consumer, vx, yMin, vz, vx, yMax, vz, r, g, b, a);
        }

        if (n < 2) {
            return;
        }

        int edgeCount = closedLoop ? n : n - 1;
        for (int i = 0; i < edgeCount; i++) {
            BlockPos v1 = vertices.get(i);
            BlockPos v2 = vertices.get((i + 1) % n);
            float x1 = v1.getX() + 0.5F;
            float z1 = v1.getZ() + 0.5F;
            float x2 = v2.getX() + 0.5F;
            float z2 = v2.getZ() + 0.5F;

            drawLine(poseStack, consumer, x1, yMin, z1, x2, yMin, z2, r, g, b, a);
            drawLine(poseStack, consumer, x1, yMax, z1, x2, yMax, z2, r, g, b, a);
        }
    }

    private static void drawLine(
            PoseStack poseStack,
            VertexConsumer consumer,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float r, float g, float b, float a
    ) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5F) {
            return;
        }
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;
        PoseStack.Pose pose = poseStack.last();
        consumer.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
        consumer.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
    }

    private static void renderBillboardText(
            Minecraft mc,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            String text,
            double x,
            double y,
            double z,
            int argbColor,
            Vec3 cameraPos
    ) {
        double distSq = cameraPos.distanceToSqr(x, y, z);
        if (distSq > MAX_ZONE_RENDER_DISTANCE_SQ) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());

        float distance = (float) Math.sqrt(distSq);
        float textScale = Math.max(0.025F, Math.min(0.11F, 0.022F + distance * 0.0011F));
        poseStack.scale(-textScale, -textScale, textScale);

        Matrix4f matrix = poseStack.last().pose();
        float halfWidth = -mc.font.width(text) / 2.0F;

        mc.font.drawInBatch(
                text,
                halfWidth,
                0.0F,
                argbColor,
                false,
                matrix,
                bufferSource,
                Font.DisplayMode.SEE_THROUGH,
                0x90000000,
                LightTexture.FULL_BRIGHT
        );
        poseStack.popPose();
    }

    private static List<BlockPos> getWandVerticesForLevel(ItemStack wand, ResourceKey<Level> currentDimension) {
        List<BlockPos> vertices = ZoneWandItem.getVertices(wand);
        if (vertices.isEmpty()) {
            return List.of();
        }
        Optional<ResourceKey<Level>> dimOpt = ZoneWandItem.getDimension(wand);
        if (dimOpt.isPresent() && !dimOpt.get().equals(currentDimension)) {
            return List.of();
        }
        return vertices;
    }

    private static ItemStack getHeldWand(LocalPlayer player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof ZoneWandItem) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (off.getItem() instanceof ZoneWandItem) {
            return off;
        }
        return ItemStack.EMPTY;
    }
}
