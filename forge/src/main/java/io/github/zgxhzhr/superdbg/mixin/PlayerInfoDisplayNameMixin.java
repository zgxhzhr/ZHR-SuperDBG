package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.client.ClientRenderNameCache;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tab 列表玩家显示名覆写：有渲染名时用渲染名替换 {@code getTabListDisplayName()}
 * 的返回值（Tab 列表与部分依赖该方法的界面）。目标方法为原版方法（生产环境混淆），
 * 走默认 remap=true，由注解处理器生成映射。
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoDisplayNameMixin {

    @Inject(
            method = "getTabListDisplayName()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void superdbg$getTabListDisplayName(CallbackInfoReturnable<Component> cir) {
        PlayerInfo info = (PlayerInfo) (Object) this;
        String renderName = ClientRenderNameCache.get(info.getProfile().getId());
        if (renderName != null) {
            cir.setReturnValue(Component.literal(renderName));
        }
    }
}
