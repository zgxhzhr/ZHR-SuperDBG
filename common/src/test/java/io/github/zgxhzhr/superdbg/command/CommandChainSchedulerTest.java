package io.github.zgxhzhr.superdbg.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandChainScheduler} 接管判定的单元测试：区分「该本模组接管」与「交还原版」的边界。
 */
class CommandChainSchedulerTest {

    private static CommandChainParser.Segment seg(String command, int delayTicks) {
        return new CommandChainParser.Segment(command, delayTicks);
    }

    @Test
    void multipleSegmentsAreAlwaysTakenOver() {
        assertTrue(CommandChainScheduler.needsTakeover(List.of(seg("/say a", 0), seg("/say b", 20))));
    }

    @Test
    void leadingDelayOnTheOnlyCommandIsTakenOver() {
        // 开头就写间隔：只有一条命令，但它带前导延迟，必须接管才能生效
        assertTrue(CommandChainScheduler.needsTakeover(List.of(seg("/tellraw @a \"x\"", 200))));
    }

    @Test
    void singlePlainCommandIsLeftToVanilla() {
        // 单条、无前导间隔：不接管，交还原版执行
        assertFalse(CommandChainScheduler.needsTakeover(List.of(seg("/say hi", 0))));
    }

    @Test
    void emptySegmentsAreLeftToVanilla() {
        assertFalse(CommandChainScheduler.needsTakeover(List.of()));
    }
}
