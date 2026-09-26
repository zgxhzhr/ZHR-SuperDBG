package io.github.zgxhzhr.superdbg.platform.services;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.util.Iterator;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * 遍历当前平台<b>全部已注册</b>的属性（含原版、Forge 内置如 BLOCK_REACH/ENTITY_REACH、以及其它模组注册的属性）。
     * <p>
     * common 层不能直接访问 Forge 注册表，因此通过此方法抽象。
     *
     * @see #attributeId(Attribute)
     */
    Iterator<Attribute> allAttributes();

    /**
     * 取属性注册名。与 {@link #allAttributes()} 返回的实例配对使用。
     * 对未注册的属性返回 null。
     */
    ResourceLocation attributeId(Attribute attribute);

    /**
     * 按注册名反查属性。对未注册的 id 返回 null。
     */
    Attribute attributeById(ResourceLocation id);

    /**
     * 计算击杀者的抢夺等级（掉落覆盖的概率加成用）。
     * 默认实现返回 0（不生效抢夺加成）；Forge 侧覆写为
     * {@code ForgeEventFactory.getLootingLevel}，内部含原版抢夺计算并兼容修改抢夺等级的模组。
     */
    default int getLootingLevel(net.minecraft.world.entity.LivingEntity target,
                                net.minecraft.world.damagesource.DamageSource source) {
        return 0;
    }
}
