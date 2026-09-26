package io.github.zgxhzhr.superdbg.loot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份掉落覆盖配置：模式 + 规则列表。
 * <p>
 * 存储位置二选一（存在即启用，删除即停用）：
 * <ul>
 *   <li>单实体：实体 persistentData 的 {@code SuperDbgLootOverride} 键</li>
 *   <li>全类型：存档 SavedData（{@link LootOverrideData}）按实体类型注册名索引</li>
 * </ul>
 * 优先级：单实体 > 全类型 > 原版战利品表。
 */
public record LootConfig(Mode mode, List<LootEntry> entries) {

    public enum Mode {
        /** 替换：原版战利品表不掉，只掉本配置（装备/经验/特殊硬编码掉落不受影响） */
        REPLACE,
        /** 追加：原版战利品表照掉，额外追加本配置 */
        APPEND;

        public static Mode byName(String name) {
            for (Mode m : values()) {
                if (m.name().equals(name)) {
                    return m;
                }
            }
            return REPLACE;
        }
    }

    /**
     * 单条掉落规则。
     *
     * @param item               物品注册名
     * @param minCount           最小数量（0-999，与 maxCount 构成闭区间均匀随机）
     * @param maxCount           最大数量（0-999）
     * @param chance             基础概率（百分比 0-100，支持一位小数）
     * @param lootingChanceBonus 每级抢夺追加的概率（百分比 0-100）
     * @param tag                物品 NBT（如附魔书 StoredEnchantments），无 NBT 为 null
     */
    public record LootEntry(ResourceLocation item, int minCount, int maxCount,
                            float chance, float lootingChanceBonus, CompoundTag tag) {
    }

    /** 实体 persistentData / SavedData 内的存储键 */
    public static final String NBT_KEY = "SuperDbgLootOverride";

    // ==================== NBT ====================

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Mode", mode.name());
        ListTag list = new ListTag();
        for (LootEntry e : entries) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", e.item().toString());
            entry.putInt("Min", e.minCount());
            entry.putInt("Max", e.maxCount());
            entry.putFloat("Chance", e.chance());
            entry.putFloat("LootChance", e.lootingChanceBonus());
            if (e.tag() != null) {
                entry.put("Tag", e.tag().copy());
            }
            list.add(entry);
        }
        tag.put("Entries", list);
        return tag;
    }

    /** 从 NBT 解析；无效条目跳过，全部无效时返回空列表配置（调用方自行决定是否视为无配置） */
    public static LootConfig fromNbt(CompoundTag tag) {
        Mode mode = Mode.byName(tag.getString("Mode"));
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        List<LootEntry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("Id"));
            if (id == null) {
                continue;
            }
            int[] range = LootMath.normalizeRange(entry.getInt("Min"), entry.getInt("Max"));
            CompoundTag entryTag = entry.contains("Tag", Tag.TAG_COMPOUND)
                    ? entry.getCompound("Tag").copy() : null;
            entries.add(new LootEntry(id, range[0], range[1],
                    LootMath.clampChance(entry.getFloat("Chance")),
                    LootMath.clampChance(entry.getFloat("LootChance")), entryTag));
        }
        return new LootConfig(mode, entries);
    }

    // ==================== 网络 ====================

    /** 写配置；null 写 false（表示该作用域无覆盖/未启用） */
    public static void write(FriendlyByteBuf buf, LootConfig config) {
        if (config == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeEnum(config.mode());
        buf.writeVarInt(config.entries().size());
        for (LootEntry e : config.entries()) {
            buf.writeResourceLocation(e.item());
            buf.writeVarInt(e.minCount());
            buf.writeVarInt(e.maxCount());
            buf.writeFloat(e.chance());
            buf.writeFloat(e.lootingChanceBonus());
            buf.writeNbt(e.tag());
        }
    }

    public static LootConfig read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        Mode mode = buf.readEnum(Mode.class);
        int count = buf.readVarInt();
        List<LootEntry> entries = new ArrayList<>(Math.min(count, LootMath.MAX_ENTRIES));
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            int min = buf.readVarInt();
            int max = buf.readVarInt();
            float chance = buf.readFloat();
            float lootChance = buf.readFloat();
            CompoundTag tag = buf.readNbt();
            if (entries.size() < LootMath.MAX_ENTRIES) {
                int[] range = LootMath.normalizeRange(min, max);
                entries.add(new LootEntry(id, range[0], range[1],
                        LootMath.clampChance(chance), LootMath.clampChance(lootChance), tag));
            }
        }
        return new LootConfig(mode, entries);
    }
}
