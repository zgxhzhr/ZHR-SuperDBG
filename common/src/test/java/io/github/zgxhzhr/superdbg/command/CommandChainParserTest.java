package io.github.zgxhzhr.superdbg.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandChainParser} 的单元测试，覆盖 {@code \间隔N秒} 命令链语法的切分规则，
 * 以及它与 tellraw 多段消息语法之间的边界。
 */
class CommandChainParserTest {

    /** 假定已注册的顶级命令名，用于判定裸词是不是命令。 */
    private static final Set<String> REAL_COMMANDS = Set.of("say", "summon", "tellraw", "give", "time");

    private static final Predicate<String> IS_ROOT = REAL_COMMANDS::contains;

    private static List<CommandChainParser.Segment> split(String input) {
        return CommandChainParser.split(input, IS_ROOT);
    }

    // ==================== 快速判定 ====================

    @Test
    void containsDelayMarkerDetectsPrefix() {
        assertTrue(CommandChainParser.containsDelayMarker("/say hi \\间隔1秒 /say bye"));
        assertTrue(CommandChainParser.containsDelayMarker("间隔1秒"));
        assertFalse(CommandChainParser.containsDelayMarker("/say hi"));
        assertFalse(CommandChainParser.containsDelayMarker(null));
    }

    // ==================== 无分隔符：原样保留 ====================

    @Test
    void plainCommandWithoutMarkerStaysOneSegment() {
        List<CommandChainParser.Segment> segments = split("/say hi");
        assertEquals(1, segments.size());
        assertEquals("/say hi", segments.get(0).command());
        assertEquals(0, segments.get(0).delayTicks());
    }

    @Test
    void nullAndBlankInputReturnEmptyList() {
        assertTrue(split(null).isEmpty());
        assertTrue(split("").isEmpty());
        assertTrue(split("   ").isEmpty());
    }

    // ==================== 用户示例 ====================

    @Test
    void userExampleSplitsIntoThreeSegments() {
        List<CommandChainParser.Segment> segments =
                split("/tellraw __Astral_Express \"1\" \\间隔3.2秒 /summon  \\间隔1.2秒 /tellraw");

        assertEquals(3, segments.size());

        assertEquals("/tellraw __Astral_Express \"1\"", segments.get(0).command());
        assertEquals(0, segments.get(0).delayTicks(), "第一条命令立即执行");

        assertEquals("/summon", segments.get(1).command(), "分隔符后的命令要去掉首尾空白");
        assertEquals(64, segments.get(1).delayTicks(), "3.2 秒应换算成 64 tick");

        assertEquals("/tellraw", segments.get(2).command());
        assertEquals(24, segments.get(2).delayTicks(), "1.2 秒应换算成 24 tick");
    }

    // ==================== 裸词命令 ====================

    @Test
    void bareCommandNameAlsoSplits() {
        List<CommandChainParser.Segment> segments = split("say hi \\间隔1秒 say bye");
        assertEquals(2, segments.size());
        assertEquals("say hi", segments.get(0).command());
        assertEquals("say bye", segments.get(1).command());
        assertEquals(20, segments.get(1).delayTicks());
    }

    @Test
    void bareWordThatIsNotACommandDoesNotSplit() {
        // 「间隔1秒」后面跟的不是命令名：保持原样，交给原版执行
        List<CommandChainParser.Segment> segments = split("/say 再过\\间隔1秒就好了");
        assertEquals(1, segments.size());
        assertEquals("/say 再过\\间隔1秒就好了", segments.get(0).command());
    }

    // ==================== 与 tellraw 多段语法的边界 ====================

    @Test
    void delayFollowedByQuoteIsLeftToTellraw() {
        // tellraw 自己的多段消息写法：\间隔 后面是引号串，不是命令，不能被命令链抢走
        List<CommandChainParser.Segment> segments = split("\"甲\" \\间隔3.2秒 \"乙\"");
        assertEquals(1, segments.size(), "下一段以引号开头的仍由 tellraw 多段解析处理");
    }

