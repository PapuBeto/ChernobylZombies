package com.chernobylzombies.network.packet;

import com.chernobylzombies.data.ServerConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server packet to reload config/chernobylzombies/chernobylzombies_config.json and import/*.json.
 */
public class C2SReloadConfigPacket {

    public C2SReloadConfigPacket() {
    }

    public static void encode(C2SReloadConfigPacket packet, FriendlyByteBuf buf) {
    }

    public static C2SReloadConfigPacket decode(FriendlyByteBuf buf) {
        return new C2SReloadConfigPacket();
    }

    public static void handle(C2SReloadConfigPacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        String result = ServerConfigManager.reloadFromDisk(sender.getServer());
        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] " + result).withStyle(ChatFormatting.GREEN),
                false
        );
        ctx.setPacketHandled(true);
    }
}
