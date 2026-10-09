package io.github.zgxhzhr.superdbg.command;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * tellraw 多段延迟消息的转交与投递。
 *
 * <p>原版 {@code /tellraw} 只发送 {@code ComponentArgument} 解析出的那一个组件，而扩展语法
 * 允许多段并给后段指定延迟，因此需要两条链路把「整份段列表」从解析处带到执行处：</p>
 * <ol>
 *   <li>{@code ComponentArgument.parse} 解析出多段消息后调用 {@link #hold} 暂存；</li>
 *   <li>{@code /tellraw} 执行时 {@code EntityArgument.getPlayers} 被调用一次，
 *       在 {@code EntityArgumentMixin} 里 {@link #take} 取走并交给 {@link #deliver}。</li>
 * </ol>
 *
 * <p>暂存只保留「最近一次解析结果」，且取走即清空：解析与执行之间不会有别的解析插进来，
 * 取走时的空判断与命令名核对（见 {@code EntityArgumentMixin}）足以避免把补全阶段遗留的
 * 解析结果误当成正在执行的命令。</p>
 *
 * <p>投递用一个每 tick 递减的待发队列：{@link #tick} 由 forge 侧的 {@code ServerTickEvent}
 * 每个服务端 tick 调用一次，减到 0 就发。之所以不用 {@code MinecraftServer#execute} 自重排，
 * 是因为 {@code BlockableEventLoop} 的轮询是「队列非空就继续取」，任务把自己重新入队会让
 * 它在本 tick 内被反复取出，直接把服务端卡死；改用 tick 事件则天然每 tick 只有一次。</p>
 */
public final class TellRawScheduler {

    /** 待发送的消息段，倒计数到 0 时发出去。只在服务端线程访问，无需同步。 */
    private static final List<Pending> PENDING = new ArrayList<>();

    @Nullable
    private static TellRawStyledMessage pending;

    /**
     * 最近一次 {@link #deliver} 的尾段累计延迟（刻），即那份多段消息从即时发送到最后一段的总时长。
     * 命令链用它把「前面这段消息还要播多久」计入下一步的等待时间，否则
     * {@code tellraw ... \间隔0.1秒 /summon} 里的 summon 会只等 0.1 秒、与消息并行跑，而不是等消息播完。
     */
    private static int lastDeliveryTrailingTicks;

    private TellRawScheduler() {
    }

    /** 暂存刚刚解析出的消息（单段消息同样会覆盖，避免上一次的多段结果残留）。 */
    public static void hold(@Nullable TellRawStyledMessage message) {
        pending = message;
    }

    /** 取走暂存的消息并清空；没有则返回 null。 */
    @Nullable
    public static TellRawStyledMessage take() {
        TellRawStyledMessage message = pending;
        pending = null;
        return message;
    }

    /**
     * 把第 2 段起的部分排入待发队列，按「各段延迟累加」后的时刻依次发出。
     *
     * <p>第 1 段由原版 {@code /tellraw} 逻辑即时发送，这里只处理后续段；每段的延迟是相对
     * 前一段的间隔，所以要累加，否则「每段都等 3 秒」会被误读成「3 秒后一起出现」。</p>
     */
    public static void deliver(Collection<ServerPlayer> players, TellRawStyledMessage message) {
        List<TellRawStyledMessage.Part> parts = message.parts();
        int cumulativeTicks = 0;
        for (int i = 1; i < parts.size(); i++) {
            TellRawStyledMessage.Part part = parts.get(i);
            cumulativeTicks += Math.max(0, part.delayTicks());
            for (ServerPlayer player : players) {
                PENDING.add(new Pending(player.getUUID(), cumulativeTicks, part.component()));
            }
        }
        lastDeliveryTrailingTicks = cumulativeTicks;
    }

    /** 清空投递时长记录：执行一条命令前调用，避免读到上一条命令的残留。 */
    public static void resetLastDelivery() {
        lastDeliveryTrailingTicks = 0;
    }

    /** 取走并清空最近一次投递的尾段累计延迟；没有投递过则返回 0。 */
    public static int takeLastDeliveryTrailingTicks() {
        int ticks = lastDeliveryTrailingTicks;
        lastDeliveryTrailingTicks = 0;
        return ticks;
    }

    /**
     * 推进一步待发队列，把倒计时归零的消息发给仍在线的玩家。每个服务端 tick 调用一次。
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Pending> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next();
            if (--entry.remainingTicks > 0) {
                continue;
            }
            iterator.remove();
            // 延迟期间玩家可能已离线，用 UUID 取回当前实例，取不到就丢弃
            ServerPlayer online = server.getPlayerList().getPlayer(entry.playerId);
            if (online != null) {
                online.sendSystemMessage(entry.component, false);
            }
        }
    }

    /** 一条待发送的消息：目标玩家、内容与剩余 tick 数。 */
    private static final class Pending {

        private final UUID playerId;
        private final Component component;
        private int remainingTicks;

        private Pending(UUID playerId, int delayTicks, Component component) {
            this.playerId = playerId;
            this.component = component;
            // 至少等 1 tick：与第一段同 tick 的话两条消息的显示顺序不可控
            this.remainingTicks = Math.max(1, delayTicks);
        }
    }
}