    @Test
    void contentInsideQuotesIsNeverSplit() {
        List<CommandChainParser.Segment> segments = split("/tellraw @a \"这里有\\间隔5秒的字样\"");
        assertEquals(1, segments.size());
        assertEquals("/tellraw @a \"这里有\\间隔5秒的字样\"", segments.get(0).command());
    }

    @Test
    void userExampleQuotedTextIsNotMistakenForMarker() {
        // 引号串里的「间隔」不属于语法，命令链仍按引号外的分隔符切
        List<CommandChainParser.Segment> segments =
                split("/tellraw @a \"间隔3.2秒\" \\间隔2秒 /say done");
        assertEquals(2, segments.size());
        assertEquals("/tellraw @a \"间隔3.2秒\"", segments.get(0).command());
        assertEquals("/say done", segments.get(1).command());
        assertEquals(40, segments.get(1).delayTicks());
    }

    // ==================== 反斜杠与秒数格式 ====================

    @Test
    void delayWithoutBackslashAlsoSplits() {
        List<CommandChainParser.Segment> segments = split("say hi 间隔1秒 say bye");
        assertEquals(2, segments.size());
        assertEquals(20, segments.get(1).delayTicks());
    }

    @Test
    void wholeSecondDelayIsAccepted() {
        List<CommandChainParser.Segment> segments = split("/say a \\间隔2秒 /say b");
        assertEquals(2, segments.size());
        assertEquals(40, segments.get(1).delayTicks());
    }

    @Test
    void malformedDelayIsNotASeparator() {
        // 秒数不是数字、或漏了「秒」：不构成分隔符，命令原样保留
        assertEquals(1, split("/say a \\间隔abc秒 /say b").size());
        assertEquals(1, split("/say a \\间隔3.2 /say b").size());
    }

    // ==================== 开头就写间隔 ====================

    @Test
    void leadingDelayDefersTheOnlyCommand() {
        // 聊天栏写成 /\间隔10秒 /tellraw ...，服务端收到的是去掉首个 / 的串
        List<CommandChainParser.Segment> segments = split("\\间隔10秒 /tellraw @a \"x\"");
        assertEquals(1, segments.size());
        assertEquals("/tellraw @a \"x\"", segments.get(0).command());
        assertEquals(200, segments.get(0).delayTicks(), "10 秒应换算成 200 tick，第一条不再立即执行");
    }

    @Test
    void leadingDelayWithoutBackslashAlsoWorks() {
        List<CommandChainParser.Segment> segments = split("间隔0.5秒 /say hi");
        assertEquals(1, segments.size());
        assertEquals("/say hi", segments.get(0).command());
        assertEquals(10, segments.get(0).delayTicks());
    }

    @Test
    void leadingDelayPrependsToFullChain() {
        // 开头间隔作用于第一条；之后每条的间隔仍相对上一条
        List<CommandChainParser.Segment> segments =
                split("\\间隔10秒 /tellraw @a \"x\" \\间隔2秒 /say y");
        assertEquals(2, segments.size());
        assertEquals("/tellraw @a \"x\"", segments.get(0).command());
        assertEquals(200, segments.get(0).delayTicks(), "开头的间隔作用于第一条命令");
        assertEquals("/say y", segments.get(1).command());
        assertEquals(40, segments.get(1).delayTicks(), "第二条仍相对第一条等待 2 秒");
    }

    @Test
    void danglingLeadingDelayStaysOnePlainSegment() {
        // 只有间隔、后面没有命令：不构成有效链，原样保留
        List<CommandChainParser.Segment> segments = split("\\间隔10秒");
        assertEquals(1, segments.size());
        assertEquals("\\间隔10秒", segments.get(0).command());
        assertEquals(0, segments.get(0).delayTicks());
    }
}
