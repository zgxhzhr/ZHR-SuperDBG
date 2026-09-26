package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.client.ClientAttributeEnforcer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 本地玩家 tick 末尾重新对齐服务端属性真值锁。
 * <p>
 * {@code LocalPlayer.tick()} 内部先调用 {@code super.tick()}（即 Player.tick，
 * extra_enchantments 等模组的属性重算注入在其中），因此本 TAIL 注入晚于
 * 它们执行；真正的重置拦截由 {@code AttributeInstanceMixin} 的写入口锁完成，
 * 这里只负责每 tick 重新注册锁（属性实例可能重建）与对齐当前值。
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerTickMixin {

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void superdbg$enforceSyncedAttributes(CallbackInfo ci) {
        ClientAttributeEnforcer.enforce();
    }
}
