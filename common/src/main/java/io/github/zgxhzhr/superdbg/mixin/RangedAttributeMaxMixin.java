package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 强制所有 RangedAttribute 的上限为 int 最大值，并确保 sanitizeValue 将值钳制到
 * {@code [minValue, Integer.MAX_VALUE]} 范围内。
 * <p>
 * 背景：
 * <ul>
 *   <li>原版 {@code MAX_HEALTH} 的 {@code maxValue} 字段为 1024，其他属性也有各自的硬编码上限</li>
 *   <li>{@code RangedAttribute.sanitizeValue(double)} 直接读取 {@code maxValue} 字段进行钳制，
 *       而非调用 {@code getMaxValue()}，因此仅覆盖 {@code getMaxValue()} 无法阻止写入被钳回</li>
 *   <li>ApothicAttributes、AttributeFix 等模组可能在运行时重写字段上限</li>
 * </ul>
 * 本 Mixin 同时覆写 {@code getMaxValue()} 与 {@code sanitizeValue()}：
 * <ul>
 *   <li>{@code getMaxValue()}：从读取入口确保上限始终为 int 最大值</li>
 *   <li>{@code sanitizeValue()}：从钳制入口确保下限为 minValue、上限为 Integer.MAX_VALUE，
 *       防止属性叠加后溢出 int 范围导致崩溃</li>
 * </ul>
 */
@Mixin(value = RangedAttribute.class, priority = 2000)
public abstract class RangedAttributeMaxMixin {

    /**
     * 上限：Integer.MAX_VALUE - 1（≈ 21 亿）。
     * 用户明确要求编辑器能把 MAX_HEALTH 设到 int 最大值，这里放开。
     * <p>
     * 为什么现在安全了（之前放开会触发无限涨血正反馈）：
     * 无正反馈 = superdbg$setHealthDirect 恢复 clamp 到 getMaxHealth() +
     * 属性上限放开，health 永远 ≤ maxHealth，模组"按百分比回血/加血"逻辑
     * 不会导致 health 突破 maxHealth 再反过来推高 maxHealth 形成死循环。
     * 即使模组通过 AttributeModifier 叠加 maxHealth，也只是属性值变大，
     * health 始终被 clamp 压在新 maxHealth 内——单向、无循环。
     */
    private static final double SAFE_MAX = Integer.MAX_VALUE - 1.0;

    @Inject(method = "getMaxValue()D", at = @At("HEAD"), cancellable = true)
    private void superdbg$forceMaxValue(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(SAFE_MAX);
    }

    /**
     * 覆写 sanitizeValue：钳制到 {@code [minValue, SAFE_MAX]}。
     * 放开原版硬编码上限的同时，防止属性叠加后溢出 int 范围。
     * NaN 按 minValue 处理——NaN 与任何值比较均为 false，会原样穿透写入属性。
     */
    @Inject(method = "sanitizeValue(D)D", at = @At("HEAD"), cancellable = true)
    private void superdbg$sanitizeClamp(double value, CallbackInfoReturnable<Double> cir) {
        RangedAttribute self = (RangedAttribute) (Object) this;
        double min = self.getMinValue();
        if (Double.isNaN(value) || value < min) {
            cir.setReturnValue(min);
        } else if (value > SAFE_MAX) {
            cir.setReturnValue(SAFE_MAX);
        } else {
            cir.setReturnValue(value);
        }
    }
}
