package io.github.zgxhzhr.superdbg.client;

import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 用调试器自持的渲染名替换玩家头顶悬浮名牌。
 * <p>
 * 数据由 {@link ClientRenderNameCache} 经
 * {@link io.github.zgxhzhr.superdbg.network.SyncRenderNamePacket} 同步而来，不依赖任何第三方模组。
 * 仅影响头顶名牌（以及 Jade 标题），真实名字与其他位置不变。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RenderNameTagHandler {

    private RenderNameTagHandler() {
    }

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String renderName = ClientRenderNameCache.get(player.getUUID());
        if (renderName != null) {
            event.setContent(Component.literal(renderName));
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRenderNameCache.clear();
    }
}
