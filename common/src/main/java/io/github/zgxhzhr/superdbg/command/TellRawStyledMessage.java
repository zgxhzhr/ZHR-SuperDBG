package io.github.zgxhzhr.superdbg.command;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 一次 tellraw 扩展语法的完整解析结果：按顺序排列的若干「消息段」。
 *
 * <p>每个消息段附带「相对上一段需要等待的 tick 数」，第一段恒为 0。单段输入就是本类的
 * 退化情形（只有一个延迟为 0 的段），其行为与旧的「解析出一个组件」完全一致。</p>
 *
 * <p>之所以要保留整份段列表而不是只留一个组件：{@code /tellraw} 执行时原版只负责发送
 * 解析返回的那一个组件，后面几段要靠服务端按各自延迟补发，因此解析结果必须把
 * 「还有哪些段、每段等多久」一并带出去。</p>
 */
public final class TellRawStyledMessage {

    /** 单个消息段：要显示的组件，以及相对上一段需要等待的 tick 数。 */
    public static final class Part {

        private final Component component;
        private final int delayTicks;

        Part(Component component, int delayTicks) {
            this.component = component;
            this.delayTicks = delayTicks;
        }

        public Component component() {
            return component;
        }

        public int delayTicks() {
            return delayTicks;
        }
    }

    private final List<Part> parts;

    TellRawStyledMessage(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    /** 只有一段、且不带延迟的消息，供「非扩展写法」与单段扩展写法共用。 */
    static TellRawStyledMessage single(Component component) {
        return new TellRawStyledMessage(List.of(new Part(component, 0)));
    }

    public List<Part> parts() {
        return parts;
    }

    /** 第一段的组件；{@code /tellraw} 由原版逻辑立即发送它。 */
    public Component first() {
        return parts.get(0).component();
    }

    /** 是否还有需要延迟补发的后续段。 */
    public boolean isMultiPart() {
        return parts.size() > 1;
    }
}
