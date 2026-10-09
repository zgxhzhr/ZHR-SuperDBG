package io.github.zgxhzhr.superdbg.command;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 命令链解析：把一行里用 {@code \间隔N秒} 串起来的多条命令拆成「命令 + 等待时长」序列。
 *
 * <p>语法：{@code 命令1 \间隔N秒 命令2 \间隔N秒 命令3 ...}，其中等待时长相对<b>上一条命令</b>，
 * 反斜杠可省略，秒数可为小数（{@code 间隔1.2秒}）。后续命令可带 {@code /} 前缀，也可省略——
 * 省略时按「是否为已注册的命令名」判定，避免把消息正文里的「间隔N秒」误当作命令分隔符。</p>
 *
 * <p>与 tellraw 的多段消息语法互不干扰：引号串内部一律不切分；引号外也必须「后面确实跟着一条命令」
 * 才切分。因此 {@code "段1" 标记 \间隔3.2秒 "段2"} 中下一段以引号开头，仍由 tellraw 自己的
 * 多段解析处理。</p>
 */
public final class CommandChainParser {

    /** 一秒对应的服务端 tick 数。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 分隔标记前缀（与 tellraw 的段间间隔写法一致）。 */
    private static final String DELAY_PREFIX = "间隔";

    /** 分隔标记结尾的单位。 */
    private static final String DELAY_SUFFIX = "秒";

    private CommandChainParser() {
    }

    /** 一段命令及其相对上一条命令的等待时长（第一条通常为 0，即立即执行）。 */
    public record Segment(String command, int delayTicks) {
    }

    /** 快速判定：字符串里是否可能出现分隔标记。 */
    public static boolean containsDelayMarker(@Nullable String input) {
        return input != null && input.contains(DELAY_PREFIX);
    }

    /**
     * 按 {@code \间隔N秒} 把命令串拆成命令链。
     *
     * @param input         原始命令串（可含 {@code /} 前缀，可为多段）
     * @param isCommandRoot 判定一个裸词是否为已注册的命令名
     * @return 命令链；不含有效分隔符时返回只含一条命令的列表（原串原样保留）
     */
    public static List<Segment> split(@Nullable String input, Predicate<String> isCommandRoot) {
        List<Segment> segments = new ArrayList<>();
        if (input == null) {
            return segments;
        }
        StringBuilder current = new StringBuilder();
        int pendingDelayTicks = 0;
        boolean inQuotes = false;
        int index = 0;
        while (index < input.length()) {
            char c = input.charAt(index);
            // 转义引号：原样保留，不切换引号状态
            if (c == '\\' && index + 1 < input.length() && input.charAt(index + 1) == '"') {
                current.append(c).append('"');
                index += 2;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
                index++;
                continue;
            }
            if (!inQuotes) {
                DelayMatch match = matchDelay(input, index);
                if (match != null && startsCommand(input, index + match.length(), isCommandRoot)) {
                    String text = current.toString().trim();
                    if (text.isEmpty()) {
                        // 分隔符前没有实际命令（整串以分隔符开头）：延迟并入下一条命令
                        pendingDelayTicks += match.delayTicks();
                    } else {
                        segments.add(new Segment(text, pendingDelayTicks));
                        pendingDelayTicks = match.delayTicks();
                    }
                    current.setLength(0);
                    index += match.length();
                    continue;
                }
            }
            current.append(c);
            index++;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            segments.add(new Segment(tail, pendingDelayTicks));
        }
        return segments;
    }

    /**
     * 从 {@code from} 处匹配 {@code \间隔N秒}（反斜杠可省略）。
     *
     * @return 匹配到的等待 tick 数与消耗的字符数；不匹配返回 null
     */
    @Nullable
    private static DelayMatch matchDelay(String input, int from) {
        int i = from;
        if (i < input.length() && input.charAt(i) == '\\') {
            i++;
        }
        if (!input.startsWith(DELAY_PREFIX, i)) {
            return null;
        }
        i += DELAY_PREFIX.length();
        int digitsStart = i;
        while (i < input.length() && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
            i++;
        }
        int digitsEnd = i;
        if (digitsStart == digitsEnd || !input.startsWith(DELAY_SUFFIX, i)) {
            return null;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(input.substring(digitsStart, digitsEnd));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(seconds >= 0.0D)) {
            return null;
        }
        i += DELAY_SUFFIX.length();
        int ticks = (int) Math.round(seconds * TICKS_PER_SECOND);
        return new DelayMatch(ticks, i - from);
    }

    /** 判定 {@code from} 起（跳过空白后）是否为一条新命令：{@code /} 前缀，或已注册的命令名。 */
    private static boolean startsCommand(String input, int from, Predicate<String> isCommandRoot) {
        int i = from;
        while (i < input.length() && Character.isWhitespace(input.charAt(i))) {
            i++;
        }
        if (i >= input.length()) {
            return false;
        }
        if (input.charAt(i) == '/') {
            return true;
        }
        int start = i;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            return false;
        }
        return isCommandRoot.test(input.substring(start, i));
    }

    /** 一次分隔标记匹配结果。 */
    private record DelayMatch(int delayTicks, int length) {
    }
}
