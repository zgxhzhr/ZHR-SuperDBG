package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.command.CommandChainScheduler;
import io.github.zgxhzhr.superdbg.command.TellRawScheduler;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * 多段 tellraw 延迟消息的驱动（Fabric 侧），与 forge 侧的
 * {@code io.github.zgxhzhr.superdbg.event.TellRawDelayHandler} 作用相同：
 * 每个服务端 tick 推进一步 {@link TellRawScheduler} 的待发队列。
 * 命令链（{@code \间隔N秒} 串联多条命令）的待执行队列也在同一处推进。
 */
public final class TellRawDelayHandler {

    private TellRawDelayHandler() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TellRawScheduler::tick);
        ServerTickEvents.END_SERVER_TICK.register(CommandChainScheduler::tick);
    }
}
