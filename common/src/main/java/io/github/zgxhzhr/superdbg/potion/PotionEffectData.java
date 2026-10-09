package io.github.zgxhzhr.superdbg.potion;

import net.minecraft.world.effect.MobEffect;

/**
     * 药水效果的不可变数据载体。
     *
     * @param effect   效果类型
     * @param amplifier 等级（0-{@link Integer#MAX_VALUE}，由 {@code MobEffectInstanceMixin}
     *                  放开 byte 序列化瓶颈支持）
     * @param duration  持续时长（tick），-1 表示永久
     * @param ambient   是否为环境效果（信标等来源，粒子更淡）
     * @param visible   是否显示粒子
     * @param showIcon  是否在 HUD 显示图标
     */
public record PotionEffectData(
        MobEffect effect,
        int amplifier,
        int duration,
        boolean ambient,
        boolean visible,
        boolean showIcon
) {
    /** 永久效果的持续时长标识。 */
    public static final int INFINITE_DURATION = -1;

    /** 允许的最小等级。 */
    public static final int MIN_AMPLIFIER = 0;
    /** 允许的最大等级：{@link Integer#MAX_VALUE} - 1（留余量防效果叠加计算溢出，由 MobEffectInstanceMixin 放开 byte 瓶颈支持）。 */
    public static final int MAX_AMPLIFIER = Integer.MAX_VALUE - 1;

    /** 允许的最大持续时长（tick），避免恶意超大值。 */
    public static final int MAX_DURATION = Integer.MAX_VALUE / 2;

    /**
     * 把等级收敛到合法区间（服务端防御性处理用：客户端校验不可信）。
     */
    public static int clampAmplifier(int amplifier) {
        if (amplifier < MIN_AMPLIFIER) {
            return MIN_AMPLIFIER;
        }
        return Math.min(amplifier, MAX_AMPLIFIER);
    }

    /**
     * 把时长收敛到合法区间：{@link #INFINITE_DURATION}（永久）原样保留，
     * 其余负数归零，超大值封顶到 {@link #MAX_DURATION}。
     */
    public static int clampDuration(int duration) {
        if (duration == INFINITE_DURATION) {
            return INFINITE_DURATION;
        }
        if (duration < 0) {
            return 0;
        }
        return Math.min(duration, MAX_DURATION);
    }

    /**
     * 校验 amplifier 与 duration 的合法性。
     *
     * @throws IllegalArgumentException 数值越界
     */
    public void validate() {
        if (amplifier < MIN_AMPLIFIER || amplifier > MAX_AMPLIFIER) {
            throw new IllegalArgumentException("amplifier 必须在 " + MIN_AMPLIFIER + "-" + MAX_AMPLIFIER + " 之间，实际为 " + amplifier);
        }
        if (duration != INFINITE_DURATION && duration < 0) {
            throw new IllegalArgumentException("duration 必须为 -1（永久）或非负数，实际为 " + duration);
        }
    }

    /** 是否为永久效果。 */
    public boolean isInfinite() {
        return duration == INFINITE_DURATION;
    }
}
