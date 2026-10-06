package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.PseudoCreativeState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 伪创造模式：禁止药水/效果恢复（仅创造模式）。
 * <p>
 * 原版 {@code LivingEntity#heal(float)} 是药水效果（MobEffectInstance 的
 * {@code performEffect} → {@code heal}）等恢复来源的统一入口；调试器直接
 * {@code setHealth} 不经过 heal，不受影响。伪创造在生存模式不拦恢复
 * （生存模式不锁血，回血属于正常受击流程外的普通机制）。
 */
@Mixin(LivingEntity.class)
public abstract class PseudoCreativeHealMixin {

    @Inject(method = "heal(F)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$blockHealWhenPseudoCreative(float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide || !(self instanceof ServerPlayer player)) {
            return;
        }
        if (PseudoCreativeState.isEnabled(player) && player.isCreative()) {
            ci.cancel();
        }
    }
}
