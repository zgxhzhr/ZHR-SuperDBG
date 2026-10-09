package io.github.zgxhzhr.superdbg.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TellRawStyleParser} 的单元测试，覆盖 tellraw 扩展语法的四种标准写法与错误分支。
 */
class TellRawStyleParserTest {

    /** 取组件中某个文本片段对应的样式；整段只有一个 literal 时直接看根组件。 */
    private static Style styleOf(Component root, String text) {
        if (root.getSiblings().isEmpty()) {
            return root.getString().equals(text) ? root.getStyle() : null;
        }
        for (Component sibling : root.getSiblings()) {
            if (sibling.getString().equals(text)) {
                return sibling.getStyle();
            }
        }
        return null;
    }

    private static boolean italic(Style style) {
        return Boolean.TRUE.equals(style.isItalic());
    }

    // ==================== 基础样式 ====================

    @Test
    void wholeTextItalic() throws Exception {
        Component result = TellRawStyleParser.parse("\"<???>这是....哪？\" 斜体");
        assertEquals("<???>这是....哪？", result.getString());
        assertTrue(italic(result.getStyle()));
    }

    @Test
    void fragmentItalicLeavesPrefixDefault() throws Exception {
        Component result = TellRawStyleParser.parse("\"<???>这是....哪？\" 斜体=\"这是....哪？\"");
        assertEquals("<???>这是....哪？", result.getString());

        Style prefix = styleOf(result, "<???>");
        Style fragment = styleOf(result, "这是....哪？");
        assertNotNull(prefix);
        assertNotNull(fragment);
        assertFalse(italic(prefix), "未标记的前缀不应是斜体");
        assertTrue(italic(fragment));
    }

    // ==================== 颜色 ====================

