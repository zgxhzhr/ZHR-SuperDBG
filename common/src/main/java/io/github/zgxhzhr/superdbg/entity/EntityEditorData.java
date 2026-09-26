package io.github.zgxhzhr.superdbg.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体编辑器的数据快照。
 * <p>
 * 打开菜单时由服务端生成（客户端读不到未同步的属性），
 * 经 extraData 传给客户端菜单；提交时客户端回传全量数据。
 *
 * @param entityId       目标实体 id（-1 表示编辑者自己）
 * @param removable      是否允许移除该实体（女仆等受保护实体为 false）
 * @param health         打开时的当前血量
 * @param attrs          属性基础值列表
 * @param effects        药水效果列表
 * @param traits         L2Hostility 词条列表（全量已注册词条，等级 0 表示无该词条）；
 *                       未安装 L2Hostility 时为空列表
 * @param hostilityLevel L2Hostility 难度等级（决定敌人的生命缩放倍率）；
 *                       -1 表示目标无词条能力或未安装 L2Hostility，不参与写回
 * @param removalGuard   是否开启移除守卫（指令/其他模组清除失效，正常伤害仍可杀死）
 * @param curiosSlots    Curios 饰品栏列表（每个槽位类型及其当前数量）；
 *                       未安装 Curios 时为空列表
 * @param bond           车万女仆好感度/羁绊调试快照；目标非女仆时为 null
 * @param trades         村民类实体（AbstractVillager）的交易列表快照；
 *                       非村民类实体为 null（无交易页签）
 * @param entityLoot     本实体生效的掉落覆盖配置；无覆盖为 null
 * @param typeLoot       该实体类型全体生效的掉落覆盖配置；无覆盖为 null
 * @param vanillaLoot    该实体原版战利品表的只读快照（用于界面展示参考）；
 *                       无战利品表或解析失败时 pools 为空列表
 * @param giftPool       女仆个体的 TMA 回赠礼物池配置；非女仆或无配置为 null
 */
