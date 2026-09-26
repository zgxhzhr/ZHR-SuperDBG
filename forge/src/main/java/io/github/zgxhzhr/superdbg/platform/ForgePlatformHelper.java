package io.github.zgxhzhr.superdbg.platform;

import io.github.zgxhzhr.superdbg.platform.services.IPlatformHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Iterator;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Iterator<Attribute> allAttributes() {
        // Forge 注册表天然包含原版、Forge 内置（BLOCK_REACH/ENTITY_REACH/STEP_HEIGHT_ADDITION 等）
        // 以及其它模组（如 TLM）注册的属性。
        return ForgeRegistries.ATTRIBUTES.getValues().iterator();
    }

    @Override
    public ResourceLocation attributeId(Attribute attribute) {
        return ForgeRegistries.ATTRIBUTES.getKey(attribute);
    }

    @Override
    public Attribute attributeById(ResourceLocation id) {
        return ForgeRegistries.ATTRIBUTES.getValue(id);
    }

    @Override
    public int getLootingLevel(net.minecraft.world.entity.LivingEntity target,
                               net.minecraft.world.damagesource.DamageSource source) {
        // 与 Forge patch 进 dropAllDeathLoot 的调用相同：内部算原版抢夺并触发 LootingLevelEvent
        return net.minecraftforge.common.ForgeHooks.getLootingLevel(target, source.getEntity(), source);
    }
}
