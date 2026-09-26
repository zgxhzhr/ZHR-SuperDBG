package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 将药水效果的 amplifier（等级）存储从 byte 扩展为 int，
 * 支持 0-{@link Integer#MAX_VALUE} 范围。
 * <p>
 * 原版 {@code MobEffectInstance.writeDetailsTo} 使用 {@code putByte("Amplifier", (byte) amplifier)}，
 * 导致大于 127 的等级被截断；{@code loadSpecifiedEffect} 使用 {@code getByte} 配合
 * {@code Math.max(0, i)}，进一步把所有 ≥ 256 的等级归到 0-255。
 * <p>
 * 本 Mixin 同时放开两端：
 * <ul>
 *   <li>写入端：在 {@code writeDetailsTo} 的 RETURN 用 {@code putInt} 覆盖 Amplifier 字段，
 *       把它从 ByteTag 升级为 IntTag，支持 int 全范围。</li>
 *   <li>读取端：在 {@code loadSpecifiedEffect} 内拦截 {@code getByte} 与 {@code Math.max}，
 *       用栈式 ThreadLocal 应对 HiddenEffect 嵌套递归——getByte 时若 NBT 中是 IntTag
 *       则取完整 int 压栈并返回占位 byte；Math.max 时弹栈取完整 int 还原。
 *       兼容旧 ByteTag/ShortTag 存档：按无符号解释为 0-255。</li>
 * </ul>
 */
@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin {

    /** 读取端 ThreadLocal 栈：每层递归压一个 amplifier 完整 int 值。 */
    private static final ThreadLocal<Deque<Integer>> AMP_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * 写入端：在 {@code writeDetailsTo} 返回后，用 {@code putInt} 覆盖 Amplifier 字段，
     * 把它从 ByteTag 升级为 IntTag，保留原始 int amplifier。
     * <p>
     * 注：原版 {@code putByte("Amplifier", (byte) amp)} 已在方法体中执行，
     * 本注入在 RETURN 时再次写入，覆盖 ByteTag 为 IntTag。
     */
    @Inject(
            method = "writeDetailsTo(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("RETURN")
    )
    private void superdbg$writeAmplifierAsInt(CompoundTag nbt, CallbackInfo ci) {
        MobEffectInstance self = (MobEffectInstance) (Object) this;
        nbt.putInt("Amplifier", self.getAmplifier());
    }

    /**
     * 读取端 1：拦截 {@code loadSpecifiedEffect} 中的 {@code pNbt.getByte("Amplifier")}。
     * <p>
     * 若 NBT 中 Amplifier 是 IntTag（新存档），取完整 int 压栈并返回 (byte) 0 占位
     * （后续 {@link #superdbg$restoreAmplifier} 会从栈弹出完整值）。
     * 否则按旧 ByteTag/ShortTag 存档处理，无符号还原为 0-255 压栈后返回原字节。
     */
    @Redirect(
            method = "loadSpecifiedEffect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;getByte(Ljava/lang/String;)B")
    )
    private static byte superdbg$readAmplifier(CompoundTag nbt, String key) {
        if (!"Amplifier".equals(key)) {
            return nbt.getByte(key);
        }
        int full;
        if (nbt.contains(key, Tag.TAG_INT)) {
            // 新存档：IntTag，取完整 int
            full = nbt.getInt(key);
        } else {
            // 旧存档：ByteTag/ShortTag，按无符号还原为 0-255
            full = nbt.getByte(key) & 0xFF;
        }
        AMP_STACK.get().push(full);
        // 占位 byte，下游 Math.max 会被我们的 @Redirect 拦截并从栈还原
        return (byte) 0;
    }

    /**
     * 读取端 2：拦截 {@code loadSpecifiedEffect} 中的 {@code Math.max(0, i)}。
     * <p>
     * 从 ThreadLocal 栈弹出完整 amplifier int，应用非负下限保护后返回。
     * 栈空（理论上不应发生）时回退原版逻辑。
     */
    @Redirect(
            method = "loadSpecifiedEffect",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I")
    )
    private static int superdbg$restoreAmplifier(int a, int b) {
        Deque<Integer> stack = AMP_STACK.get();
        if (!stack.isEmpty()) {
            int stored = stack.pop();
            return Math.max(0, stored);
        }
        return Math.max(a, b);
    }

    /**
     * 瞬间效果（如瞬间治疗）在有时长时每 tick 应用效果。
     * <p>
     * 原版 {@code tick} 跳过瞬间效果的 {@code applyEffectTick}，
     * 导致通过编辑器设置时长的瞬间效果只会倒计时但不生效。
     * 此注入在 tick 开头对有持续时间（含无限 -1）的瞬间效果
     * 调用 {@code applyInstantenousEffect}，实现持续恢复/伤害等效果。
     * <p>
     * 注：原版 {@link MobEffectInstance#mapDuration} 已正确跳过无限时长递减，
     * 无需额外拦截 tickDownDuration。
     */
    @Inject(
            method = "tick(Lnet/minecraft/world/entity/LivingEntity;Ljava/lang/Runnable;)Z",
            at = @At("HEAD")
    )
    private void superdbg$instantEffectPerTick(LivingEntity entity, Runnable onFinished, CallbackInfoReturnable<Boolean> cir) {
        MobEffectInstance self = (MobEffectInstance) (Object) this;
        // duration != 0 覆盖正数时长和 INFINITE_DURATION(-1)
        if (self.getDuration() != 0 && !entity.level().isClientSide) {
            MobEffect effect = self.getEffect();
            if (effect.isInstantenous()) {
                effect.applyInstantenousEffect(null, null, entity, self.getAmplifier(), 1.0);
            }
        }
    }
}
