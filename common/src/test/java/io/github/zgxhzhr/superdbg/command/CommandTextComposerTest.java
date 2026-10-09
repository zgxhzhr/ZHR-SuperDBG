package io.github.zgxhzhr.superdbg.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CommandTextComposer} 的单元测试：覆盖多行合并、按段折行，以及「折行再合并」的往返一致性。
 */
class CommandTextComposerTest {

    /** 用户示例的那条命令，单行写法。 */
    private static final String COMMAND =
            "tellraw @a \"<狗修金>雪狐，帮我把那个——\" blue=\"雪狐，帮我把那个——\" \\间隔3.2秒"
                    + " \"<酒狐>......？\" 紫色=\"......？\" 斜体=\"......？\"";

    /** 同一条命令按段折行后的写法。 */
    private static final String WRAPPED =
            "tellraw @a \"<狗修金>雪狐，帮我把那个——\"\n"
                    + "blue=\"雪狐，帮我把那个——\"\n"
                    + "\\间隔3.2秒\n"
                    + "\"<酒狐>......？\"\n"
                    + "紫色=\"......？\"\n"
                    + "斜体=\"......？\"";

    // ==================== 多行 → 单行 ====================

    @Test
    void joinLinesMergesUserWrittenBlock() {
        String block = "tellraw @a \"<狗修金>雪狐，帮我把那个——\"\n"
                + "blue=\"雪狐，帮我把那个——\"\n"
                + "\\间隔3.2秒\n"
                + "\"<酒狐>......？\"\n"
                + "紫色=\"......？\"\n"
                + "斜体=\"......？\"";
        assertEquals(COMMAND, CommandTextComposer.joinLines(block));
    }

    @Test
    void joinLinesDropsBlankLinesAndTrimsEdges() {
        assertEquals("a b", CommandTextComposer.joinLines("  a  \n\n   \n b \n"));
    }

    @Test
    void joinLinesKeepsSpacesInsideQuotes() {
        // 引号内的空格属于正文，不能被当成行间分隔吞掉
        assertEquals("say \"hello world\"", CommandTextComposer.joinLines("say\n\"hello world\""));
    }

    @Test
    void joinLinesHandlesNullOrEmpty() {
        assertEquals("", CommandTextComposer.joinLines(null));
        assertEquals("", CommandTextComposer.joinLines(""));
        assertEquals("", CommandTextComposer.joinLines("\n  \n"));
    }

    @Test
    void joinLinesKeepsLineInternalSpacing() {
        // 行内多余空格不动，因为它们可能是引号串之外玩家有意写的（命令语法里等价，但没必要改写）
        assertEquals("a   b", CommandTextComposer.joinLines("a   b"));
    }

    // ==================== 单行 → 多行 ====================

    @Test
    void wrapBySegmentsBreaksExactlyAtSegmentBoundaries() {
        assertEquals(WRAPPED, CommandTextComposer.wrapBySegments(COMMAND));
    }

    @Test
    void wrappingRoundTripsToTheSameCommand() {
        assertEquals(COMMAND, CommandTextComposer.joinLines(CommandTextComposer.wrapBySegments(COMMAND)));
    }

    @Test
    void wrapBySegmentsAcceptsDelayWithoutBackslash() {
        assertEquals("tellraw @a \"甲\"\n间隔2秒\n\"乙\"",
                CommandTextComposer.wrapBySegments("tellraw @a \"甲\" 间隔2秒 \"乙\""));
    }

    @Test
    void wrapBySegmentsKeepsUnmarkedStyleMarkersOnTheirLine() {
        // 不带值的标记依托它所属的那一段，跟该行放在一起
        assertEquals("tellraw @a \"甲\" 斜体\n蓝色=\"甲\"",
                CommandTextComposer.wrapBySegments("tellraw @a \"甲\" 斜体 蓝色=\"甲\""));
    }

    @Test
    void plainCommandsAreLeftUntouched() {
        assertEquals("say hello world", CommandTextComposer.wrapBySegments("say hello world"));
        // 只有引号串、没有带值标记与间隔标记时，不该被折行
        assertEquals("say \"hello world\"", CommandTextComposer.wrapBySegments("say \"hello world\""));
        assertEquals("", CommandTextComposer.wrapBySegments(""));
        assertEquals("", CommandTextComposer.wrapBySegments(null));
    }

    // ==================== 按上限截断 ====================

    @Test
    void truncateCutsTextBeyondLimit() {
        assertEquals("abcd", CommandTextComposer.truncate("abcd", 4));
        assertEquals("ab", CommandTextComposer.truncate("abcd", 2));
    }

    @Test
    void truncateYieldsEmptyWhenNoRoomLeft() {
        assertEquals("", CommandTextComposer.truncate("abcd", 0));
        assertEquals("", CommandTextComposer.truncate("abcd", -3));
    }

    @Test
    void truncateLeavesTextUntouchedUnderHugeLimit() {
        // 无上限时不该改动任何内容：这是多行输入框的默认状态
        assertEquals(COMMAND, CommandTextComposer.truncate(COMMAND, Integer.MAX_VALUE));
    }
}
