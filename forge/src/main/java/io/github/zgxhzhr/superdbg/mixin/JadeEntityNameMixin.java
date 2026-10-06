package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.client.ClientRenderNameCache;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Jade 面板标题同步自定义渲染名。
 *
 * <p>调试器写入的渲染名由 {@link ClientRenderNameCache} 经
 * {@link io.github.zgxhzhr.superdbg.network.SyncRenderNamePacket} 同步到客户端。
 * Jade 的实体标题由 {@code ObjectNameProvider.getEntityName} 生成，这里在标题生成前
 * 用渲染名替换，使 Jade 面板与头顶名牌保持一致。Jade 缺席时该 Mixin 自动跳过。</p>
 */
@Mixin(snownee.jade.addon.core.ObjectNameProvider.class)
public abstract class JadeEntityNameMixin {

    @Inject(method = "getEntityName", at = @At("HEAD"), cancellable = true, remap = false)
    private static void superdbg$replaceJadeTitle(Entity entity, CallbackInfoReturnable<Component> cir) {
        String renderName = ClientRenderNameCache.get(entity.getUUID());
        if (renderName != null) {
            cir.setReturnValue(Component.literal(renderName));
        }
    }
}
