package io.github.zgxhzhr.superdbg.jade;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade 实体信息 Provider：在实体 tooltip 中显示"防移除"状态。
 * <p>
 * 参考 L2Hostility 的 {@code TraitInfo} 实现方式，
 * 通过 {@code IWailaPlugin.registerEntityComponent} 注册，
 * 而非 Mixin 拦截，保证不影响 Jade 原有信息显示。
 */
public class RemovalGuardProvider implements IEntityComponentProvider {

    public static final ResourceLocation ID =
            new ResourceLocation("superdbg", "removal_guard");

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        Entity entity = accessor.getEntity();
        if (entity instanceof LivingEntity living && RemovalGuard.has(living)) {
            tooltip.add(Component.literal("§6§l防移除"));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }
}
