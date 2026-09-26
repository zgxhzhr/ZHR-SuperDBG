package io.github.zgxhzhr.superdbg.platform;

import io.github.zgxhzhr.superdbg.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.util.Iterator;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Iterator<Attribute> allAttributes() {
        // Fabric 没有像 Forge 那样为 Attribute 维护独立注册表，
        // 直接走原版 BuiltInRegistries.ATTRIBUTE（含其它模组注册到原版注册表的属性）。
        return BuiltInRegistries.ATTRIBUTE.iterator();
    }

    @Override
    public ResourceLocation attributeId(Attribute attribute) {
        return BuiltInRegistries.ATTRIBUTE.getKey(attribute);
    }

    @Override
    public Attribute attributeById(ResourceLocation id) {
        return BuiltInRegistries.ATTRIBUTE.get(id);
    }
}
