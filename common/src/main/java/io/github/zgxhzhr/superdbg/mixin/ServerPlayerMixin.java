package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家死亡守卫。
 * <p>
 * {@code ServerPlayer} 覆写了 {@code die(DamageSource)} 且<b>不调用</b>{@code super.die}，
 * 因此 {@link LivingEntityMixin} 挂在 {@code LivingEntity.die} 上的守卫对玩家完全不生效。
 * 而"绕过伤害系统直接压血致死"的最后一步正是走原版 {@code ServerPlayer.die}
 * （发送死亡消息 + {@code remove(KILLED)}），其调用栈是全白名单的原版栈，靠调用栈判别会漏放。
 * <p>
 * 守卫语义：开启防移除的玩家<b>一律不死亡</b>。任何来源的 die() 都取消，
 * 并在血量低于基准时恢复到基准值，避免停留在"血量归零却未死亡"的不一致状态
 * （玩家会卡在死亡界面并反复进入死亡流程）。
 * 调试器主动处置（编辑器移除）走 BYPASS/DYING 旁路放行。
 */
@Mixin(value = ServerPlayer.class, priority = 2000)
public abstract class ServerPlayerMixin {

    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$guardPlayerDie(DamageSource source, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.level() == null || self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self) || RemovalGuard.isBypassing()
                || Boolean.TRUE.equals(RemovalGuard.DYING.get())) {
            return;
        }
        ci.cancel();
        RemovalGuard.restoreHealthBaseline(self);
        RemovalGuard.logInterceptedThrottled(self,
                "玩家死亡被拦截（hp=" + self.getHealth() + "）", RemovalGuard.findIllegalCaller());
    }
}
