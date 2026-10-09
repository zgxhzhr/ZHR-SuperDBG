package io.github.zgxhzhr.superdbg.entity;

import io.github.zgxhzhr.superdbg.attribute.AttributeLock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家属性基础值的持久化覆盖表。
 * <p>
 * 背景：部分模组（如 extra_enchantments）在 {@code Player.tick()} 中每 tick
 * 按装备附魔重算属性基础值（没有对应附魔时直接写回默认值），会立刻抹掉
 * 实体编辑器对玩家自身属性的修改。因此编辑器对玩家改的属性存入本覆盖表，
 * 由 {@code PlayerTickEvent.END} 在这些模组重算之后重新强制写回。
 * <p>
 * 存储位置：玩家 Forge 持久化 NBT 的 {@code PlayerPersisted} 子标签下
 * （死亡重生也保留），结构：
 * <pre>
 * PlayerPersisted.SuperDbgAttrOverrides = { "minecraft:generic.max_health": 100.0D, ... }
 * </pre>
 * 用户把某项改回该属性的原版默认值时，对应覆盖条目移除，该属性重新交回
 * 其它模组/原版逻辑管理。
 */
public final class PlayerAttributeOverrides {

    /** PlayerPersisted 下的覆盖表标签名 */
    private static final String TAG_PERSISTED = "PlayerPersisted";
    private static final String TAG_OVERRIDES = "SuperDbgAttrOverrides";
    /** 旧版标签名：读取时回退，保存时自动迁移 */
    private static final String LEGACY_TAG_OVERRIDES = "TiaoshiAttrOverrides";

    private static final double EPSILON = 1.0E-9D;

    private PlayerAttributeOverrides() {
    }

    /**
     * 每 tick 调用：按覆盖表给玩家属性加锁，并对已解除覆盖的属性解锁。
     * <p>
     * 真正的值保护由 {@link AttributeLock} 在 {@code setBaseValue} 入口
     * 拦截完成（任何模组的重置写入都会被改写为锁定值）；本方法负责让锁
     * 指向当前属性实例（死亡重生/维度切换后实例可能重建）并对齐当前值。
     */
    public static void enforce(Player player) {
        CompoundTag overrides = getOverrideTag(player);
        Map<ResourceLocation, Double> wanted = new HashMap<>();
        for (String key : overrides.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(key);
            if (rl != null) {
                wanted.put(rl, overrides.getDouble(key));
            }
        }

        for (AttributeInstance instance : player.getAttributes().getSyncableAttributes()) {
            Attribute attribute = instance.getAttribute();
            ResourceLocation rl = ForgeRegistries.ATTRIBUTES.getKey(attribute);
            if (rl != null && wanted.containsKey(rl)) {
                double target = attribute.sanitizeValue(wanted.get(rl));
                AttributeLock.lock(instance, target);
                if (Math.abs(instance.getBaseValue() - target) > EPSILON) {
                    instance.setBaseValue(target);
                }
            } else {
                // 不在覆盖表中的属性必须主动解锁，把管理权交还原版/其它模组
                AttributeLock.unlock(instance);
            }
        }

        // 提高/降低最大生命后，把当前血量钳到新上限内（提高时不主动回血）
        // 调试器写入：绕过守卫并同步血量基准（否则下一 tick 会被当作非法降血回滚）
        AttributeInstance maxHealth = player.getAttributes().getInstance(Attributes.MAX_HEALTH);
        if (maxHealth != null && player.getHealth() > maxHealth.getValue()) {
            final float clampedToMax = (float) maxHealth.getValue();
            RemovalGuard.runWithoutGuard(() -> player.setHealth(clampedToMax));
        }
    }

    /**
     * 根据提交值与打开时快照的差异更新覆盖表（仅玩家目标调用）。
     * <ul>
     *   <li>被用户改动的属性：写入覆盖值（若恰好等于原版默认值则移除覆盖）</li>
     *   <li>未改动的属性：保持原样（不覆盖其它模组的动态调整）</li>
     * </ul>
     *
     * @param submitted 客户端提交的全部属性值
     * @param snapshot  打开菜单时服务端生成的属性快照
     */
    public static void updateFromSubmit(Player player,
                                        Map<ResourceLocation, Double> submitted,
                                        Map<ResourceLocation, Double> snapshot) {
        CompoundTag overrides = getOverrideTag(player);
        boolean changed = false;
        for (Map.Entry<ResourceLocation, Double> e : submitted.entrySet()) {
            ResourceLocation rl = e.getKey();
            double value = e.getValue() == null ? 0.0D : e.getValue();
            Double original = snapshot.get(rl);
            if (original == null || Math.abs(original - value) <= EPSILON) {
                continue;
            }
            Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(rl);
            if (attribute == null) {
                continue;
            }
            String key = rl.toString();
            if (Math.abs(value - attribute.getDefaultValue()) <= EPSILON) {
                // 回到原版默认值：解除覆盖，把该属性交还给原版/其它模组
                if (overrides.contains(key)) {
                    overrides.remove(key);
                    changed = true;
                }
            } else {
                overrides.putDouble(key, value);
                changed = true;
            }
        }
        if (changed) {
            save(player, overrides);
        }
    }

    /** 读取覆盖表（拷贝，避免外部直接改 NBT） */
    public static Map<ResourceLocation, Double> snapshot(Player player) {
        Map<ResourceLocation, Double> map = new LinkedHashMap<>();
        CompoundTag overrides = getOverrideTag(player);
        for (String key : overrides.getAllKeys()) {
            ResourceLocation rl = ResourceLocation.tryParse(key);
            if (rl != null) {
                map.put(rl, overrides.getDouble(key));
            }
        }
        return map;
    }

    /** 死亡重生时把旧玩家的覆盖表复制到新玩家实体 */
    public static void cloneData(Player oldPlayer, Player newPlayer) {
        CompoundTag oldTag = getOverrideTag(oldPlayer);
        if (!oldTag.isEmpty()) {
            save(newPlayer, oldTag.copy());
        }
    }

    private static CompoundTag getOverrideTag(Player player) {
        CompoundTag root = player.getPersistentData();
        CompoundTag persistent = root.getCompound(TAG_PERSISTED);
        if (persistent.contains(TAG_OVERRIDES)) {
            return persistent.getCompound(TAG_OVERRIDES);
        }
        // 旧版存档兼容：读到旧标签时迁移到新名
        if (persistent.contains(LEGACY_TAG_OVERRIDES)) {
            CompoundTag legacy = persistent.getCompound(LEGACY_TAG_OVERRIDES);
            persistent.remove(LEGACY_TAG_OVERRIDES);
            persistent.put(TAG_OVERRIDES, legacy);
            root.put(TAG_PERSISTED, persistent);
            return legacy;
        }
        return persistent.getCompound(TAG_OVERRIDES);
    }

    private static void save(Player player, CompoundTag overrides) {
        CompoundTag root = player.getPersistentData();
        CompoundTag persistent = root.getCompound(TAG_PERSISTED);
        persistent.put(TAG_OVERRIDES, overrides);
        root.put(TAG_PERSISTED, persistent);
    }
}
