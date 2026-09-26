package io.github.zgxhzhr.superdbg.loot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 原版战利品表的只读快照，用于在实体编辑器掉落页展示"该生物原本会掉什么"。
 * <p>
 * 按战利品池（LootPool）分组：每个池子独立投掷若干次（rolls），
 * 每次从该池所有条目中按权重抽一个。单个物品在一次投掷中被抽中的概率 =
 * weight / pool 总权重。若该条目或池子挂有 LootItemCondition（如抢夺加成、
 * 被玩家击杀等），标记 {@code conditional}，实际掉落概率会受条件影响。
 *
 * @param pools 解析出的战利品池列表（空列表表示无战利品表或解析失败）
 */
public record VanillaLootSnapshot(List<PoolView> pools) {

    public VanillaLootSnapshot {
        pools = List.copyOf(pools);
    }

    /** 单个战利品池的可视化投影。 */
    public record PoolView(float rollsMin, float rollsMax, List<ItemView> items) {
        public PoolView {
            items = List.copyOf(items);
        }
    }

    /**
     * 单个物品掉落条目（已展开复合条目、已折算实际概率）。
     *
     * @param item               物品注册名
     * @param minCount           单次掉落数量下限（SetItemCountFunction；固定数量时 min==max；无数量函数时为 1）
     * @param maxCount           单次掉落数量上限
     * @param chancePercent      该物品每次死亡的实际掉落概率（百分比），
     *                           已综合池内权重与 random_chance 条件；复合条目内按顺序独立条件近似
     * @param lootingBonusPercent 每级抢夺追加的概率（百分比，来自 random_chance_with_looting）
     * @param conditional        是否还挂有无法静态折算的条件（如 killed_by_player），实际概率可能再受影响
     */
    public record ItemView(ResourceLocation item, int minCount, int maxCount,
                           float chancePercent, float lootingBonusPercent, boolean conditional) {
    }

    /** 序列化：pools.size → 每个 pool(rollsMin, rollsMax, items.size → 每个 item) */
    public static void write(FriendlyByteBuf buf, VanillaLootSnapshot snapshot) {
        if (snapshot == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(snapshot.pools().size());
        for (PoolView pool : snapshot.pools()) {
            buf.writeFloat(pool.rollsMin());
            buf.writeFloat(pool.rollsMax());
            buf.writeVarInt(pool.items().size());
            for (ItemView item : pool.items()) {
                buf.writeResourceLocation(item.item());
                buf.writeVarInt(item.minCount());
                buf.writeVarInt(item.maxCount());
                buf.writeFloat(item.chancePercent());
                buf.writeFloat(item.lootingBonusPercent());
                buf.writeBoolean(item.conditional());
            }
        }
    }

    public static VanillaLootSnapshot read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        int poolCount = buf.readVarInt();
        List<PoolView> pools = new ArrayList<>(poolCount);
        for (int i = 0; i < poolCount; i++) {
            float rollsMin = buf.readFloat();
            float rollsMax = buf.readFloat();
            int itemCount = buf.readVarInt();
            List<ItemView> items = new ArrayList<>(itemCount);
            for (int j = 0; j < itemCount; j++) {
                items.add(new ItemView(
                        buf.readResourceLocation(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readFloat(),
                        buf.readFloat(),
                        buf.readBoolean()));
            }
            pools.add(new PoolView(rollsMin, rollsMax, items));
        }
        return new VanillaLootSnapshot(pools);
    }
}
