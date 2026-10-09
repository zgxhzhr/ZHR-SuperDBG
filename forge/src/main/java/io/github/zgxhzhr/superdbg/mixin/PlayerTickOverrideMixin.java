package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.PlayerAttributeOverrides;
import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端玩家 tick 末尾强制执行属性覆盖。
 * <p>
 * 用高于默认（1000）的优先级 2000，使本 Mixin 晚于普通优先级的 TAIL 注入器
 * 被处理，从而保证在 extra_enchantments 等模组每 tick 重算属性基础值之后再
 * 写回编辑器设定的值。仅作用于服务端玩家，客户端侧由 LocalPlayerTickMixin
 * 依据服务端同步值重写。
 * <p>
 * 同时承载玩家侧的防移除伤害守卫：{@code Player.actuallyHurt} 覆写了
 * {@code LivingEntity.actuallyHurt} 且不调用 super，使得
 * {@link io.github.zgxhzhr.superdbg.mixin.LivingEntityMixin} 上的
 * 实际扣血层注入对玩家不生效，必须在此单独挂载。
 * （{@code Player.hurt} 会调用 super.hurt，故 hurt 层的守卫已由
 * LivingEntityMixin 覆盖，此处无需重复。）
 */
@Mixin(value = Player.class, priority = 2000)
public abstract class PlayerTickOverrideMixin {

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void superdbg$enforceAttributeOverrides(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide && self instanceof ServerPlayer serverPlayer) {
            PlayerAttributeOverrides.enforce(serverPlayer);
        }
    }

    /**
     * 玩家侧实际扣血层守卫：守卫实体一律免疫（返回 true，取消本次伤害）。
     * 与 {@link io.github.zgxhzhr.superdbg.mixin.LivingEntityMixin} 的同名注入互斥
     * （两者分别命中 Player 与 LivingEntity 的覆写版本，不会重复计数）。
     */
    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$guardPlayerActuallyHurt(DamageSource source, float amount, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (RemovalGuard.blockActuallyHurt(self, source, amount)) {
            ci.cancel();
        }
    }
}
