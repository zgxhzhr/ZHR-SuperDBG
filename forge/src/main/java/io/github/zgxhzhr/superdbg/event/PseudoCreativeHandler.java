package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.entity.PseudoCreativeState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 伪创造模式每 tick 维持（玩家 tick 事件，服务端）。
 * <p>
 * 创造模式：把生命值拉回开启瞬间记录的值（锁血）；生存模式不做任何 tick
 * 干预——受击掉血由 {@code PseudoCreativeDamageMixin} 的 hurt 前后快照恢复，
 * 死亡由 die 拦截。
 */
public class PseudoCreativeHandler {

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (serverPlayer.level().isClientSide) {
            return;
        }
        PseudoCreativeState.tick(serverPlayer);
    }
}
