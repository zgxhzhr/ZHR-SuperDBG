package io.github.zgxhzhr.superdbg.attribute;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 属性基础值锁（双端通用）。
 * <p>
 * 背景：部分模组（如 extra_enchantments）会在玩家 tick 的任意阶段把属性
 * 基础值重算回默认值，"tick 末尾事后重写"无法覆盖全部重置时机。因此改用
 * 入口拦截：{@code AttributeInstanceMixin} 在每次
 * {@link AttributeInstance#setBaseValue(double)} 入口检查本锁，若该实例
 * 已锁定且新值不等于锁定值，则直接把入参改写为锁定值——任何模组、任何
 * 调用时机都无法把锁定属性写回其它值。
 * <p>
 * 键使用弱引用，属性实例随实体被回收时锁条目自动消失，无需手动清理
 * （但"改回默认值解除锁定"仍需主动 {@link #unlock}）。
 * 集成服务端（Netty/主线程）与客户端（渲染线程）共用本表，故加同步。
 */
public final class AttributeLock {

    private static final double EPSILON = 1.0E-9D;

    private static final Map<AttributeInstance, Double> LOCKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AttributeLock() {
    }

    /** 锁定指定属性实例的基础值。 */
    public static void lock(AttributeInstance instance, double baseValue) {
        if (instance != null) {
            LOCKS.put(instance, baseValue);
        }
    }

    /** 解除锁定（未锁定时为空操作）。 */
    public static void unlock(AttributeInstance instance) {
        if (instance != null) {
            LOCKS.remove(instance);
        }
    }

    /** 返回锁定值；未锁定返回 {@code null}。 */
    public static Double expected(AttributeInstance instance) {
        return instance == null ? null : LOCKS.get(instance);
    }

    /**
     * 把任意写入值规范化为锁定值；未锁定或写入值本就等于锁定值时原样返回。
     */
    public static double coerceIfLocked(AttributeInstance instance, double incoming) {
        Double expected = expected(instance);
        if (expected == null || Math.abs(expected - incoming) <= EPSILON) {
            return incoming;
        }
        return expected;
    }
}
