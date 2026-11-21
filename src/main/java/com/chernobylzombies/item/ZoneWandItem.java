package com.chernobylzombies.item;

import com.chernobylzombies.network.PacketHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Administrative polygon-tracing wand:
 * - Right Click block: add polygon vertex
 * - Left Click block: undo last vertex
 * - Shift + Left Click (block or air): clear all vertices
 * - Shift + Right Click (air or block): open ZoneManagerScreen
 */
public class ZoneWandItem extends Item {

    private static final String TAG_VERTICES = "Vertices";
    private static final String TAG_DIMENSION = "Dimension";

    public ZoneWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                if (!serverPlayer.hasPermissions(2)) {
                    serverPlayer.displayClientMessage(
                            Component.literal("No tienes permisos para abrir el panel de zonas.").withStyle(ChatFormatting.RED),
                            true
                    );
                    return InteractionResultHolder.fail(stack);
                }
                PacketHandler.sendZonesAndStatsToPlayer(serverPlayer, true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            if (!player.hasPermissions(2)) {
                player.displayClientMessage(
                        Component.literal("No tienes permisos para usar esta varita.").withStyle(ChatFormatting.RED),
                        true
                );
                return InteractionResult.FAIL;
            }
            addVertex(context.getItemInHand(), player, context.getClickedPos(), level.dimension());
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    public static void addVertex(ItemStack stack, Player player, BlockPos pos, ResourceKey<Level> dimension) {
        List<BlockPos> vertices = getVertices(stack);
        Optional<ResourceKey<Level>> existingDim = getDimension(stack);
        if (existingDim.isPresent() && !existingDim.get().equals(dimension) && !vertices.isEmpty()) {
            vertices.clear();
        }
        vertices.add(pos.immutable());
        saveVertices(stack, vertices, dimension);

        int count = vertices.size();
        String readyText = count >= 3 ? " (Listo para crear zona)" : " (Minimo 3 puntos)";
        player.displayClientMessage(
                Component.literal("Punto " + count + " anadido: [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]" + readyText)
                        .withStyle(count >= 3 ? ChatFormatting.GREEN : ChatFormatting.AQUA),
                true
        );
    }

    public static void removeLastVertex(ItemStack stack, Player player) {
        List<BlockPos> vertices = getVertices(stack);
        if (vertices.isEmpty()) {
            player.displayClientMessage(
                    Component.literal("No hay puntos marcados para deshacer.").withStyle(ChatFormatting.YELLOW),
                    true
            );
            return;
        }
        BlockPos removed = vertices.remove(vertices.size() - 1);
        saveVertices(stack, vertices, getDimension(stack).orElse(null));
        player.displayClientMessage(
                Component.literal("Punto [" + removed.getX() + ", " + removed.getY() + ", " + removed.getZ()
                        + "] eliminado. Restantes: " + vertices.size())
                        .withStyle(ChatFormatting.YELLOW),
                true
        );
    }

    public static void clearVertices(ItemStack stack, Player player) {
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(TAG_VERTICES);
            tag.remove(TAG_DIMENSION);
        }
        player.displayClientMessage(
                Component.literal("Todos los puntos de la varita han sido limpiados.").withStyle(ChatFormatting.GOLD),
                true
        );
    }

    private static void saveVertices(ItemStack stack, List<BlockPos> vertices, @Nullable ResourceKey<Level> dimension) {
        CompoundTag tag = stack.getOrCreateTag();
        if (vertices.isEmpty()) {
            tag.remove(TAG_VERTICES);
            tag.remove(TAG_DIMENSION);
            return;
        }
        long[] packed = new long[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            packed[i] = vertices.get(i).asLong();
        }
        tag.putLongArray(TAG_VERTICES, packed);
        if (dimension != null) {
            tag.putString(TAG_DIMENSION, dimension.location().toString());
        }
    }

    public static List<BlockPos> getVertices(ItemStack stack) {
        List<BlockPos> list = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_VERTICES)) {
            return list;
        }
        long[] packed = tag.getLongArray(TAG_VERTICES);
        for (long value : packed) {
            list.add(BlockPos.of(value));
        }
        return list;
    }

    public static Optional<ResourceKey<Level>> getDimension(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_DIMENSION)) {
            return Optional.empty();
        }
        ResourceLocation dimLoc = ResourceLocation.tryParse(tag.getString(TAG_DIMENSION));
        if (dimLoc == null) {
            return Optional.empty();
        }
        return Optional.of(ResourceKey.create(Registries.DIMENSION, dimLoc));
    }

    public static int computeDefaultMinY(List<BlockPos> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return 0;
        }
        int minY = Integer.MAX_VALUE;
        for (BlockPos p : vertices) {
            minY = Math.min(minY, p.getY());
        }
        return minY;
    }

    public static int computeDefaultMaxY(List<BlockPos> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return 15;
        }
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : vertices) {
            maxY = Math.max(maxY, p.getY());
        }
        return maxY + 15;
    }

    public static ItemStack findWand(Player player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof ZoneWandItem) {
            return main;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        if (off.getItem() instanceof ZoneWandItem) {
            return off;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof ZoneWandItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Click Der.: Anadir punto | Click Izq.: Deshacer").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift+Click Izq.: Limpiar | Shift+Click Der.: Panel").withStyle(ChatFormatting.GRAY));
        List<BlockPos> vertices = getVertices(stack);
        ChatFormatting statusColor = vertices.size() >= 3 ? ChatFormatting.GREEN : ChatFormatting.AQUA;
        tooltip.add(Component.literal("Puntos marcados: " + vertices.size()).withStyle(statusColor));
        getDimension(stack).ifPresent(dim ->
                tooltip.add(Component.literal("Dimension: " + dim.location()).withStyle(ChatFormatting.DARK_GRAY))
        );
    }
}
