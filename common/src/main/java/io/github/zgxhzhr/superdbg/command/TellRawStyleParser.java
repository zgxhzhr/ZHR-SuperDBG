package io.github.zgxhzhr.superdbg.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * tellraw 扩展语法的解析器。
 *
 * <p>输入是一段原始文本，输出一份 {@link TellRawStyledMessage}：按顺序排列的若干「消息段」，
 * 每段是一个可直接用于 {@code sendSystemMessage} 的 {@link Component}。解析分两步：先兼容原版
 * （以左花括号或左方括号开头的输入按 JSON 文本组件解析），其余走扩展语法。</p>
 *
 * <p>扩展语法形如
 * {@code "正文" 样式/颜色标记 标记="片段" [\间隔N秒 "下一段正文" ...]}：</p>
 * <ul>
 *   <li>每一个引号字符串都是<b>一条独立的消息</b>，按输入顺序依次发送；</li>
 *   <li><b>不带值</b>的标记作用于同段正文，例如 {@code 斜体}、{@code 黄色}；</li>
 *   <li>带 {@code ="片段"} 的标记只作用于同段正文中首次出现的那一段，例如
 *       {@code 斜体="这是....哪？"}、{@code 红色="<???>"}；</li>
 *   <li>{@code \间隔N秒}（反斜杠可省略）表示<b>下一段相对上一段</b>要等待的秒数，N 可以是小数。</li>
 * </ul>
 *
 * <p>叠加规则：同一段内，不带值的标记构成「基础样式」，带值的标记在基础样式之上再追加
 * （颜色以片段标记为准，样式取并集），因此
 * {@code "…" 斜体 黄色="A" 红色="B"} 会得到 A 为斜体黄、B 为斜体红。</p>
 *
 * <p>例：{@code "<狗修金>雪狐，帮我把那个——" blue="雪狐，帮我把那个——" \间隔3.2秒 "<酒狐>......？" 紫色="......？" 斜体="......？"}
 * 会先显示第一行（后半句蓝色），3.2 秒后再显示第二行（后半句紫色斜体）。</p>
 */
public final class TellRawStyleParser {

    /** 一秒对应的服务端 tick 数，用来把「间隔N秒」换算成 tick 数。 */
    private static final int TICKS_PER_SECOND = 20;

    /** 「间隔N秒」标记的前缀；写法上允许带一个前导反斜杠（见 {@link #parseDelayTicks}）。 */
    private static final String DELAY_PREFIX = "间隔";

    /** 「间隔N秒」标记的结尾单位。 */
    private static final String DELAY_SUFFIX = "秒";

    /** 标记名既不是样式也不是颜色。 */
    public static final DynamicCommandExceptionType UNKNOWN_MARKER = new DynamicCommandExceptionType(
            name -> Component.literal("未知的样式或颜色标记「" + name + "」。可用标记 → "
                    + TellRawStyleRegistry.describeAvailable()));

    /** 标记后跟了 = 但没有引号值。 */
    public static final DynamicCommandExceptionType MISSING_VALUE = new DynamicCommandExceptionType(
            name -> Component.literal("标记「" + name + "」后面应当跟 =\"片段\"，例如 " + name + "=\"要应用的文字\""));

    /** 片段在正文中不存在。 */
    public static final DynamicCommandExceptionType FRAGMENT_NOT_FOUND = new DynamicCommandExceptionType(
            fragment -> Component.literal("正文中找不到要应用样式的片段：「" + fragment + "」"));

    /** 间隔标记写法不正确。 */
    public static final DynamicCommandExceptionType MALFORMED_DELAY = new DynamicCommandExceptionType(
            token -> Component.literal("间隔写法不正确：「" + token + "」应当形如 \\间隔3.2秒"));

    /** 间隔标记后面没有下一段消息。 */
    public static final DynamicCommandExceptionType DANGLING_DELAY = new DynamicCommandExceptionType(
            token -> Component.literal("「" + token + "」后面还需要一段消息，间隔标记不能放在最后"));

    /** 以 { 或 [ 开头的输入按 JSON 文本组件解析但解析失败。 */
    public static final DynamicCommandExceptionType INVALID_JSON = new DynamicCommandExceptionType(
            reason -> Component.literal("无效的 JSON 文本组件：" + reason));

    private TellRawStyleParser() {
    }

