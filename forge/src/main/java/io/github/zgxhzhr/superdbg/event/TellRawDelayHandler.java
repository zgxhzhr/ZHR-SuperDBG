package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.command.CommandChainScheduler;
import io.github.zgxhzhr.superdbg.command.TellRawScheduler;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 多段 tellraw 延迟消息的驱动。
 *
 * <p>{@link TellRawScheduler} 在 common 源集里，拿不到 forge 的 tick 事件，因此由本类在
 * 每个服务端 tick 末尾推进一步它的待发队列。放在 forge 侧而不是用 mixin 注入
 * {@code MinecraftServer#tickServer}：事件订阅是公开 API，不需要碰原版方法的混淆名，
 * 升级或换平台时也不会因为签名变动而静默失效。</p>
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TellRawDelayHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 只在 END 阶段推一次，BEGIN 阶段原版 tick 还没跑完
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        TellRawScheduler.tick(event.getServer());
        // 命令链（\间隔N秒 串联多条命令）的待执行队列，与 tellraw 多段消息同一驱动
        CommandChainScheduler.tick(event.getServer());
    }
}
