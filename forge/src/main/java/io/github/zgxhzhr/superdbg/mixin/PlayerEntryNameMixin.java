package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.client.ClientRenderNameCache;
import net.minecraft.client.gui.screens.social.PlayerEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.UUID;

/**
 * 社交屏幕（按 P）玩家条目名字覆写。
 *
 * <p>原版 {@link PlayerEntry#render} 直接用 {@code playerName} 字段绘制名字
 * （不走 {@code getPlayerName()} getter），因此改用 {@link ModifyArg} 替换
 * {@code GuiGraphics#drawString(Font, String, int, int, int, boolean)} 的
 * 字符串参数：有渲染名（按玩家 UUID 查客户端缓存）则用渲染名，否则保留原名。
 * 目标方法均为原版方法（生产环境混淆），走默认 remap=true，由注解处理器生成映射。</p>
 */
@Mixin(PlayerEntry.class)
public abstract class PlayerEntryNameMixin {

    /** 玩家条目对应的玩家 UUID（构造时写入，稳定不变）。 */
    @Shadow
    private UUID id;

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"),
            index = 1
    )
    private String superdbg$replaceSocialName(String name) {
        String renderName = ClientRenderNameCache.get(this.id);
        return renderName != null ? renderName : name;
    }
}