    @Test
    void wholeTextItalicAndYellow() throws Exception {
        Component result = TellRawStyleParser.parse("\"<???>这是....哪？\" 斜体 黄色");
        assertTrue(italic(result.getStyle()));
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), result.getStyle().getColor());
    }

    @Test
    void mixedFragmentStyles() throws Exception {
        Component result = TellRawStyleParser.parse(
                "\"<???>这是....哪？\" 斜体 黄色=\"这是....哪？\" 红色=\"<???>\"");
        assertEquals("<???>这是....哪？", result.getString());

        Style head = styleOf(result, "<???>");
        Style tail = styleOf(result, "这是....哪？");
        assertNotNull(head);
        assertNotNull(tail);
        // 片段颜色覆盖基础颜色，基础斜体保持生效
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), head.getColor());
        assertTrue(italic(head));
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), tail.getColor());
        assertTrue(italic(tail));
    }

    @Test
    void repeatedFragmentMergesMarkersInsteadOfDropping() throws Exception {
        // 同一片段被多个标记命中：两个标记区间完全相同，必须合并而不是丢弃后写的那个
        Component result = TellRawStyleParser.parse(
                "\"<???> 这是......哪？\" 斜体=\"这是......哪？\" 紫色=\"这是......哪？\"");
        assertEquals("<???> 这是......哪？", result.getString());

        Style tail = styleOf(result, "这是......哪？");
        assertNotNull(tail);
        assertTrue(italic(tail), "斜体应当生效");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_PURPLE), tail.getColor(), "紫色应当生效");

        Style head = styleOf(result, "<???> ");
        assertNotNull(head);
        assertFalse(italic(head), "未被标记的前缀不应是斜体");
    }

    @Test
    void overlappingFragmentsBothApply() throws Exception {
        // 区间部分重叠时按输入顺序叠加，颜色以最后写的标记为准
        Component result = TellRawStyleParser.parse("\"abcd\" 红色=\"abc\" 蓝色=\"bcd\"");
        assertEquals("abcd", result.getString());

        Style head = styleOf(result, "a");
        Style middle = styleOf(result, "bc");
        Style tail = styleOf(result, "d");
        assertNotNull(head);
        assertNotNull(middle);
        assertNotNull(tail);
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), head.getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE), middle.getColor(), "重叠处颜色以最后写的标记为准");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE), tail.getColor());
    }

    @Test
    void englishNamesAreRecognised() throws Exception {
        Component result = TellRawStyleParser.parse("\"hello\" italic red");
        assertTrue(italic(result.getStyle()));
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), result.getStyle().getColor());
    }

    @Test
    void nameMatchingIgnoresCase() throws Exception {
        Component result = TellRawStyleParser.parse("\"hello\" ItaLic YeLLoW");
        assertTrue(italic(result.getStyle()));
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW), result.getStyle().getColor());
    }

    // ==================== 用法提示 ====================

    @Test
    void usageHintOnlyListsRealMarkers() {
        String hint = TellRawStyleRegistry.usageHint("message");
        assertTrue(hint.startsWith("<") && hint.endsWith(">"), "用法提示沿用原版 <参数名> 的形态：" + hint);
        assertTrue(hint.contains("message"), "提示里要保留原参数名，玩家才知道改的是哪个参数：" + hint);
        for (String marker : TellRawStyleRegistry.usageMarkers()) {
            assertTrue(hint.contains(marker), "提示要平铺出可以照抄的标记名，缺了「" + marker + "」：" + hint);
            assertTrue(TellRawStyleRegistry.findStyle(marker) != null
                            || TellRawStyleRegistry.findColor(marker) != null,
                    "提示中列出的「" + marker + "」必须是可用的样式或颜色");
        }
        // 直接用具体名字，不写「样式」「颜色」这类统称
        assertFalse(hint.contains("颜色"), "不要用「颜色」统称，直接列出具体名字：" + hint);
        assertFalse(hint.contains("样式"), "不要用「样式」统称，直接列出具体名字：" + hint);
    }

    @Test
    void styledTextArgumentCoversTellrawAndTitle() {
        assertTrue(TellRawStyleRegistry.isStyledTextArgument("message"),
                "tellraw 的 message 参数应支持扩展语法");
        // /title 的 title/subtitle/actionbar 三个子命令共用同一个参数名 title
        assertTrue(TellRawStyleRegistry.isStyledTextArgument("title"),
                "/title 的 title 参数应支持扩展语法");
        assertTrue(TellRawStyleRegistry.usageHint("title").contains("title"),
                "title 的用法提示要带上自己的参数名，不能照抄 tellraw 的 message：" + TellRawStyleRegistry.usageHint("title"));

        assertFalse(TellRawStyleRegistry.isStyledTextArgument("targets"), "实体选择参数不应被接管");
        assertFalse(TellRawStyleRegistry.isStyledTextArgument("duration"), "时长参数不应被接管");
        assertFalse(TellRawStyleRegistry.isStyledTextArgument("fadeIn"), "淡入时长参数不应被接管");
    }

    // ==================== 原版兼容 ====================

    @Test
    void plainTextKeepsOriginalBehaviour() throws Exception {
        Component result = TellRawStyleParser.parse("hello");
        assertEquals("hello", result.getString());
        assertEquals(Style.EMPTY, result.getStyle());
    }

    @Test
    void jsonComponentStillParses() throws Exception {
        Component result = TellRawStyleParser.parse("{\"text\":\"hi\",\"color\":\"red\"}");
        assertEquals("hi", result.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), result.getStyle().getColor());
    }

    // ==================== 接管判定 ====================

    @Test
    void looksStyled_onlyWhenMarkersFollowQuotedText() {
        assertTrue(TellRawStyleParser.looksStyled("\"文本\" 斜体"));
        assertTrue(TellRawStyleParser.looksStyled("\"文本\" 斜体=\"文本\""));
        assertTrue(TellRawStyleParser.looksStyled("  \"文本\" 斜体  "));
    }

    @Test
    void looksStyled_leavesEverythingElseToVanilla() {
        // 单个引号串、纯 JSON、裸词、未闭合引号都不接管
        assertFalse(TellRawStyleParser.looksStyled("\"文本\""));
        assertFalse(TellRawStyleParser.looksStyled("{\"text\":\"hi\"}"));
        assertFalse(TellRawStyleParser.looksStyled("[{\"text\":\"hi\"}]"));
        assertFalse(TellRawStyleParser.looksStyled("hello"));
        assertFalse(TellRawStyleParser.looksStyled("\"未闭合 斜体"));
        assertFalse(TellRawStyleParser.looksStyled(""));
        assertFalse(TellRawStyleParser.looksStyled(null));
    }

    @Test
    void looksStyled_handlesEscapedQuoteInText() {
        // 正文里的 \" 不算引号结尾，标记仍在闭合引号之后
        assertTrue(TellRawStyleParser.looksStyled("\"说\\\"你好\\\"\" 斜体"));
        assertFalse(TellRawStyleParser.looksStyled("\"说\\\"你好\\\"\""));
    }

    // ==================== 错误提示 ====================

    @Test
    void unknownMarkerIsRejected() {
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parse("\"hi\" 闪闪发光"));
    }

    @Test
    void markerValueMustBeQuoted() {
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parse("\"hi\" 斜体=hi"));
    }

    @Test
    void missingFragmentIsRejected() {
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parse("\"hi\" 斜体=\"不存在的片段\""));
    }

    // ==================== 多段与间隔 ====================

    @Test
    void multipleSegmentsSplitByQuotedStrings() throws Exception {
        TellRawStyledMessage message = TellRawStyleParser.parseMessage(
                "\"<狗修金>雪狐，帮我把那个——\" blue=\"雪狐，帮我把那个——\" \\间隔3.2秒"
                        + " \"<酒狐>......？\" 紫色=\"......？\" 斜体=\"......？\"");

        assertEquals(2, message.parts().size(), "两个引号串应当解析成两条先后显示的消息");
        assertTrue(message.isMultiPart());

        TellRawStyledMessage.Part first = message.parts().get(0);
        TellRawStyledMessage.Part second = message.parts().get(1);
        assertEquals("<狗修金>雪狐，帮我把那个——", first.component().getString());
        assertEquals(0, first.delayTicks(), "第一段随命令立即显示，不应带延迟");
        assertEquals(64, second.delayTicks(), "3.2 秒应换算成 64 tick");
        assertEquals("<酒狐>......？", second.component().getString());

        // 第一段：只有后半句是蓝色
        Style firstHead = styleOf(first.component(), "<狗修金>");
        Style firstTail = styleOf(first.component(), "雪狐，帮我把那个——");
        assertNotNull(firstHead);
        assertNotNull(firstTail);
        assertNull(firstHead.getColor(), "未标记的前缀不应有颜色");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE), firstTail.getColor());

        // 第二段：只有后半句是紫色斜体
        Style secondHead = styleOf(second.component(), "<酒狐>");
        Style secondTail = styleOf(second.component(), "......？");
        assertNotNull(secondHead);
        assertNotNull(secondTail);
        assertFalse(italic(secondHead), "未标记的前缀不应是斜体");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.DARK_PURPLE), secondTail.getColor());
        assertTrue(italic(secondTail));
    }

    @Test
    void parseReturnsFirstSegmentOfMultiPartMessage() throws Exception {
        Component first = TellRawStyleParser.parse("\"甲\" 斜体 \\间隔1秒 \"乙\" 粗体");
        assertEquals("甲", first.getString());
        assertTrue(italic(first.getStyle()));
    }

    @Test
    void segmentsWithoutDelayShowImmediately() throws Exception {
        TellRawStyledMessage message = TellRawStyleParser.parseMessage("\"甲\" 斜体 \"乙\"");
        assertEquals(2, message.parts().size());
        assertEquals(0, message.parts().get(1).delayTicks(), "没写间隔时下一段立即显示");
    }

    @Test
    void delayMarkerWorksWithoutBackslash() throws Exception {
        TellRawStyledMessage message = TellRawStyleParser.parseMessage("\"甲\" 间隔0.5秒 \"乙\"");
        assertEquals(2, message.parts().size());
        assertEquals(10, message.parts().get(1).delayTicks());
    }

    @Test
    void delayMarkerAcceptsWholeSeconds() throws Exception {
        TellRawStyledMessage message = TellRawStyleParser.parseMessage("\"甲\" 间隔2秒 \"乙\"");
        assertEquals(40, message.parts().get(1).delayTicks());
    }

    @Test
    void singleSegmentIsNotMultiPart() throws Exception {
        TellRawStyledMessage message = TellRawStyleParser.parseMessage("\"甲\" 斜体");
        assertEquals(1, message.parts().size());
        assertFalse(message.isMultiPart());
    }

    @Test
    void jsonMessageIsSinglePart() throws Exception {
        TellRawStyledMessage message = TellRawStyleParser.parseMessage("{\"text\":\"hi\"}");
        assertFalse(message.isMultiPart());
        assertEquals("hi", message.first().getString());
    }

    // ==================== 间隔写错 ====================

    @Test
    void danglingDelayIsRejected() {
        // 间隔后面没有下一段：等待会静默落空，必须报错
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parseMessage("\"甲\" 斜体 \\间隔1秒"));
    }

    @Test
    void consecutiveDelaysAreRejected() {
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parseMessage("\"甲\" \\间隔1秒 \\间隔2秒 \"乙\""));
    }

    @Test
    void malformedDelayIsRejected() {
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parseMessage("\"甲\" 间隔abc秒 \"乙\""),
                "秒数不是数字应当报错，而不是当成 0 秒");
        assertThrows(CommandSyntaxException.class,
                () -> TellRawStyleParser.parseMessage("\"甲\" 间隔3.2 \"乙\""),
                "漏了「秒」应当报错");
    }
}
