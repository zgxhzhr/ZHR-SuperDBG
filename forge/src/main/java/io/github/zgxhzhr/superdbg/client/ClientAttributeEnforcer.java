package io.github.zgxhzhr.superdbg.client;

import io.github.zgxhzhr.superdbg.attribute.AttributeLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 客户端本地玩家属性基础值缓存与锁定。
 * <p>
 * 部分模组（如 extra_enchantments）在 {@code Player.tick()} 中每 tick 重算
 * 玩家属性基础值，客户端本地玩家也会被重置成默认值。本类以服务端最近一次
 * 同步给本地玩家的属性基础值为真值（由属性同步包喂养），通过
 * {@link AttributeLock} 在 {@code setBaseValue} 入口拦截一切重置写入；
 * {@code LocalPlayerTickMixin} 每 tick 重新注册锁，以应对属性实例重建。
 */
public final class ClientAttributeEnforcer {

    private static final double EPSILON = 1.0E-9D;
    private static final Map<ResourceLocation, Double> SERVER_BASES = new HashMap<>();
    /** 本类当前持有锁的实例，断线时统一释放 */
    private static final Set<AttributeInstance> LOCKED = new HashSet<>();

    private ClientAttributeEnforcer() {
    }

    /**
     * 收到属性同步包时更新单条缓存（由 Mixin 逐条调用，仅本地玩家的包生效）。
     */
    public static void acceptSyncedBase(int entityId, Attribute attribute, double base) {
        LocalPlayer self = Minecraft.getInstance().player;
        if (self == null || entityId != self.getId() || attribute == null) {
            return;
        }
        ResourceLocation rl = ForgeRegistries.ATTRIBUTES.getKey(attribute);
        if (rl != null) {
            SERVER_BASES.put(rl, base);
        }
    }

    /**
     * 本地玩家 tick 末尾：按服务端真值注册/对齐锁，并释放不再覆盖的属性。
     */
    public static void enforce() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Set<AttributeInstance> stillLocked = new HashSet<>();
        for (AttributeInstance instance : player.getAttributes().getSyncableAttributes()) {
            Attribute attribute = instance.getAttribute();
            ResourceLocation rl = ForgeRegistries.ATTRIBUTES.getKey(attribute);
            Double expected = rl == null ? null : SERVER_BASES.get(rl);
            if (expected != null) {
                double target = attribute.sanitizeValue(expected);
                AttributeLock.lock(instance, target);
                stillLocked.add(instance);
                if (Math.abs(instance.getBaseValue() - target) > EPSILON) {
                    instance.setBaseValue(target);
                }
            }
        }
        // 释放服务端不再覆盖（或本地不再缓存）的属性锁
        for (AttributeInstance instance : LOCKED) {
            if (!stillLocked.contains(instance)) {
                AttributeLock.unlock(instance);
            }
        }
        LOCKED.clear();
        LOCKED.addAll(stillLocked);

        AttributeInstance maxHealth = player.getAttributes().getInstance(Attributes.MAX_HEALTH);
        if (maxHealth != null && player.getHealth() > maxHealth.getValue()) {
            player.setHealth((float) maxHealth.getValue());
        }
    }

    /** 退出世界时清空缓存并释放全部锁，避免换存档串值 */
    public static void reset() {
        for (AttributeInstance instance : LOCKED) {
            AttributeLock.unlock(instance);
        }
        LOCKED.clear();
        SERVER_BASES.clear();
    }
}
