package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.attribute.AttributeLock;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 属性基础值写入拦截。
 * <p>
 * 对已被 {@link AttributeLock} 锁定的属性实例，把一切对
 * {@code setBaseValue} 的调用入参强制改写为锁定值。这样其它模组
 * （如 extra_enchantments 在玩家 tick 中重算属性）无论在什么时机写入
 * 默认值，都无法覆盖实体编辑器设定的玩家属性。
 */
@Mixin(value = AttributeInstance.class, priority = 2000)
public abstract class AttributeInstanceMixin {

    @ModifyVariable(method = "setBaseValue(D)V", at = @At("HEAD"), argsOnly = true)
    private double superdbg$coerceLockedBase(double incoming) {
        return AttributeLock.coerceIfLocked((AttributeInstance) (Object) this, incoming);
    }
}
