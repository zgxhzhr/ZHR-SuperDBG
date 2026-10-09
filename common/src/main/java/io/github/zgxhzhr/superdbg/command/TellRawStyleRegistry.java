package io.github.zgxhzhr.superdbg.command;

import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * tellraw 扩展标记的名称表。
 *
 * <p>把玩家输入中的中文/英文标记名解析为原版可用的文本样式或颜色：
 * 样式对应 {@code Style} 的各个开关，颜色对应原版 {@link ChatFormatting} 的 16 色调色板。
 * 名称匹配不区分大小写，同一含义可登记多个别名（中英文、常见简写）。</p>
 *
 * <p>两处与直觉略有出入的原版限制，在此显式说明：原版没有独立的「橙色」，
 * 最接近的是金色的 {@link ChatFormatting#GOLD}；「紫色」按中文习惯取
 * {@link ChatFormatting#DARK_PURPLE}（#AA00AA），而 {@link ChatFormatting#LIGHT_PURPLE}
 * 另登记为「粉色」；「青色」取 {@link ChatFormatting#DARK_AQUA}（#00AAAA），
 * {@link ChatFormatting#AQUA}（#55FFFF）另登记为「浅蓝」。</p>
 */
public final class TellRawStyleRegistry {

    /** 文本样式开关，与 {@code Style} 的各个字段一一对应。 */
    public enum TextStyle {
        ITALIC,
        BOLD,
        UNDERLINED,
        STRIKETHROUGH,
        OBFUSCATED
    }

    /** 样式名 → 样式开关。 */
    private static final Map<String, TextStyle> STYLES = new HashMap<>();
    /** 颜色名 → 原版颜色。 */
    private static final Map<String, ChatFormatting> COLORS = new HashMap<>();

    /** 错误提示中展示的推荐样式名。 */
    private static final String STYLE_HINT = "斜体/粗体/下划线/删除线/混淆";
    /** 错误提示中展示的推荐颜色名。 */
    private static final String COLOR_HINT =
            "红色/黄色/蓝色/绿色/紫色/橙色/青色/白色/黑色/灰色/粉色/浅蓝/深蓝/深绿/深灰/金色";

    /** 灰色用法提示中按顺序列出的标记名，风格上先样式后颜色。 */
    private static final List<String> USAGE_MARKERS = List.of(
            "斜体", "粗体", "下划线", "删除线", "混淆",
            "红色", "黄色", "蓝色", "绿色", "紫色", "橙色", "青色", "白色", "黑色", "灰色");

    /** 灰色用法提示中展示的「只作用于某个片段」的写法示例。 */
    private static final String USAGE_FRAGMENT_EXAMPLE = "蓝色=\"片段\"";

    /** 灰色用法提示中展示的「两段之间等待」的写法示例。 */
    private static final String USAGE_DELAY_EXAMPLE = "\\间隔3.2秒";

    static {
        style(TextStyle.ITALIC, "斜体", "italic", "italics");
        style(TextStyle.BOLD, "粗体", "加粗", "bold");
        style(TextStyle.UNDERLINED, "下划线", "下画线", "underline", "underlined");
        style(TextStyle.STRIKETHROUGH, "删除线", "strikethrough");
        style(TextStyle.OBFUSCATED, "混淆", "随机", "obfuscated", "magic");

        color(ChatFormatting.BLACK, "黑色", "黑", "black");
        color(ChatFormatting.DARK_BLUE, "深蓝", "暗蓝", "dark_blue", "darkblue");
        color(ChatFormatting.DARK_GREEN, "深绿", "暗绿", "dark_green", "darkgreen");
        color(ChatFormatting.DARK_AQUA, "青色", "深青", "暗青", "cyan", "dark_aqua", "darkaqua");
        color(ChatFormatting.DARK_RED, "深红", "暗红", "dark_red", "darkred");
        color(ChatFormatting.DARK_PURPLE, "紫色", "dark_purple", "darkpurple", "purple");
        color(ChatFormatting.GOLD, "橙色", "金色", "gold", "orange");
        color(ChatFormatting.GRAY, "灰色", "灰", "gray", "grey");
        color(ChatFormatting.DARK_GRAY, "深灰", "暗灰", "dark_gray", "darkgray", "dark_grey");
        color(ChatFormatting.BLUE, "蓝色", "蓝", "blue");
        color(ChatFormatting.GREEN, "绿色", "绿", "green");
        color(ChatFormatting.AQUA, "浅蓝", "天蓝", "aqua", "light_blue");
        color(ChatFormatting.RED, "红色", "红", "red");
        color(ChatFormatting.LIGHT_PURPLE, "粉色", "粉红", "浅紫", "pink", "light_purple");
        color(ChatFormatting.YELLOW, "黄色", "黄", "yellow");
        color(ChatFormatting.WHITE, "白色", "白", "white");
    }

    private TellRawStyleRegistry() {
    }

    /** 登记一个样式名的全部别名。 */
    private static void style(TextStyle style, String... names) {
        for (String name : names) {
            STYLES.put(name.toLowerCase(Locale.ROOT), style);
        }
    }

    /** 登记一个颜色名的全部别名。 */
    private static void color(ChatFormatting color, String... names) {
        for (String name : names) {
            COLORS.put(name.toLowerCase(Locale.ROOT), color);
        }
    }

    /** 按名称查找样式，找不到返回 null。 */
    public static TextStyle findStyle(String name) {
        return STYLES.get(name.toLowerCase(Locale.ROOT));
    }

    /** 按名称查找颜色，找不到返回 null。 */
    public static ChatFormatting findColor(String name) {
        return COLORS.get(name.toLowerCase(Locale.ROOT));
    }

    /** 供错误提示使用的可用标记清单。 */
    public static String describeAvailable() {
        return "样式：" + STYLE_HINT + "；颜色：" + COLOR_HINT;
    }

    /**
     * 命令输入框下方那条灰色提示的文案，用来替换文本参数原本只显示的 {@code <参数名>}。
     *
     * <p>原版只给出参数名，玩家无从得知本模组扩充了哪些标记；这里在保留参数名的同时，把可以照着敲的
     * 标记名全部平铺出来，不写「样式」「颜色」这类统称——玩家要的就是能直接抄的名字。
     * 文案由 {@link #USAGE_MARKERS} 拼出，不另抄一份名字，避免清单与名称表走偏。</p>
     *
     * <p>末尾再补两个例子：{@code 蓝色="片段"} 说明标记可以只作用于一句话里的某几个字，
     * {@code \间隔3.2秒} 说明可以写成多段、段与段之间指定等待时间（见
     * {@link TellRawStyleParser#parseMessage}）。</p>
     */
    public static String usageHint(String argumentName) {
        return "<" + argumentName + "：" + String.join(" ", USAGE_MARKERS)
                + " " + USAGE_FRAGMENT_EXAMPLE + " " + USAGE_DELAY_EXAMPLE + ">";
    }

    /** 供单元测试核对用法提示中的标记名。 */
    public static List<String> usageMarkers() {
        return USAGE_MARKERS;
    }

    /**
     * 判断某个命令参数是否支持扩展语法。
     *
     * <p>支持的是原版用 {@code ComponentArgument} 承载显示文本、且位于命令末尾的两个参数：
     * {@code /tellraw <targets> <message>} 的 {@code message}，以及
     * {@code /title <targets> title|subtitle|actionbar <title>} 的 {@code title}
     * （三个子命令在原版里共用同一个参数名 {@code title}）。它们都是原版的「文本」参数，
     * 玩家最常在这里需要样式与颜色。</p>
     */
    public static boolean isStyledTextArgument(String name) {
        return "message".equals(name) || "title".equals(name);
    }
}
