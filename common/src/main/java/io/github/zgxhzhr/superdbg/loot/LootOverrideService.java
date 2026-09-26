package io.github.zgxhzhr.superdbg.loot;

import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 掉落覆盖的查找与生成（服务端）。
 * <p>
 * 生效机制：{@code LivingEntityLootMixin} 注入 {@code dropFromLootTable}——
 * REPLACE 在 HEAD 取消原版战利品表掉落并改由本类生成，APPEND 在 TAIL 追加。
 * 装备掉落（dropEquipment）、经验（dropExperience）、特殊硬编码掉落
 * （dropCustomDeathLoot，如凋灵之星）均走原版路径不受影响。
 * <p>
 * 查找优先级：单实体 persistentData > 全类型 SavedData > 无覆盖（原版）。
 */
public final class LootOverrideService {

    private LootOverrideService() {
    }

    /**
     * 查找目标实体的生效掉落覆盖配置；无覆盖返回 null。
     * 单实体配置存在（即使规则列表为空）即屏蔽全类型配置，不做叠加。
     */
    public static LootConfig findConfig(LivingEntity target) {
        CompoundTag forgeData = persistentData(target);
        if (forgeData.contains(LootConfig.NBT_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return LootConfig.fromNbt(forgeData.getCompound(LootConfig.NBT_KEY));
        }
        if (target.getServer() == null) {
            return null;
        }
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
        return LootOverrideData.get(target.getServer()).get(typeId);
    }

    /**
     * 通过反射调用 Forge 扩展方法 {@code Entity.getPersistentData()}，
     * 兼容 multi-loader common 模块编译（common 不直接依赖 Forge 类）。
     * 与 RemovalGuard/TmaBondCompat 的持久化访问同款模式。
     */
    public static CompoundTag persistentData(LivingEntity entity) {
        try {
            return (CompoundTag) net.minecraft.world.entity.Entity.class
                    .getMethod("getPersistentData").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("无法获取实体 persistentData", e);
        }
    }

    /** 按配置 roll 并在实体位置生成掉落（概率判定 → 数量判定 → 生成 ItemEntity） */
    public static void spawnDrops(LivingEntity target, LootConfig config, int lootingLevel) {
        RandomSource random = target.getRandom();
        LootMath.RandomLike rand = new RandomLikeImpl(random);
        int rolled = 0;
        for (LootConfig.LootEntry entry : config.entries()) {
            float chance = LootMath.effectiveChance(entry.chance(), lootingLevel, entry.lootingChanceBonus());
            if (!LootMath.rollChance(rand, chance)) {
                continue;
            }
            int count = LootMath.rollCount(rand, entry.minCount(), entry.maxCount());
            if (count <= 0) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(entry.item());
            if (item == Items.AIR) {
                continue;
            }
            ItemStack drop = new ItemStack(item, count);
            // 透传规则携带的 NBT（如附魔书 StoredEnchantments）
            if (entry.tag() != null) {
                drop.setTag(entry.tag().copy());
            }
            target.spawnAtLocation(drop);
            rolled++;
        }
        Constants.LOG.debug("[SuperDbg] 掉落覆盖生效: entity={}, mode={}, 规则数={}, 实际掉落条目数={}, 抢夺等级={}",
                target, config.mode(), config.entries().size(), rolled, lootingLevel);
    }

    /** RandomSource → LootMath.RandomLike 适配 */
    private record RandomLikeImpl(RandomSource random) implements LootMath.RandomLike {
        @Override
        public float nextFloat() {
            return random.nextFloat();
        }

        @Override
        public int nextInt(int bound) {
            return bound <= 0 ? 0 : random.nextInt(bound);
        }
    }
}
