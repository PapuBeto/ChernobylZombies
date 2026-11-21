package com.chernobylzombies.network.packet;

import com.chernobylzombies.item.ZoneWandItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server packet sent when the admin Shift + Left Clicks air to clear wand vertices.
 */
public class C2SWandClearPacket {

    public C2SWandClearPacket() {
    }

    public static void encode(C2SWandClearPacket packet, FriendlyByteBuf buf) {
    }

    public static C2SWandClearPacket decode(FriendlyByteBuf buf) {
        return new C2SWandClearPacket();
    }

    public static void handle(C2SWandClearPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        ItemStack wand = ZoneWandItem.findWand(sender);
        if (!wand.isEmpty()) {
            ZoneWandItem.clearVertices(wand, sender);
        }
        ctx.setPacketHandled(true);
    }
}
