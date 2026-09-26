package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.entity.PlayerAttributeOverrides;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家属性覆盖的持久化继承。
 * <p>
 * 每 tick 的强制执行由 {@code PlayerTickOverrideMixin} 在 Player.tick()
 * 末尾（晚于其它模组的属性重算）完成；这里只负责死亡重生（及维度切换返回）时
 * 把旧玩家实体的覆盖表复制到新实体。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerAttributeOverrideHandler {

    private PlayerAttributeOverrideHandler() {
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.isWasDeath() || event.getEntity() instanceof ServerPlayer) {
            PlayerAttributeOverrides.cloneData(event.getOriginal(), event.getEntity());
        }
    }
}
