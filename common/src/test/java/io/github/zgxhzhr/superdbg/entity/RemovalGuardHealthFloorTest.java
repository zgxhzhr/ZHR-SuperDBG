package io.github.zgxhzhr.superdbg.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防移除「禁止降血」判定单元测试。
 * <p>
 * 驱动纯函数 {@link RemovalGuard#isIllegalHealthDrop}：守卫实体除调试器设置外不允许
 * 任何降血——只要当前血量低于基准（超出浮点误差）即判非法并回滚；回血与持平一律合法。
 * 该判定用于拦下"绕过伤害系统直接压低血量"（含用 MethodHandle/VarHandle 直写血量
 * 同步数据）的压血，与调用栈、伤害类型、模组身份无关。
 */
class RemovalGuardHealthFloorTest {

    /** 血量持平不算降血。 */
    @Test
    void equalHealthIsAllowed() {
        assertFalse(RemovalGuard.isIllegalHealthDrop(20.0F, 20.0F), "血量持平不应判非法");
    }

    /** 回血合法（再生、治疗、调试器回满）。 */
    @Test
    void healingIsAllowed() {
        assertFalse(RemovalGuard.isIllegalHealthDrop(5.0F, 20.0F), "回血不应判非法");
    }

    /** 浮点噪声（误差以内）不算降血。 */
    @Test
    void floatingNoiseIsAllowed() {
        assertFalse(RemovalGuard.isIllegalHealthDrop(20.0F, 20.0F - 5.0E-5F), "误差以内不应判非法");
    }

    /** 任何可观测的降血都判非法——哪怕只掉 1 点（守卫实体不允许被磨死）。 */
    @Test
    void anyObservableDropIsIllegal() {
        assertTrue(RemovalGuard.isIllegalHealthDrop(20.0F, 19.0F), "掉 1 点应判非法");
        assertTrue(RemovalGuard.isIllegalHealthDrop(1.0F, 0.0F), "掉到 0 应判非法");
    }

    /** 负血量（绕过型压血致死后的残留值）判非法。 */
    @Test
    void negativeHealthIsIllegal() {
        assertTrue(RemovalGuard.isIllegalHealthDrop(20.0F, -163159.75F), "负血量应判非法");
        assertTrue(RemovalGuard.isIllegalHealthDrop(20.0F, Float.NEGATIVE_INFINITY), "负无穷应判非法");
    }
}
