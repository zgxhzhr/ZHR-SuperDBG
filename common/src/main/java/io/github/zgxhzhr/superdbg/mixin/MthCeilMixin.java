package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 {@link Mth#ceil(float)} 对超大 float 值的溢出问题。
 * <p>
 * 原版实现：
 * <pre>{@code
 * public static int ceil(float value) {
 *     int i = (int) value;
 *     return value > (float) i ? i + 1 : i;
 * }
 * }</pre>
 * 当 {@code value > Integer.MAX_VALUE}（如 2147483648.0f）时：
 * <ul>
 *   <li>{@code (int) value} 被 Java 规范钳制为 {@code Integer.MAX_VALUE}</li>
 *   <li>{@code value > (float) i} 为 true</li>
 *   <li>返回 {@code i + 1} = {@code Integer.MAX_VALUE + 1} = {@code Integer.MIN_VALUE}（溢出！）</li>
 * </ul>
 * 这导致 Jade 的 {@code HealthElement} 在血量超过 20 亿时计算出负数尺寸，
 * 整个 tooltip 渲染失败、生物信息完全不显示。
 */
@Mixin(Mth.class)
public abstract class MthCeilMixin {

    @Inject(method = "ceil(F)I", at = @At("HEAD"), cancellable = true)
    private static void superdbg$ceilNoOverflow(float value, CallbackInfoReturnable<Integer> cir) {
        if (value >= (float) Integer.MAX_VALUE) {
            cir.setReturnValue(Integer.MAX_VALUE);
        } else if (value <= (float) Integer.MIN_VALUE) {
            cir.setReturnValue(Integer.MIN_VALUE);
        }
    }
}