public record EntityEditorData(int entityId,
                               boolean removable,
                               float health,
                               List<AttrEntry> attrs,
                               List<EffectEntry> effects,
                               List<TraitEntry> traits,
                               int hostilityLevel,
                               boolean removalGuard,
                               List<CuriosSlotEntry> curiosSlots,
                               BondSnapshot bond,
                               List<TradeEntry> trades,
                               io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot,
                               io.github.zgxhzhr.superdbg.loot.LootConfig typeLoot,
                               io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot vanillaLoot,
                               io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool) {

    /** 单个属性条目：注册名 + 基础值 */
    public record AttrEntry(ResourceLocation id, double baseValue) {
    }

    /** 单个效果条目：注册名 + 等级 + 时长（-1 永久） */
    public record EffectEntry(ResourceLocation id, int amplifier, int duration) {
    }

    /** 单个 L2Hostility 词条条目：注册名 + 等级（0 表示未拥有） */
    public record TraitEntry(ResourceLocation id, int level) {
    }

    /** 单个 Curios 饰品栏条目：槽位类型标识 + 数量 */
    public record CuriosSlotEntry(String identifier, int amount) {
    }

    /**
     * 单个羁绊能力开关条目：能力 id + 是否已解锁。
     */
    public record BondAbilityEntry(String id, boolean unlocked) {
    }

    /**
     * 女仆好感度/羁绊调试快照。
     *
     * @param present       目标是否为车万女仆（false 时其余字段无意义）
     * @param tmaLoaded     TMA 羁绊附属是否已加载（false 时仅有 TLM 好感度点数可编辑）
     * @param favorability  TLM 好感度点数（0-384）
     * @param bondLevel     TMA 羁绊等级缓存（TMA 同步时会按好感度等级覆写）
     * @param bondUnlocked  TMA 羁绊是否已解锁（等级 ≥ 3）
     * @param giftQueue     TMA 待发随机礼物数量
     * @param abilities     TMA 羁绊能力开关列表
     */
    public record BondSnapshot(boolean present,
                               boolean tmaLoaded,
                               int favorability,
                               int bondLevel,
                               boolean bondUnlocked,
                               int giftQueue,
                               List<BondAbilityEntry> abilities) {
    }

    /**
     * 单条村民交易条目（MerchantOffer 的可编辑投影）。
     *
     * @param costA     第一买入物注册名（左侧槽，必填）
     * @param countA    第一买入物数量
     * @param costATag  第一买入物 NBT（附魔/药水等），无 NBT 为 null
     * @param hasCostB  是否存在第二买入物（中间槽）
     * @param costB     第二买入物注册名（{@link #hasCostB} 为 false 时无意义）
     * @param countB    第二买入物数量
     * @param costBTag  第二买入物 NBT；无 B 或无 NBT 为 null
     * @param result    卖出物注册名（右侧槽，必填）
     * @param countR    卖出物数量
     * @param resultTag 卖出物 NBT（如附魔书的 StoredEnchantments），无 NBT 为 null
     * @param maxUses   最大交易次数（补货后重置）
     * @param xp        成交后村民获得的经验
     */
    public record TradeEntry(ResourceLocation costA, int countA, net.minecraft.nbt.CompoundTag costATag,
                             boolean hasCostB, ResourceLocation costB, int countB, net.minecraft.nbt.CompoundTag costBTag,
                             ResourceLocation result, int countR, net.minecraft.nbt.CompoundTag resultTag,
                             int maxUses, int xp) {
    }

    /**
     * 序列化快照（不含 entityId，entityId 由调用方单独写）。
     * <p>
     * 顺序：removable → 当前血量 → 属性列表 → 效果列表 → 词条列表 → 难度等级 → 防移除
     * → Curios 饰品栏列表 → 女仆羁绊快照 → 交易列表 → 本实体掉落覆盖 → 全类型掉落覆盖
     * → 原版战利品表快照 → 女仆回礼池。
     */
    public static void writeSnapshot(FriendlyByteBuf buf,
                                     boolean removable,
                                     float health,
                                     List<AttrEntry> attrs,
                                     List<EffectEntry> effects,
                                     List<TraitEntry> traits,
                                     int hostilityLevel,
                                     boolean removalGuard,
                                     List<CuriosSlotEntry> curiosSlots,
                                     BondSnapshot bond,
                                     List<TradeEntry> trades,
                                     io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot,
                                     io.github.zgxhzhr.superdbg.loot.LootConfig typeLoot,
                                     io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot vanillaLoot,
                                     io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool) {
        buf.writeBoolean(removable);
        buf.writeFloat(health);

        buf.writeVarInt(attrs.size());
        for (AttrEntry entry : attrs) {
            buf.writeResourceLocation(entry.id());
            buf.writeDouble(entry.baseValue());
        }
        buf.writeVarInt(effects.size());
        for (EffectEntry entry : effects) {
            buf.writeResourceLocation(entry.id());
            buf.writeVarInt(entry.amplifier());
            buf.writeVarInt(entry.duration());
        }
        buf.writeVarInt(traits.size());
        for (TraitEntry entry : traits) {
            buf.writeResourceLocation(entry.id());
            buf.writeVarInt(entry.level());
        }
        buf.writeVarInt(hostilityLevel);
        buf.writeBoolean(removalGuard);
        buf.writeVarInt(curiosSlots.size());
        for (CuriosSlotEntry entry : curiosSlots) {
            buf.writeUtf(entry.identifier());
            buf.writeVarInt(entry.amount());
        }
        writeBond(buf, bond);
        writeTrades(buf, trades);
        io.github.zgxhzhr.superdbg.loot.LootConfig.write(buf, entityLoot);
        io.github.zgxhzhr.superdbg.loot.LootConfig.write(buf, typeLoot);
        io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot.write(buf, vanillaLoot);
        io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.write(buf, giftPool);
    }

    /** 交易列表：null（非村民）写 false；空列表也是合法快照（村民可以没有交易） */
    private static void writeTrades(FriendlyByteBuf buf, List<TradeEntry> trades) {
        if (trades == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(trades.size());
        for (TradeEntry t : trades) {
            buf.writeResourceLocation(t.costA());
            buf.writeVarInt(t.countA());
            buf.writeNbt(t.costATag());
            buf.writeBoolean(t.hasCostB());
            if (t.hasCostB()) {
                buf.writeResourceLocation(t.costB());
                buf.writeVarInt(t.countB());
                buf.writeNbt(t.costBTag());
            }
            buf.writeResourceLocation(t.result());
            buf.writeVarInt(t.countR());
            buf.writeNbt(t.resultTag());
            buf.writeVarInt(t.maxUses());
            buf.writeVarInt(t.xp());
        }
    }

    private static List<TradeEntry> readTrades(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        int count = buf.readVarInt();
        List<TradeEntry> trades = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation costA = buf.readResourceLocation();
            int countA = buf.readVarInt();
            net.minecraft.nbt.CompoundTag costATag = buf.readNbt();
            boolean hasB = buf.readBoolean();
            ResourceLocation costB = null;
            int countB = 0;
            net.minecraft.nbt.CompoundTag costBTag = null;
            if (hasB) {
                costB = buf.readResourceLocation();
                countB = buf.readVarInt();
                costBTag = buf.readNbt();
            }
            ResourceLocation result = buf.readResourceLocation();
            int countR = buf.readVarInt();
            net.minecraft.nbt.CompoundTag resultTag = buf.readNbt();
            int maxUses = buf.readVarInt();
            int xp = buf.readVarInt();
            trades.add(new TradeEntry(costA, countA, costATag, hasB, costB, countB, costBTag,
                    result, countR, resultTag, maxUses, xp));
        }
        return trades;
    }

    private static void writeBond(FriendlyByteBuf buf, BondSnapshot bond) {
        if (bond == null || !bond.present()) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeBoolean(bond.tmaLoaded());
        buf.writeVarInt(bond.favorability());
        buf.writeVarInt(bond.bondLevel());
        buf.writeBoolean(bond.bondUnlocked());
        buf.writeVarInt(bond.giftQueue());
        buf.writeVarInt(bond.abilities().size());
        for (BondAbilityEntry entry : bond.abilities()) {
            buf.writeUtf(entry.id());
            buf.writeBoolean(entry.unlocked());
        }
    }

    private static BondSnapshot readBond(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        boolean tmaLoaded = buf.readBoolean();
        int favorability = buf.readVarInt();
        int bondLevel = buf.readVarInt();
        boolean bondUnlocked = buf.readBoolean();
        int giftQueue = buf.readVarInt();
        int abilityCount = buf.readVarInt();
        List<BondAbilityEntry> abilities = new ArrayList<>(abilityCount);
        for (int i = 0; i < abilityCount; i++) {
            abilities.add(new BondAbilityEntry(buf.readUtf(64), buf.readBoolean()));
        }
        return new BondSnapshot(true, tmaLoaded, favorability, bondLevel,
                bondUnlocked, giftQueue, abilities);
    }

    /**
     * 反序列化快照，与 {@link #writeSnapshot} 配对。
     */
    public static EntityEditorData readSnapshot(FriendlyByteBuf buf, int entityId) {
        boolean removable = buf.readBoolean();
        float health = buf.readFloat();

        int attrCount = buf.readVarInt();
        List<AttrEntry> attrs = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) {
            attrs.add(new AttrEntry(buf.readResourceLocation(), buf.readDouble()));
        }
        int effectCount = buf.readVarInt();
        List<EffectEntry> effects = new ArrayList<>(effectCount);
        for (int i = 0; i < effectCount; i++) {
            effects.add(new EffectEntry(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt()));
        }
        int traitCount = buf.readVarInt();
        List<TraitEntry> traits = new ArrayList<>(traitCount);
        for (int i = 0; i < traitCount; i++) {
            traits.add(new TraitEntry(buf.readResourceLocation(), buf.readVarInt()));
        }
        int hostilityLevel = buf.readVarInt();
        boolean removalGuard = buf.readBoolean();
        int curiosCount = buf.readVarInt();
        List<CuriosSlotEntry> curiosSlots = new ArrayList<>(curiosCount);
        for (int i = 0; i < curiosCount; i++) {
            curiosSlots.add(new CuriosSlotEntry(buf.readUtf(64), buf.readVarInt()));
        }
        BondSnapshot bond = readBond(buf);
        List<TradeEntry> trades = readTrades(buf);
        io.github.zgxhzhr.superdbg.loot.LootConfig entityLoot =
                io.github.zgxhzhr.superdbg.loot.LootConfig.read(buf);
        io.github.zgxhzhr.superdbg.loot.LootConfig typeLoot =
                io.github.zgxhzhr.superdbg.loot.LootConfig.read(buf);
        io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot vanillaLoot =
                io.github.zgxhzhr.superdbg.loot.VanillaLootSnapshot.read(buf);
        io.github.zgxhzhr.superdbg.gift.GiftPoolConfig giftPool =
                io.github.zgxhzhr.superdbg.gift.GiftPoolConfig.read(buf);
        return new EntityEditorData(entityId, removable, health, attrs, effects, traits,
                hostilityLevel, removalGuard, curiosSlots, bond, trades, entityLoot, typeLoot,
                vanillaLoot, giftPool);
    }

    /**
     * 属性列表转 Map（提交包使用）。
     */
    public static Map<ResourceLocation, Double> attrsToMap(List<AttrEntry> attrs) {
        Map<ResourceLocation, Double> map = new LinkedHashMap<>();
        for (AttrEntry entry : attrs) {
            map.put(entry.id(), entry.baseValue());
        }
        return map;
    }
}
