package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.PseudoCreativeState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 伪创造模式：禁止饱食度自然回血（仅创造模式）。
 * <p>
 * 原版 {@code FoodData#tick(Player)} 是玩家按饱食度/饱和度自然恢复生命的入口，
 * 仅在服务端玩家上执行。创造模式锁血时此恢复会破坏锁定值，直接取消。
 */
@Mixin(FoodData.class)
public abstract class FoodDataTickMixin {

    @Inject(method = "tick(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$blockNaturalRegenWhenPseudoCreative(Player player, CallbackInfo ci) {
        if (player == null || player.level().isClientSide
                || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (PseudoCreativeState.isEnabled(serverPlayer) && serverPlayer.isCreative()) {
            ci.cancel();
        }
    }
}