    /**
     * 判断原始输入是否使用了扩展语法，也就是「引号正文之后还跟着样式/颜色标记或后续消息段」。
     *
     * <p>只有这种情况才接管原版 {@code ComponentArgument} 的解析。原版遇到「引号串后还有
     * 多余内容」必定报错，因此接管不会影响任何原本合法的写法（纯 JSON 文本组件、单个引号串、
     * 裸词，以及其它命令的文本组件参数），把改动面压到最小。</p>
     */
    public static boolean looksStyled(@Nullable String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '"') {
            return false;
        }
        boolean escaped = false;
        for (int i = 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return !trimmed.substring(i + 1).isBlank();
            }
        }
        // 引号未闭合：交回原版，由原版给出它自己的报错
        return false;
    }

    /**
     * 解析一段 tellraw 文本，只取第一段的组件。
     *
     * <p>提供给不需要后续延迟段的调用方（含既有单元测试）；多段消息请用
     * {@link #parseMessage}。</p>
     */
    public static Component parse(@Nullable String raw) throws CommandSyntaxException {
        return parseMessage(raw).first();
    }

    /**
     * 解析一段 tellraw 文本，得到完整的消息段列表。
     *
     * @param raw 命令中 message 参数位置的原始字符串（可能含引号、样式标记与间隔标记）
     * @return 按顺序排列的消息段，第一段由原版 {@code /tellraw} 立即发送
     * @throws CommandSyntaxException 语法错误（由命令框架转成玩家可见的红色错误提示）
     */
    public static TellRawStyledMessage parseMessage(@Nullable String raw) throws CommandSyntaxException {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return TellRawStyledMessage.single(Component.empty());
        }
        // 原版兼容：JSON 文本组件原样交给原版解析器，扩展语法不介入
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') {
            return TellRawStyledMessage.single(parseJson(trimmed));
        }
        return parseStyledMessage(trimmed);
    }

    /** 原版行为：解析 JSON 文本组件。 */
    private static Component parseJson(String json) throws CommandSyntaxException {
        try {
            MutableComponent parsed = Component.Serializer.fromJson(json);
            return parsed != null ? parsed : Component.empty();
        } catch (Exception e) {
            throw INVALID_JSON.create(String.valueOf(e.getMessage()));
        }
    }

    /**
     * 扩展语法：若干「正文 + 样式/颜色标记」的消息段，段与段之间可用 {@code \间隔N秒} 指定等待。
     *
     * <p>分段依据是「出现新的引号串」：样式/颜色标记必须写成 {@code 标记} 或 {@code 标记="片段"}，
     * 裸的引号串只可能是一段的正文，因此见到引号串就开启新段。间隔标记记在下一段名下
     * （第 1 段的延迟恒为 0），这也正是玩家读到的语序：「等 3.2 秒，然后显示下一句」。</p>
     */
    private static TellRawStyledMessage parseStyledMessage(String raw) throws CommandSyntaxException {
        StringReader reader = new StringReader(raw);
        List<TellRawStyledMessage.Part> parts = new ArrayList<>();

        reader.skipWhitespace();
        SegmentBuilder builder = new SegmentBuilder(readToken(reader));
        // 当前段的延迟（相对上一段）；第 1 段恒为 0
        int currentDelay = 0;
        // 已读到、还没配上下一段正文的间隔标记
        String pendingDelayToken = null;
        int pendingDelayTicks = -1;

        while (true) {
            reader.skipWhitespace();
            if (!reader.canRead()) {
                break;
            }
            if (reader.peek() == '"') {
                // 新的引号串：收尾当前段，并让新段承担刚读到的间隔
                parts.add(new TellRawStyledMessage.Part(builder.build(), currentDelay));
                currentDelay = pendingDelayToken != null ? pendingDelayTicks : 0;
                pendingDelayToken = null;
                pendingDelayTicks = -1;
                builder = new SegmentBuilder(reader.readQuotedString());
                continue;
            }

            String token = readToken(reader);
            if (token.isEmpty()) {
                break;
            }

            int delayTicks = parseDelayTicks(token);
            if (delayTicks >= 0) {
                if (pendingDelayToken != null) {
                    // 连着两个间隔标记中间没有正文，前一个会静默失效，直接报错
                    throw DANGLING_DELAY.create(pendingDelayToken);
                }
                pendingDelayToken = token;
                pendingDelayTicks = delayTicks;
                continue;
            }

            applyMarker(reader, builder, token);
        }

        if (pendingDelayToken != null) {
            throw DANGLING_DELAY.create(pendingDelayToken);
        }
        parts.add(new TellRawStyledMessage.Part(builder.build(), currentDelay));
        return new TellRawStyledMessage(parts);
    }

    /** 把一个样式/颜色标记应用到当前段：带 {@code ="片段"} 的落到片段上，否则作用于整段。 */
    private static void applyMarker(StringReader reader, SegmentBuilder builder, String marker)
            throws CommandSyntaxException {
        TellRawStyleRegistry.TextStyle style = TellRawStyleRegistry.findStyle(marker);
        ChatFormatting color = TellRawStyleRegistry.findColor(marker);
        if (style == null && color == null) {
            throw UNKNOWN_MARKER.create(marker);
        }
        if (reader.canRead() && reader.peek() == '=') {
            reader.skip();
            if (!reader.canRead() || reader.peek() != '"') {
                throw MISSING_VALUE.create(marker);
            }
            builder.addFragment(reader.readQuotedString(), style, color);
        } else {
            builder.applyBase(style, color);
        }
    }

    /**
     * 解析 {@code \间隔N秒} 形式的间隔标记，返回换算成 tick 的等待时间。
     *
     * <p>不是间隔标记时返回 -1；是间隔标记但写法不正确时抛
     * {@link #MALFORMED_DELAY}，避免把玩家写错的秒数静默当成 0 秒。</p>
     */
    private static int parseDelayTicks(String token) throws CommandSyntaxException {
        // 反斜杠只是为了在命令里读起来更像一个「元指令」，可有可无
        String body = token.startsWith("\\") ? token.substring(1) : token;
        if (!body.startsWith(DELAY_PREFIX)) {
            return -1;
        }
        String rest = body.substring(DELAY_PREFIX.length());
        if (rest.length() <= DELAY_SUFFIX.length() || !rest.endsWith(DELAY_SUFFIX)) {
            throw MALFORMED_DELAY.create(token);
        }
        String number = rest.substring(0, rest.length() - DELAY_SUFFIX.length()).trim();
        double seconds;
        try {
            seconds = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw MALFORMED_DELAY.create(token);
        }
        // NaN 与无穷大：前者和 0 的任何比较都为假，后者会溢出成错误 tick 数，都按写法错误处理
        if (!(seconds >= 0) || Double.isInfinite(seconds)
                || seconds * TICKS_PER_SECOND > Integer.MAX_VALUE) {
            throw MALFORMED_DELAY.create(token);
        }
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }

    /**
     * 按所有片段边界把正文切成互不重叠的小区间，每个区间叠加「基础样式 + 覆盖该区间的全部片段样式」。
     *
     * <p>同一段文字被多个标记命中时必须合并而不是丢弃：例如 {@code 斜体="X" 紫色="X"} 里两个
     * 标记的区间完全相同，若把后一个当成「重叠」跳过，颜色就会被静默忽略。片段部分重叠时同样
     * 按输入顺序依次叠加，颜色以最后写的标记为准。</p>
     */
    private static Component buildSegments(String text, StyleSpec base, List<Segment> segments)
            throws CommandSyntaxException {
        for (Segment segment : segments) {
            int index = text.indexOf(segment.fragment);
            if (index < 0) {
                throw FRAGMENT_NOT_FOUND.create(segment.fragment);
            }
            segment.start = index;
            segment.end = index + segment.fragment.length();
        }

        // 边界集合 = 正文首尾 + 每个片段的起止；排序去重后相邻两点就是一个区间
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boundaries.add(text.length());
        for (Segment segment : segments) {
            boundaries.add(segment.start);
            boundaries.add(segment.end);
        }

        MutableComponent result = Component.empty();
        Iterator<Integer> iterator = boundaries.iterator();
        int from = iterator.next();
        while (iterator.hasNext()) {
            int to = iterator.next();
            StyleSpec spec = base.copy();
            // segments 保持输入顺序，后写的标记覆盖先写的
            for (Segment segment : segments) {
                if (segment.start <= from && segment.end >= to) {
                    spec = spec.mergedWith(segment.spec);
                }
            }
            result.append(Component.literal(text.substring(from, to)).withStyle(spec.toStyle()));
            from = to;
        }
        return result;
    }

    /**
     * 读取一个 token：引号字符串走 Brigadier 的引号解析（自动处理 {@code \"} 转义），
     * 裸词读到空白、{@code =} 或引号为止。
     *
     * <p>把引号也算作裸词的终止符，是为了让 {@code \间隔3.2秒"下一段"} 这种「间隔与下一段之间
     * 漏了空格」的写法也能正确切分，而不是把引号吞进秒数里。</p>
     */
    private static String readToken(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && reader.peek() == '"') {
            return reader.readQuotedString();
        }
        int start = reader.getCursor();
        while (reader.canRead()) {
            char c = reader.peek();
            if (Character.isWhitespace(c) || c == '=' || c == '"') {
                break;
            }
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    /** 单个消息段的组装：正文、基础样式与若干片段样式，最终产出一个 {@link Component}。 */
    private static final class SegmentBuilder {

        private final String text;
        private final StyleSpec base = new StyleSpec();
        private final List<Segment> segments = new ArrayList<>();

        private SegmentBuilder(String text) {
            this.text = text;
        }

        /** 不带值的标记：作用于整段。 */
        private void applyBase(@Nullable TellRawStyleRegistry.TextStyle style, @Nullable ChatFormatting color) {
            base.applyStyle(style);
            base.applyColor(color);
        }

        /** 带 {@code ="片段"} 的标记：只作用于首次出现的那个片段。 */
        private void addFragment(String fragment, @Nullable TellRawStyleRegistry.TextStyle style,
                                 @Nullable ChatFormatting color) {
            StyleSpec spec = new StyleSpec();
            spec.applyStyle(style);
            spec.applyColor(color);
            segments.add(new Segment(fragment, spec));
        }

        private Component build() throws CommandSyntaxException {
            if (segments.isEmpty()) {
                return Component.literal(text).withStyle(base.toStyle());
            }
            return buildSegments(text, base, segments);
        }
    }

    /** 一段待应用样式的片段及其在正文中的位置。 */
    private static final class Segment {

        private final String fragment;
        private final StyleSpec spec;
        private int start;
        private int end;

        private Segment(String fragment, StyleSpec spec) {
            this.fragment = fragment;
            this.spec = spec;
        }
    }

    /** 可叠加的样式描述，最终转换成不可变的 {@link Style}。 */
    private static final class StyleSpec {

        private Boolean italic;
        private Boolean bold;
        private Boolean underlined;
        private Boolean strikethrough;
        private Boolean obfuscated;
        private ChatFormatting color;

        /** 应用一个样式开关；传 null 表示该标记不是样式（是颜色）。 */
        private void applyStyle(@Nullable TellRawStyleRegistry.TextStyle style) {
            if (style == null) {
                return;
            }
            // 刻意用 if/else 而非 switch：枚举 switch 会生成惰性加载的合成类，
            // 热替换 jar 后首次执行会抛 NoClassDefFoundError
            if (style == TellRawStyleRegistry.TextStyle.ITALIC) {
                italic = true;
            } else if (style == TellRawStyleRegistry.TextStyle.BOLD) {
                bold = true;
            } else if (style == TellRawStyleRegistry.TextStyle.UNDERLINED) {
                underlined = true;
            } else if (style == TellRawStyleRegistry.TextStyle.STRIKETHROUGH) {
                strikethrough = true;
            } else if (style == TellRawStyleRegistry.TextStyle.OBFUSCATED) {
                obfuscated = true;
            }
        }

        /** 应用一个颜色；传 null 表示该标记不是颜色（是样式）。 */
        private void applyColor(@Nullable ChatFormatting value) {
            if (value != null) {
                color = value;
            }
        }

        /** 复制一份，供「基础样式 + 片段样式」叠加使用。 */
        private StyleSpec copy() {
            StyleSpec copy = new StyleSpec();
            copy.italic = italic;
            copy.bold = bold;
            copy.underlined = underlined;
            copy.strikethrough = strikethrough;
            copy.obfuscated = obfuscated;
            copy.color = color;
            return copy;
        }

        /** 叠加：片段样式在基础样式之上生效，颜色以片段为准，样式取并集。 */
        private StyleSpec mergedWith(StyleSpec overlay) {
            StyleSpec merged = copy();
            if (overlay.italic != null) {
                merged.italic = overlay.italic;
            }
            if (overlay.bold != null) {
                merged.bold = overlay.bold;
            }
            if (overlay.underlined != null) {
                merged.underlined = overlay.underlined;
            }
            if (overlay.strikethrough != null) {
                merged.strikethrough = overlay.strikethrough;
            }
            if (overlay.obfuscated != null) {
                merged.obfuscated = overlay.obfuscated;
            }
            if (overlay.color != null) {
                merged.color = overlay.color;
            }
            return merged;
        }

        private Style toStyle() {
            Style style = Style.EMPTY;
            if (italic != null) {
                style = style.withItalic(italic);
            }
            if (bold != null) {
                style = style.withBold(bold);
            }
            if (underlined != null) {
                style = style.withUnderlined(underlined);
            }
            if (strikethrough != null) {
                style = style.withStrikethrough(strikethrough);
            }
            if (obfuscated != null) {
                style = style.withObfuscated(obfuscated);
            }
            if (color != null) {
                style = style.withColor(color);
            }
            return style;
        }
    }
}
