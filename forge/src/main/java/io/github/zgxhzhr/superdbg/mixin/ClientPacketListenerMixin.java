package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.client.ClientAttributeEnforcer;
import io.github.zgxhzhr.superdbg.network.FullAmplifierAccess;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端网络包处理补丁：
 * <ul>
 *   <li>药水效果：原版 {@code handleUpdateMobEffect} 用 byte amplifier 构造
 *       {@link MobEffectInstance}（超 255 被截断）。本 Mixin 在原版逻辑之后
 *       用同步包扩展字段 {@link FullAmplifierAccess} 中的完整 int 等级重建效果。</li>
 *   <li>属性同步：把服务端下发给本地玩家的属性基础值喂给
 *       {@link ClientAttributeEnforcer}，供本地玩家 tick 末重写被其它模组
 *       每 tick 重置的属性。</li>
 * </ul>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    /**
     * 原版按截断后的 byte 等级添加完效果后，若完整等级超出 byte 范围，
     * 用完整等级重新构造并强制替换（forceAddEffect 对同效果是覆盖语义）。
     */
    @Inject(
            method = "handleUpdateMobEffect",
            at = @At("TAIL")
    )
    private void superdbg$reapplyFullAmplifier(ClientboundUpdateMobEffectPacket packet, CallbackInfo ci) {
        int full = ((FullAmplifierAccess) packet).superdbg$getFullAmplifier();
        if (full < 0) {
            return;
        }
        MobEffect effect = packet.getEffect();
        if (effect == null || full == (packet.getEffectAmplifier() & 0xFF)) {
            // 完整值与原版 byte 无符号值一致（0-255），无需重建
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(packet.getEntityId());
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        MobEffectInstance replacement = new MobEffectInstance(
                effect,
                packet.getEffectDurationTicks(),
                full,
                packet.isEffectAmbient(),
                packet.isEffectVisible(),
                packet.effectShowsIcon());
        living.forceAddEffect(replacement, null);
    }

    /**
     * 记录服务端同步的属性基础值（仅本地玩家自己的包会被缓存）。
     */
    @Inject(
            method = "handleUpdateAttributes",
            at = @At("TAIL")
    )
    private void superdbg$cacheSyncedAttributes(ClientboundUpdateAttributesPacket packet, CallbackInfo ci) {
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : packet.getValues()) {
            ClientAttributeEnforcer.acceptSyncedBase(
                    packet.getEntityId(), snapshot.getAttribute(), snapshot.getBase());
        }
    }
}
