package io.github.zgxhzhr.superdbg.loot;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LootMath} 纯函数单元测试。
 */
class LootMathTest {

    /** 固定序列随机源：按序返回预设值，耗尽后重复最后一个值 */
    private static final class FixedRandom implements LootMath.RandomLike {
        private final Queue<Float> floats = new ArrayDeque<>();
        private final Queue<Integer> ints = new ArrayDeque<>();
        private float lastFloat = 0.0F;
        private int lastInt = 0;

        FixedRandom floats(float... values) {
            for (float v : values) {
                floats.add(v);
            }
            return this;
        }

        FixedRandom ints(int... values) {
            for (int v : values) {
                ints.add(v);
            }
            return this;
        }

        @Override
        public float nextFloat() {
            Float v = floats.poll();
            if (v != null) {
                lastFloat = v;
            }
            return lastFloat;
        }

        @Override
        public int nextInt(int bound) {
            if (bound <= 0) {
                return 0;
            }
            Integer v = ints.poll();
            if (v != null) {
                lastInt = v;
            }
            return lastInt % bound;
        }
    }

    // ==================== clampChance ====================

    @Test
    void clampChance_keepsNormalValue() {
        assertEquals(37.5F, LootMath.clampChance(37.5F), 1e-6);
        assertEquals(0.0F, LootMath.clampChance(0.0F), 1e-6);
        assertEquals(100.0F, LootMath.clampChance(100.0F), 1e-6);
    }

    @Test
    void clampChance_clampsOutOfRange() {
        assertEquals(0.0F, LootMath.clampChance(-0.1F), 1e-6);
        assertEquals(0.0F, LootMath.clampChance(-100.0F), 1e-6);
        assertEquals(100.0F, LootMath.clampChance(100.1F), 1e-6);
        assertEquals(100.0F, LootMath.clampChance(Float.MAX_VALUE), 1e-6);
        assertEquals(100.0F, LootMath.clampChance(Float.POSITIVE_INFINITY), 1e-6);
    }

    @Test
    void clampChance_nanBecomesZero() {
        assertEquals(0.0F, LootMath.clampChance(Float.NaN), 1e-6);
    }

    // ==================== clampCount ====================

    @Test
    void clampCount_clampsToRange() {
        assertEquals(0, LootMath.clampCount(-5));
        assertEquals(0, LootMath.clampCount(0));
        assertEquals(7, LootMath.clampCount(7));
        assertEquals(999, LootMath.clampCount(999));
        assertEquals(999, LootMath.clampCount(1000));
        assertEquals(999, LootMath.clampCount(Integer.MAX_VALUE));
    }

    // ==================== effectiveChance ====================

    @Test
    void effectiveChance_appliesPerLevelBonus() {
        // 基础 50% + 抢夺 2 级 × 每级 10% = 70%
        assertEquals(70.0F, LootMath.effectiveChance(50.0F, 2, 10.0F), 1e-6);
        // 0 级抢夺不加成
        assertEquals(50.0F, LootMath.effectiveChance(50.0F, 0, 10.0F), 1e-6);
    }

    @Test
    void effectiveChance_clampsToHundred() {
        assertEquals(100.0F, LootMath.effectiveChance(95.0F, 3, 10.0F), 1e-6);
    }

    @Test
    void effectiveChance_negativeLootingTreatedAsZero() {
        assertEquals(30.0F, LootMath.effectiveChance(30.0F, -1, 10.0F), 1e-6);
    }

    @Test
    void effectiveChance_negativeBonusCanReduce() {
        // 允许负加成（每级抢夺降低概率），结果钳制到 0
        assertEquals(0.0F, LootMath.effectiveChance(5.0F, 3, -10.0F), 1e-6);
    }

    // ==================== rollChance ====================

    @Test
    void rollChance_hundredAlwaysHits() {
        // 无论随机值多大，100% 必中
        FixedRandom rand = new FixedRandom().floats(0.9999F);
        assertTrue(LootMath.rollChance(rand, 100.0F));
    }

    @Test
    void rollChance_zeroNeverHits() {
        // 无论随机值多小，0% 必不中
        FixedRandom rand = new FixedRandom().floats(0.0F);
        assertFalse(LootMath.rollChance(rand, 0.0F));
    }

    @Test
    void rollChance_boundaryUsesLessThan() {
        // 50%：随机值 0.49 → 49 < 50 命中；0.50 → 50 < 50 不成立，不命中
        assertTrue(LootMath.rollChance(new FixedRandom().floats(0.49F), 50.0F));
        assertFalse(LootMath.rollChance(new FixedRandom().floats(0.50F), 50.0F));
    }

    // ==================== rollCount ====================

    @Test
    void rollCount_fixedWhenMinEqualsMax() {
        FixedRandom rand = new FixedRandom();
        assertEquals(3, LootMath.rollCount(rand, 3, 3));
    }

    @Test
    void rollCount_inclusiveBounds() {
        // 区间 [2, 5] → bound = 4；nextInt 返回 0 → 最小值，返回 3 → 最大值
        assertEquals(2, LootMath.rollCount(new FixedRandom().ints(0), 2, 5));
        assertEquals(5, LootMath.rollCount(new FixedRandom().ints(3), 2, 5));
        assertEquals(4, LootMath.rollCount(new FixedRandom().ints(2), 2, 5));
    }

    // ==================== normalizeRange ====================

    @Test
    void normalizeRange_swapsInverted() {
        assertArrayEquals(new int[]{3, 5}, LootMath.normalizeRange(5, 3));
    }

    @Test
    void normalizeRange_clampsBeforeSwap() {
        assertArrayEquals(new int[]{0, 999}, LootMath.normalizeRange(-5, 2000));
        assertArrayEquals(new int[]{0, 0}, LootMath.normalizeRange(-3, -1));
        assertArrayEquals(new int[]{999, 999}, LootMath.normalizeRange(5000, 1000));
    }

    @Test
    void normalizeRange_keepsValid() {
        assertArrayEquals(new int[]{1, 64}, LootMath.normalizeRange(1, 64));
    }
}
