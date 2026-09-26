package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 访问 AttributeMap 内部的属性 Map。
 * <p>
 * 原版/Forge 的 AttributeMap 有一个 {@code attributes} 字段
 * （{@code Map<Attribute, AttributeInstance>}），包含该实体持有的全部属性实例。
 * 仅遍历注册表 + hasAttribute 会遗漏动态添加或非标准注册的属性
 * （如神秘遗物的诅咒属性、其他模组运行时注入的属性），因此需要直接访问内部 Map。
 */
@Mixin(AttributeMap.class)
public interface AttributeMapAccessor {

    @Accessor("attributes")
    Map<Attribute, AttributeInstance> superdbg$getAttributes();
}
