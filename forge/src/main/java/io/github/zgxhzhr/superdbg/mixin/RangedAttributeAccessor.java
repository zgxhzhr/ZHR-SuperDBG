package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 放宽 {@link RangedAttribute} 上限的访问器。
 * <p>
 * 1.20.1 中 maxValue 为 private final 且无 setRange 方法，
 * 编辑器需要把全部属性上限提升到 int 最大值，只能通过 Mixin
 * 生成可变字段访问器（@Mutable 允许写 final 字段）。
 * minValue 保持不动，下限保护（如最小生命 1）仍然有效。
 */
@Mixin(RangedAttribute.class)
public interface RangedAttributeAccessor {

    @Mutable
    @Accessor("maxValue")
    void superdbg$setMaxValue(double maxValue);
}
