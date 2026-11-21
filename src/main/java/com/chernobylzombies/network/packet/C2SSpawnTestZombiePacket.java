package com.chernobylzombies.network.packet;

import com.chernobylzombies.entity.CustomZombieEntity;
import com.chernobylzombies.event.ModCommonEvents;
import com.chernobylzombies.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> Server packet that spawns a test CustomZombieEntity with an exact modelId and textureId
 * on solid ground in front of the requesting OP admin.
 */
public class C2SSpawnTestZombiePacket {

    private final int modelId;
    private final int textureId;

    public C2SSpawnTestZombiePacket(int modelId, int textureId) {
        this.modelId = modelId;
        this.textureId = textureId;
    }

    public static void encode(C2SSpawnTestZombiePacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.modelId);
        buf.writeVarInt(packet.textureId);
    }

    public static C2SSpawnTestZombiePacket decode(FriendlyByteBuf buf) {
        return new C2SSpawnTestZombiePacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(C2SSpawnTestZombiePacket packet, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ServerPlayer sender = ctx.getSender();
        if (sender == null || !sender.hasPermissions(2)) {
            ctx.setPacketHandled(true);
            return;
        }

        ServerLevel level = sender.serverLevel();
        CustomZombieEntity zombie = ModEntities.getEntityTypeForModel(packet.modelId).create(level);
        if (zombie == null) {
            ctx.setPacketHandled(true);
            return;
        }

        Vec3 look = sender.getLookAngle();
        Vec3 horizontalLook = new Vec3(look.x, 0.0D, look.z);
        if (horizontalLook.lengthSqr() < 0.001D) {
            horizontalLook = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            horizontalLook = horizontalLook.normalize();
        }

        int targetX = Mth.floor(sender.getX() + horizontalLook.x * 2.5D);
        int targetZ = Mth.floor(sender.getZ() + horizontalLook.z * 2.5D);
        int playerY = sender.getBlockY();

        BlockPos safePos = ModCommonEvents.findSafeGroundSpawnPos(
                level,
                targetX,
                targetZ,
                playerY - 6,
                playerY + 6,
                playerY,
                true
        );
        if (safePos == null) {
            safePos = ModCommonEvents.findSafeGroundSpawnPos(
                    level,
                    sender.getBlockX(),
                    sender.getBlockZ(),
                    playerY - 3,
                    playerY + 4,
                    playerY,
                    true
            );
        }

        double x = (safePos != null ? safePos.getX() : targetX) + 0.5D;
        double y = safePos != null ? safePos.getY() : sender.getY();
        double z = (safePos != null ? safePos.getZ() : targetZ) + 0.5D;
        float facingYaw = sender.getYRot() + 180.0F;

        zombie.moveTo(x, y, z, facingYaw, 0.0F);
        zombie.setYHeadRot(facingYaw);
        zombie.setYBodyRot(facingYaw);
        zombie.assignExactVariant(packet.modelId, packet.textureId);
        zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(zombie.blockPosition()), MobSpawnType.COMMAND, null, null);
        zombie.setApprovedZoneSpawn(true);

        if (!level.noCollision(zombie)) {
            zombie.moveTo(sender.getX(), sender.getY(), sender.getZ(), facingYaw, 0.0F);
        }

        level.addFreshEntity(zombie);
        sender.displayClientMessage(
                Component.literal("[Chernobyl Zombies] Zombie de prueba invocado (Modelo " + packet.modelId + ", Textura " + packet.textureId + ").")
                        .withStyle(ChatFormatting.GREEN),
                true
        );
        ctx.setPacketHandled(true);
    }
}
