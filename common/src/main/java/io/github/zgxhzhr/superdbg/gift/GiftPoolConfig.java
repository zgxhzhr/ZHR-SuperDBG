package io.github.zgxhzhr.superdbg.gift;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 单只女仆的回赠礼物池配置（TMA「随机礼物」羁绊能力的自定义池）。
 * <p>
 * 仅存储在女仆实体 persistentData 的 {@link #NBT_KEY} 键下，按女仆个体生效；
 * 不影响其他女仆，也不改动 TMA 全局物品标签池。无此配置（或配置被删除）时
 * 回礼完全走 TMA 默认池（{@code touhou_maid_affection:bond_random_gift_pool}
 * 标签 + 自动注册表候选）。
 * <p>
 * 抽取规则：每条目按 {@link GiftEntry#weight()} 加权随机选中一条，
 * 数量在 min/max 闭区间内均匀随机；条目可携带 NBT（如附魔书）。
 *
 * @param entries 礼物条目列表；空列表与无配置同效（回退 TMA 默认池）
 */
public record GiftPoolConfig(List<GiftEntry> entries) {

    /** 条目数上限（防恶意包，与掉落覆盖一致） */
    public static final int MAX_ENTRIES = 64;
    /** 单件礼物数量上限（投掷出的一个物品堆） */
    public static final int MAX_COUNT = 64;
    /** 单条权重上限 */
    public static final int MAX_WEIGHT = 1000;

    /** 实体 persistentData 内的存储键 */
    public static final String NBT_KEY = "SuperDbgGiftPool";

    public GiftPoolConfig {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * 单条礼物规则。
     *
     * @param item     物品注册名
     * @param minCount 最小数量（1-64，与 maxCount 构成闭区间均匀随机）
     * @param maxCount 最大数量（1-64）
     * @param weight   抽取权重（1-1000，越大越容易被选中）
     * @param tag      物品 NBT（如附魔书 StoredEnchantments），无 NBT 为 null
     */
    public record GiftEntry(ResourceLocation item, int minCount, int maxCount,
                            int weight, CompoundTag tag) {
    }

    // ==================== NBT ====================

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (GiftEntry e : entries) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", e.item().toString());
            entry.putInt("Min", e.minCount());
            entry.putInt("Max", e.maxCount());
            entry.putInt("Weight", e.weight());
            if (e.tag() != null) {
                entry.put("Tag", e.tag().copy());
            }
            list.add(entry);
        }
        tag.put("Entries", list);
        return tag;
    }

    /** 从 NBT 解析；无效条目跳过 */
    public static GiftPoolConfig fromNbt(CompoundTag tag) {
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        List<GiftEntry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("Id"));
            if (id == null) {
                continue;
            }
            int[] range = normalizeRange(entry.getInt("Min"), entry.getInt("Max"));
            entries.add(new GiftEntry(id, range[0], range[1],
                    clampWeight(entry.getInt("Weight")),
                    entry.contains("Tag", Tag.TAG_COMPOUND) ? entry.getCompound("Tag").copy() : null));
        }
        return new GiftPoolConfig(entries);
    }

    // ==================== 网络 ====================

    /** 写配置；null 写 false（表示该女仆无自定义池） */
    public static void write(FriendlyByteBuf buf, GiftPoolConfig config) {
        if (config == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(config.entries().size());
        for (GiftEntry e : config.entries()) {
            buf.writeResourceLocation(e.item());
            buf.writeVarInt(e.minCount());
            buf.writeVarInt(e.maxCount());
            buf.writeVarInt(e.weight());
            buf.writeNbt(e.tag());
        }
    }

    public static GiftPoolConfig read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        int count = buf.readVarInt();
        List<GiftEntry> entries = new ArrayList<>(Math.min(count, MAX_ENTRIES));
        for (int i = 0; i < count; i++) {
            ResourceLocation id = buf.readResourceLocation();
            int min = buf.readVarInt();
            int max = buf.readVarInt();
            int weight = buf.readVarInt();
            CompoundTag tag = buf.readNbt();
            if (entries.size() < MAX_ENTRIES) {
                int[] range = normalizeRange(min, max);
                entries.add(new GiftEntry(id, range[0], range[1], clampWeight(weight), tag));
            }
        }
        return new GiftPoolConfig(entries);
    }

    // ==================== 抽取（纯函数，便于测试） ====================

    /** 数量区间规整：至少 1，上限 {@link #MAX_COUNT}，min &gt; max 时交换 */
    public static int[] normalizeRange(int min, int max) {
        int lo = Math.max(1, Math.min(MAX_COUNT, min));
        int hi = Math.max(1, Math.min(MAX_COUNT, max));
        if (lo > hi) {
            return new int[]{hi, lo};
        }
        return new int[]{lo, hi};
    }

    /** 权重钳制到 [1, {@link #MAX_WEIGHT}]，0 或负数按 1（保证条目可被抽中） */
    public static int clampWeight(int weight) {
        if (weight < 1) {
            return 1;
        }
        return Math.min(MAX_WEIGHT, weight);
    }

    /**
     * 按权重在 [0, totalWeight) 的一次抽样值上定位条目下标。
     *
     * @param weights    各条目权重（调用方保证均为正数）
     * @param totalWeight 权重总和
     * @param rollValue  抽样值（0 &le; rollValue &lt; totalWeight）
     * @return 命中的条目下标；入参非法时返回 0
     */
    public static int pickIndex(int[] weights, int totalWeight, int rollValue) {
        if (weights == null || weights.length == 0 || totalWeight <= 0) {
            return 0;
        }
        int roll = Math.floorMod(rollValue, totalWeight);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += Math.max(0, weights[i]);
            if (roll < acc) {
                return i;
            }
        }
        return weights.length - 1;
    }

    /**
     * 按权重抽取一条礼物并构造物品堆；池为空返回 {@link ItemStack#EMPTY}。
     * 物品未注册（模组卸载）的条目在抽取前剔除，全部失效时同样返回空堆。
     */
    public ItemStack roll(RandomSource random) {
        List<GiftEntry> valid = new ArrayList<>(entries.size());
        for (GiftEntry e : entries) {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.get(e.item())
                    != net.minecraft.world.item.Items.AIR) {
                valid.add(e);
            }
        }
        if (valid.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int[] weights = new int[valid.size()];
        int total = 0;
        for (int i = 0; i < valid.size(); i++) {
            weights[i] = clampWeight(valid.get(i).weight());
            total += weights[i];
        }
        GiftEntry picked = valid.get(pickIndex(weights, total, random.nextInt(total)));
        int[] range = normalizeRange(picked.minCount(), picked.maxCount());
        int count = range[0];
        if (range[1] > range[0]) {
            count += random.nextInt(range[1] - range[0] + 1);
        }
        ItemStack stack = new ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(picked.item()), count);
        if (picked.tag() != null) {
            stack.setTag(picked.tag().copy());
        }
        return stack;
    }
}
