package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.PseudoCreativeState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.WeakHashMap;

/**
 * 伪创造模式：受击流程完全正常（怪物索敌/攻击命中/受击音效/击退/受伤红闪照旧），
 * 只是生命值不掉。
 * <ul>
 *   <li>{@code hurt} HEAD：记录受击前血量（仅服务端伪创造玩家）</li>
 *   <li>{@code hurt} RETURN：受击生效（返回值 true）且仍存活 → 血量拉回受击前，
 *       所有受击表现已由原版流程正常播放</li>
 *   <li>{@code die} HEAD：取消死亡流程（防止单次大伤害在 RETURN 拉回前把玩家打死；
 *       原版 1.20.1 {@code die} 只有一个 DamageSource 参数）</li>
 * </ul>
 * 不干预非伪创造玩家与非服务端侧。
 */
@Mixin(LivingEntity.class)
public abstract class PseudoCreativeDamageMixin {

    /** 受击前血量快照：服务端玩家实例 → 血量；弱键随实体 GC 自动清理 */
    @Unique
    private static final WeakHashMap<Entity, Float> superdbg$beforeHealth = new WeakHashMap<>();

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"))
    private void superdbg$recordHealthBeforeHurt(DamageSource source, float amount,
                                                 CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide || !(self instanceof ServerPlayer player)) {
            return;
        }
        if (PseudoCreativeState.isEnabled(player)) {
            superdbg$beforeHealth.put(player, player.getHealth());
        }
    }

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("RETURN"))
    private void superdbg$restoreHealthAfterHurt(DamageSource source, float amount,
                                                 CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide || !(self instanceof ServerPlayer player)) {
            return;
        }
        if (!PseudoCreativeState.isEnabled(player) || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        Float before = superdbg$beforeHealth.remove(player);
        // 死亡流程已被 die HEAD 取消，此处若仍存活则把血量拉回受击前
        if (before != null && self.isAlive()) {
            player.setHealth(before);
        }
    }

    /** 伪创造玩家禁止死亡：大伤害会被 hurt RETURN 拉回，这里防止 die 流程真正执行 */
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$blockPseudoCreativeDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide || !(self instanceof ServerPlayer player)) {
            return;
        }
        if (PseudoCreativeState.isEnabled(player)) {
            ci.cancel();
        }
    }
}
