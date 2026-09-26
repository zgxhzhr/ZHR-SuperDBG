package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.PlayerAttributeOverrides;
import net.minecraft.server.level.ServerPlayer;
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
}
