package io.github.zgxhzhr.superdbg.network;

/**
 * 药水效果同步包的扩展接口（Mixin duck typing）。
 * <p>
 * 原版 {@code ClientboundUpdateMobEffectPacket} 只能用 byte 携带 amplifier，
 * Mixin 给该包增加一个 int 字段承载完整等级（0-2147483646），
 * 网络传输也相应改用 VarInt。
 */
public interface FullAmplifierAccess {

    /** 未设置完整值时的哨兵（此时回退使用原版 byte 字段） */
    int UNSET = Integer.MIN_VALUE;

    int superdbg$getFullAmplifier();

    void superdbg$setFullAmplifier(int amplifier);
}
