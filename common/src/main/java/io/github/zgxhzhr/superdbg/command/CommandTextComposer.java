package io.github.zgxhzhr.superdbg.command;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令文本的「多行 ↔ 单行」转换。
 *
 * <p>Minecraft 的命令本身只能是单行，书写 tellraw 这种长命令时只跟一行的写法既难读也难改。
 * 这里提供两个方向：{@link #joinLines} 把界面上按行写的内容拼成一条可执行命令，
 * {@link #wrapBySegments} 把已有的一条长命令按「段」折成多行便于阅读与修改。</p>
 *
 * <p>折行只改断行位置，不改内容：拼接时每行首尾空白被去掉、行间用一个空格相连，因此
 * 「折行 → 拼接」得到的是同一串命令词，语义不变（引号内的空格原样保留，因为整个引号串
 * 被当作一个词）。命令里本来的多余空格会被规范化成单个空格，这在命令语法里无差别。</p>
 */
public final class CommandTextComposer {

    /** 间隔标记前缀，与解析器保持一致；折行时它单独占一行。 */
    private static final String DELAY_PREFIX = "间隔";

    /** 间隔标记的结尾单位。 */
    private static final String DELAY_SUFFIX = "秒";

    private CommandTextComposer() {
    }

    /**
     * 把多行文本合并成一条单行命令：去掉每行首尾空白、丢弃空行、用单个空格连接各段。
     */
    public static String joinLines(@Nullable String multiline) {
        if (multiline == null || multiline.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String line : multiline.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(trimmed);
        }
        return joined.toString();
    }

    /**
     * 按上限截断文本，超出部分直接丢弃。
     *
     * <p>原版多行输入框的截断动作是调用 {@code StringUtil.truncateStringIfNecessary} 完成的，
     * 而那个调用点可能被别的模组包装，把传入的长度换成更小的值，于是长命令贴进去仍会被截。
     * 这里自己按上限算，不经过那个可能被改写的调用，行为与原版在同一上限下完全一致。</p>
     *
     * @param text  待截断的文本
     * @param limit 保留的最大长度；不足时返回空串
     */
    public static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit));
    }

    /**
     * 把一条单行命令按「段」折成多行，方便阅读与修改。
     *
     * <p>折行规则照着玩家自然的写法：命令名与目标留在首行，第一个正文也并到首行，
     * 之后每个正文、每个带 {@code =} 的标记、每个间隔标记各自占一行，不带值的标记
     * 跟在它所属的那一行末尾。</p>
     *
     * <p>只有命令里确实出现了带值标记或间隔标记（也就是本模组扩展语法的特征）时才折行；
     * 其余命令一字不动地返回，避免打扰普通命令的原有写法。</p>
     */
    public static String wrapBySegments(@Nullable String command) {
        if (command == null || command.isBlank()) {
            return command == null ? "" : command;
        }
        List<Token> tokens = tokenize(command);
        if (!hasSegmentBoundary(tokens)) {
            return command;
        }

        StringBuilder wrapped = new StringBuilder();
        // 首行还没放过「有段概念的词」时，命令名/目标/第一个正文都并到首行
        boolean contentStarted = false;
        for (Token token : tokens) {
            boolean onNewLine = token.kind != Kind.BARE && contentStarted;
            if (wrapped.length() > 0) {
                wrapped.append(onNewLine ? '\n' : ' ');
            }
            wrapped.append(token.text);
            if (token.kind != Kind.BARE) {
                contentStarted = true;
            }
        }
        return wrapped.toString();
    }

    /** 只有出现带值标记或间隔标记才值得折行；普通命令保持原样。 */
    private static boolean hasSegmentBoundary(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.kind == Kind.MARKER_VALUE || token.kind == Kind.DELAY) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按命令的词法切分：空白分隔、引号串是一个整体、带 {@code ="片段"} 的标记连名字算一个词。
     * 保留每个词的原文（含引号与转义），折行后拼回去才不会变形。
     */
    private static List<Token> tokenize(String command) {
        List<Token> tokens = new ArrayList<>();
        int length = command.length();
        int i = 0;
        while (i < length) {
            char c = command.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int start = i;
                i = skipQuoted(command, i);
                tokens.add(new Token(command.substring(start, i), Kind.TEXT));
                continue;
            }
            if (c == '=') {
                // 前面没有标记名可以挂靠的孤立等号：原样保留，别让它把循环卡住
                tokens.add(new Token("=", Kind.BARE));
                i++;
                continue;
            }

            int start = i;
            while (i < length) {
                char current = command.charAt(i);
                if (Character.isWhitespace(current) || current == '=' || current == '"') {
                    break;
                }
                i++;
            }
            String word = command.substring(start, i);

            // 带值标记：名字紧跟 = 与引号串，中间没有空白，整体算一个词
            if (i + 1 < length && command.charAt(i) == '=' && command.charAt(i + 1) == '"') {
                i = skipQuoted(command, i + 1);
                tokens.add(new Token(command.substring(start, i), Kind.MARKER_VALUE));
                continue;
            }
            tokens.add(new Token(word, isDelayToken(word) ? Kind.DELAY : Kind.BARE));
        }
        return tokens;
    }

    /** 从起始引号开始跳过整个引号串，返回闭合引号之后的下标；未闭合时返回文本末尾。 */
    private static int skipQuoted(String text, int quoteIndex) {
        int i = quoteIndex + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /** 是否是「间隔N秒」形式的标记；反斜杠可省略。 */
    private static boolean isDelayToken(String word) {
        String body = word.startsWith("\\") ? word.substring(1) : word;
        return body.startsWith(DELAY_PREFIX)
                && body.endsWith(DELAY_SUFFIX)
                && body.length() > DELAY_PREFIX.length() + DELAY_SUFFIX.length();
    }

    /** 词的类别，决定它是否单独占一行。 */
    private enum Kind {
        /** 命令名、目标、不带值的标记等普通词。 */
        BARE,
        /** 引号串，即一段正文。 */
        TEXT,
        /** 带 {@code ="片段"} 的样式/颜色标记。 */
        MARKER_VALUE,
        /** 间隔标记。 */
        DELAY
    }

    /** 一个词及其类别。 */
    private static final class Token {

        private final String text;
        private final Kind kind;

        private Token(String text, Kind kind) {
            this.text = text;
            this.kind = kind;
        }
    }
}
