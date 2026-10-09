package io.github.zgxhzhr.superdbg.command;

import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.mojang.brigadier.tree.CommandNode;

/**
 * 命令链的执行与延迟投递。
 *
 * <p>把 {@code 命令1 \间隔N秒 命令2 ...} 拆开后按顺序执行，延迟由 {@link #tick} 每个服务端 tick
 * 推进一步。之所以不用 {@code MinecraftServer#execute} 自重排：{@code BlockableEventLoop} 的轮询是
 * 「队列非空就继续取」，任务重新入队会在同一 tick 内被反复取出，直接把服务端卡死（与 tellraw
 * 多段消息同一原因）。</p>
 *
 * <p><b>各段是串行而不是并行计时的。</b>上一段彻底走完（对 tellraw 的多段消息而言，是它的最后
 * 一段也发出去）之后，才按下一段自己的间隔开始计时。这样一段混在消息里的命令才会出现在它该在的
 * 位置：{@code tellraw @a "..." \间隔2秒 "..." \间隔0.1秒 /summon} 里的 summon 会等前面两段消息
 * 播完再延迟 0.1 秒，而不是与消息并行、在第 0.1 秒就抢先执行。若非如此，命令链的分段计时与
 * tellraw 的段内计时就是两条互不相干的时间轴。</p>
 *
 * <p>执行复用原命令来源 {@link CommandSourceStack}，因此权限、执行位置与命令方块/玩家身份都保持一致；
 * 与原版 {@code /schedule} 的延迟执行是同一路数。</p>
 */
public final class CommandChainScheduler {

    /** 正在执行的命令链，每条链一个游标。只在服务端线程访问，无需同步。 */
    private static final List<Script> SCRIPTS = new ArrayList<>();

    private CommandChainScheduler() {
    }

    /**
     * 规划命令链：不含分隔标记、或拆分后只有一条无前导延迟的命令时返回 null（由原版逻辑照常执行）。
     */
    @Nullable
    public static List<CommandChainParser.Segment> plan(Commands commands, @Nullable String command) {
        if (!CommandChainParser.containsDelayMarker(command)) {
            return null;
        }
        Set<String> roots = new HashSet<>();
        for (CommandNode<CommandSourceStack> node : commands.getDispatcher().getRoot().getChildren()) {
            roots.add(node.getName());
        }
        List<CommandChainParser.Segment> segments = CommandChainParser.split(command, roots::contains);
        return needsTakeover(segments) ? segments : null;
    }

    /**
     * 拆分结果是否需要本模组接管。
     *
     * <p>两种情况要接管：拆分出多条命令，或只有一条但带着前导间隔
     * （整串以 {@code \间隔N秒} 开头，等于「延迟后再执行」这唯一一条命令）。
     * 后者若不接管会退回原版，把 {@code \间隔N秒} 当成命令正文而报错。</p>
     *
     * <p>单段且无前导间隔时返回 false，交还原版逻辑原样执行，避免无谓接管。</p>
     */
    static boolean needsTakeover(List<CommandChainParser.Segment> segments) {
        if (segments.size() > 1) {
            return true;
        }
        return segments.size() == 1 && segments.get(0).delayTicks() > 0;
    }

    /**
     * 执行命令链：第一段若无前导间隔则立即执行，其余各段由 {@link #tick} 串行推进。
     *
     * @return 立即执行那一段的返回值（没有立即执行则返回 0）
     */
    public static int run(CommandSourceStack source, List<CommandChainParser.Segment> segments) {
        Script script = new Script(source, segments);
        SCRIPTS.add(script);
        return script.start();
    }

    /** 推进一步所有在跑的命令链。每个服务端 tick 调用一次。 */
    public static void tick(MinecraftServer server) {
        if (SCRIPTS.isEmpty()) {
            return;
        }
        Iterator<Script> iterator = SCRIPTS.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().advance()) {
                iterator.remove();
            }
        }
    }

    /**
     * 执行一条命令，并把它可能产生的多段消息时长记录下来。
     *
     * <p>执行前先清空记录：只有告诉玩家的 {@code tellraw} 多段消息会写入它，非 tellraw 命令读到 0
     * 才是对的，否则会把上一条命令的残留时长误当成这条命令的耗时。</p>
     */
    private static int execute(CommandSourceStack source, String command) {
        TellRawScheduler.resetLastDelivery();
        return source.getServer().getCommands().performPrefixedCommand(source, command);
    }

    /** 一条命令链的执行游标。只在服务端线程访问。 */
    private static final class Script {

        private final CommandSourceStack source;
        private final List<CommandChainParser.Segment> segments;
        /** 下一个待执行段的下标；等于段数即表示整条链已走完。 */
        private int index;
        /** 距离执行当前段还要等多少刻。 */
        private int waitTicks;

        private Script(CommandSourceStack source, List<CommandChainParser.Segment> segments) {
            this.source = source;
            this.segments = segments;
        }

        /** 立即执行第一段（若它没有前导间隔），返回其结果。 */
        private int start() {
            if (this.segments.isEmpty()) {
                return 0;
            }
            if (this.segments.get(0).delayTicks() > 0) {
                // 开头就写了间隔：第一段也要等待
                this.waitTicks = this.segments.get(0).delayTicks();
                return 0;
            }
            int result = execute(this.source, this.segments.get(0).command());
            this.scheduleNext();
            return result;
        }

        /**
         * 推进一步。
         *
         * @return true 表示整条链已执行完
         */
        private boolean advance() {
            if (this.index >= this.segments.size()) {
                return true;
            }
            if (--this.waitTicks > 0) {
                return false;
            }
            String command = this.segments.get(this.index).command();
            try {
                execute(this.source, command);
            } catch (Exception e) {
                // 延迟期间来源可能已失效（命令方块被破坏、实体卸载等），只记录不影响服务端 tick
                Constants.LOG.warn("[SuperDbg] 延迟命令执行失败：{}", command, e);
            }
            this.scheduleNext();
            return this.index >= this.segments.size();
        }

        /** 把游标移到下一段，并把「上一段还要播多久 + 下一段自己的间隔」设为等待刻数。 */
        private void scheduleNext() {
            this.index++;
            if (this.index >= this.segments.size()) {
                return;
            }
            // 上一段若是 tellraw 多段消息，它还要播 trailing 刻，得等播完再开始下一段的计时
            int trailing = TellRawScheduler.takeLastDeliveryTrailingTicks();
            this.waitTicks = trailing + Math.max(0, this.segments.get(this.index).delayTicks());
        }
    }
}
