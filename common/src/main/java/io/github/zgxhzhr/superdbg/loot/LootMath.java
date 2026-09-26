package io.github.zgxhzhr.superdbg.loot;

/**
 * 掉落覆盖的概率/数量计算（纯函数，不依赖 Minecraft 类，便于单元测试）。
 */
public final class LootMath {

    private LootMath() {
    }

    /** 随机源抽象：服务端用实体 RandomSource 包装，测试用固定序列 */
    public interface RandomLike {
        /** [0,1) 均匀浮点 */
        float nextFloat();

        /** {@code [0, bound)} 均匀整数；bound &lt;= 0 时直接返回 0 */
        int nextInt(int bound);
    }

    /** 概率上限（百分比） */
    public static final float MAX_CHANCE = 100.0F;
    /** 数量上限（每条规则单次掉落） */
    public static final int MAX_COUNT = 999;
    /** 规则条数上限（防恶意包） */
    public static final int MAX_ENTRIES = 64;

    /**
     * 有效概率（百分比）：基础概率 + 抢夺等级 × 每级加成，钳制到 [0, 100]。
     */
    public static float effectiveChance(float baseChance, int lootingLevel, float perLevelBonus) {
        if (lootingLevel < 0) {
            lootingLevel = 0;
        }
        float chance = baseChance + lootingLevel * perLevelBonus;
        return clampChance(chance);
    }

    /** 概率钳制到 [0, 100]，NaN 按 0 处理 */
    public static float clampChance(float chance) {
        if (Float.isNaN(chance)) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(MAX_CHANCE, chance));
    }

    /** 数量钳制到 [0, 999] */
    public static int clampCount(int count) {
        return Math.max(0, Math.min(MAX_COUNT, count));
    }

    /**
     * 概率判定：chance 为百分比（0-100）。100 必中，0 必不中。
     */
    public static boolean rollChance(RandomLike random, float chancePercent) {
        if (chancePercent >= MAX_CHANCE) {
            return true;
        }
        if (chancePercent <= 0.0F) {
            return false;
        }
        return random.nextFloat() * MAX_CHANCE < chancePercent;
    }

    /**
     * 数量判定：{@code [min, max]} 闭区间均匀随机；min == max 时固定值。
     * 调用前需保证 {@code 0 <= min <= max}（由 {@link #normalizeRange} 规整）。
     */
    public static int rollCount(RandomLike random, int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    /**
     * 规整数量范围：先各自钳制，再保证 {@code min <= max}（倒置时交换）。
     *
     * @return 长度为 2 的数组 [min, max]
     */
    public static int[] normalizeRange(int min, int max) {
        min = clampCount(min);
        max = clampCount(max);
        if (min > max) {
            int t = min;
            min = max;
            max = t;
        }
        return new int[]{min, max};
    }
}
