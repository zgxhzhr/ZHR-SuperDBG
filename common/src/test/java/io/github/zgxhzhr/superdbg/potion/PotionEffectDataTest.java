package io.github.zgxhzhr.superdbg.potion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PotionEffectData} 数值收敛（clamp）逻辑的单元测试。
 * <p>
 * 服务端在写回药水效果前必须对客户端提交的等级/时长做防御性收敛，
 * 这里覆盖边界值，确保"永久"语义与上下限不被破坏。
 */
class PotionEffectDataTest {

    // ==================== 等级 ====================

    @Test
    void amplifierInRangeKept() {
        assertEquals(0, PotionEffectData.clampAmplifier(0));
        assertEquals(5, PotionEffectData.clampAmplifier(5));
        assertEquals(PotionEffectData.MAX_AMPLIFIER,
                PotionEffectData.clampAmplifier(PotionEffectData.MAX_AMPLIFIER));
    }

    @Test
    void amplifierBelowMinRaisedToMin() {
        assertEquals(PotionEffectData.MIN_AMPLIFIER, PotionEffectData.clampAmplifier(-1));
        assertEquals(PotionEffectData.MIN_AMPLIFIER, PotionEffectData.clampAmplifier(Integer.MIN_VALUE));
    }

    @Test
    void amplifierAboveMaxCapped() {
        assertEquals(PotionEffectData.MAX_AMPLIFIER,
                PotionEffectData.clampAmplifier(PotionEffectData.MAX_AMPLIFIER + 1));
        assertEquals(PotionEffectData.MAX_AMPLIFIER,
                PotionEffectData.clampAmplifier(Integer.MAX_VALUE));
    }

    // ==================== 时长 ====================

    @Test
    void infiniteDurationPreserved() {
        assertEquals(PotionEffectData.INFINITE_DURATION,
                PotionEffectData.clampDuration(PotionEffectData.INFINITE_DURATION));
    }

    @Test
    void otherNegativeDurationsBecomeZero() {
        assertEquals(0, PotionEffectData.clampDuration(-2));
        assertEquals(0, PotionEffectData.clampDuration(Integer.MIN_VALUE));
    }

    @Test
    void normalDurationKept() {
        assertEquals(0, PotionEffectData.clampDuration(0));
        assertEquals(200, PotionEffectData.clampDuration(200));
        assertEquals(PotionEffectData.MAX_DURATION,
                PotionEffectData.clampDuration(PotionEffectData.MAX_DURATION));
    }

    @Test
    void hugeDurationCapped() {
        assertEquals(PotionEffectData.MAX_DURATION,
                PotionEffectData.clampDuration(Integer.MAX_VALUE));
    }
}
