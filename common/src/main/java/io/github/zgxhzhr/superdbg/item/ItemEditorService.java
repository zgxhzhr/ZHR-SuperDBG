package io.github.zgxhzhr.superdbg.item;

import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通用物品编辑器的数据读写与原子写回。
 * <p>
 * 关键机制：1.20.1 中物品一旦存在 {@code AttributeModifiers} NBT，
 * 该列表会<b>完全替换</b>物品默认修饰符（不是合并），因此写回时必须
 * 显式带上默认修饰符，否则钻石剑等物品的基础数值会全部丢失。
 */
public final class ItemEditorService {

    /** 调试斧标记 NBT key */
    public static final String DEBUG_TAG = "SuperDbgDebug";
    /** 旧版调试斧标记：改名前发出的调试斧仍可识别，重新编辑物品时自动迁移为 {@link #DEBUG_TAG} */
    public static final String LEGACY_DEBUG_TAG = "TiaoshiDebug";
    /** 调试斧名称 */
    public static final String DEBUG_NAME = "调试斧";
    /** 名称长度上限 */
    public static final int MAX_NAME_LENGTH = 64;
    /**
     * 附魔等级上限。
     * <p>
     * 原版 {@code EnchantmentHelper.getEnchantmentLevel(CompoundTag)} 读取 NBT 时
     * 执行 {@code Mth.clamp(getInt("lvl"), 0, 255)}，且 {@code setEnchantmentLevel}
     * 使用 {@code putShort("lvl", (short)pLevel)} 写入（short 范围 -32768~32767），
     * 双重瓶颈把实际生效上限压到 255（写入端）。
     * <p>
     * 本模组通过 {@code EnchantmentHelperMixin} 同时放开两端：
     * <ul>
     *   <li>写入：{@code putShort} → {@code putInt}，支持 int 全范围</li>
     *   <li>读取：跳过 {@code Mth.clamp}，直接返回 NBT 中的原始 int</li>
     * </ul>
     * 因此 MAX_ENCHANT_LEVEL 可放开到 {@link Integer#MAX_VALUE}。
     */
    public static final int MAX_ENCHANT_LEVEL = Integer.MAX_VALUE;

    private ItemEditorService() {
    }

    /**
     * 调试斧判定：金斧 + SuperDbgDebug 标记（旧版 TiaoshiDebug 标记同样认可）。
     * 物品编辑器与实体编辑器共用此判定。
     */
    public static boolean isDebugAxe(ItemStack stack) {
        return stack.is(Items.GOLDEN_AXE) && stack.hasTag()
                && (stack.getTag().getBoolean(DEBUG_TAG)
                    || stack.getTag().getBoolean(LEGACY_DEBUG_TAG));
    }

    /**
     * 从物品当前 NBT 读取编辑器数据。
     */
    public static ItemEditorData read(ItemStack stack) {
        ItemEditorData data = new ItemEditorData();
        data.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());

        if (stack.hasCustomHoverName()) {
            data.customName = stack.getHoverName().getString();
        }

        CompoundTag tag = stack.getTag();
        if (tag != null) {
            data.unbreakable = tag.getBoolean("Unbreakable");
            data.debug = tag.getBoolean(DEBUG_TAG) || tag.getBoolean(LEGACY_DEBUG_TAG);
        }

        for (Map.Entry<Enchantment, Integer> e : EnchantmentHelper.getEnchantments(stack).entrySet()) {
            data.enchantments.put(BuiltInRegistries.ENCHANTMENT.getKey(e.getKey()), e.getValue());
        }

        readAttributeValues(stack, data.attributes);
        return data;
    }

    /**
     * 读取受管属性当前生效总值（所有槽位 ADDITION 之和，每个 NBT 条目只计一次）。
     */
    private static void readAttributeValues(ItemStack stack, Map<ResourceLocation, Double> out) {
        Map<ResourceLocation, Double> sums = new HashMap<>();

        if (stack.hasTag() && stack.getTag().contains("AttributeModifiers", Tag.TAG_LIST)) {
            // 直接遍历 NBT 条目：无 Slot 的条目对每个槽位都生效，
            // 不能按槽位遍历 getAttributeModifiers，否则会重复累加
            ListTag list = stack.getTag().getList("AttributeModifiers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                ResourceLocation rl = ResourceLocation.tryParse(c.getString("AttributeName"));
                if (rl == null || !isManagedAttribute(rl)) continue;
                if (c.getInt("Operation") != AttributeModifier.Operation.ADDITION.toValue()) continue;
                sums.merge(rl, c.getDouble("Amount"), Double::sum);
            }
        } else {
            // 无 NBT 修饰符：读取物品默认修饰符（默认条目都带具体槽位，不会跨槽位重复）
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                for (Map.Entry<Attribute, AttributeModifier> e
                        : stack.getItem().getDefaultAttributeModifiers(slot).entries()) {
                    ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(e.getKey());
                    if (!isManagedAttribute(rl)) continue;
                    if (e.getValue().getOperation() != AttributeModifier.Operation.ADDITION) continue;
                    sums.merge(rl, e.getValue().getAmount(), Double::sum);
                }
            }
        }

        for (EditableAttribute ea : EditableAttribute.ALL) {
            ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute());
            // 保留真实值，不做步进吸附（默认值如钻石剑攻速 -2.4 可能不在步进网格上）
            out.put(rl, sums.getOrDefault(rl, 0.0D));
        }
    }

    /**
     * 把编辑数据原子写回物品（服务端调用，内部做全部防御性校验）。
     */
    public static void apply(ItemStack stack, ItemEditorData input) {
        if (stack == null || stack.isEmpty() || input == null) {
            return;
        }

        boolean goldenAxe = stack.is(Items.GOLDEN_AXE);
        boolean debug = goldenAxe && input.debug;

        // ---- 名称 ----
        String name = sanitizeName(input.customName);
        if (debug) {
            stack.setHoverName(Component.literal(DEBUG_NAME));
        } else if (!name.isEmpty()) {
            stack.setHoverName(Component.literal(name));
        } else {
            stack.resetHoverName();
        }

        // ---- 无法破坏 ----
        if (input.unbreakable) {
            stack.getOrCreateTag().putBoolean("Unbreakable", true);
        } else if (stack.hasTag()) {
            stack.getTag().remove("Unbreakable");
        }

        // ---- 调试标记 ----
        if (debug) {
            stack.getOrCreateTag().putBoolean(DEBUG_TAG, true);
            stack.getTag().remove(LEGACY_DEBUG_TAG); // 旧标记迁移
        } else if (stack.hasTag()) {
            stack.getTag().remove(DEBUG_TAG);
            stack.getTag().remove(LEGACY_DEBUG_TAG);
        }

        // ---- 附魔（调试斧强制无附魔）----
        Map<Enchantment, Integer> ench = new LinkedHashMap<>();
        if (!debug) {
            for (Map.Entry<ResourceLocation, Integer> e : input.enchantments.entrySet()) {
                Enchantment enchantment = BuiltInRegistries.ENCHANTMENT.get(e.getKey());
                if (enchantment == null) continue;
                int level = e.getValue() == null ? 0 : e.getValue();
                if (level >= 1 && level <= MAX_ENCHANT_LEVEL) {
                    ench.put(enchantment, level);
                }
            }
        }
        EnchantmentHelper.setEnchantments(ench, stack);

        // ---- 属性修饰符（整体重建，保留默认值与未知条目）----
        Map<ResourceLocation, Double> targets = new HashMap<>();
        for (EditableAttribute ea : EditableAttribute.ALL) {
            ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute());
            double requested = input.attributes.getOrDefault(rl, ea.normalize(0.0D));
            targets.put(rl, ea.normalize(requested));
        }
        rebuildAttributeModifiers(stack, targets);
    }

    /**
     * 重建 AttributeModifiers NBT：
     * 默认修饰符合并写回；受管属性用目标值替换；未知/非 ADDITION 条目原样保留。
     */
    private static void rebuildAttributeModifiers(ItemStack stack, Map<ResourceLocation, Double> targets) {
        // 1. 计算各受管属性的默认总和，并收集需要原样保留的旧 NBT 条目
        Map<ResourceLocation, Double> defaultTotals = new HashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            for (Map.Entry<Attribute, AttributeModifier> e
                    : stack.getItem().getDefaultAttributeModifiers(slot).entries()) {
                ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(e.getKey());
                if (isManagedAttribute(rl)
                        && e.getValue().getOperation() == AttributeModifier.Operation.ADDITION) {
                    defaultTotals.merge(rl, e.getValue().getAmount(), Double::sum);
                }
            }
        }

        List<Tag> preserved = new ArrayList<>();
        if (stack.hasTag() && stack.getTag().contains("AttributeModifiers", Tag.TAG_LIST)) {
            ListTag old = stack.getTag().getList("AttributeModifiers", Tag.TAG_COMPOUND);
            for (int i = 0; i < old.size(); i++) {
                CompoundTag c = old.getCompound(i);
                ResourceLocation rl = ResourceLocation.tryParse(c.getString("AttributeName"));
                // 受管属性的 ADDITION 条目由我们重建；其余（未知属性、乘法运算）原样保留
                if (rl != null && isManagedAttribute(rl)
                        && c.getInt("Operation") == AttributeModifier.Operation.ADDITION.toValue()) {
                    continue;
                }
                preserved.add(c.copy());
            }
        }

        // 2. 移除旧列表
        if (stack.hasTag()) {
            stack.getTag().remove("AttributeModifiers");
        }

        // 3. 默认修饰符：受管属性若目标值等于默认值则原样保留槽位，否则丢弃（稍后写任意槽位的新值）
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            for (Map.Entry<Attribute, AttributeModifier> e
                    : stack.getItem().getDefaultAttributeModifiers(slot).entries()) {
                ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(e.getKey());
                if (isManagedAttribute(rl)) {
                    double target = targets.getOrDefault(rl, 0.0D);
                    double def = defaultTotals.getOrDefault(rl, 0.0D);
                    if (Math.abs(target - def) < 1.0E-6D) {
                        stack.addAttributeModifier(e.getKey(), e.getValue(), slot);
                    }
                    // 不相等的默认条目丢弃
                } else {
                    stack.addAttributeModifier(e.getKey(), e.getValue(), slot);
                }
            }
        }

        // 4. 受管属性：目标值不等于默认值时，写一个绑定主槽位的新修饰符。
        //    不能写 slot=null：无槽位条目对每个装备槽都生效，
        //    tooltip 会在主手/副手/头/胸/腿/脚下重复显示 6 次同一加成
        EquipmentSlot primarySlot = primaryEquipmentSlot(stack);
        for (EditableAttribute ea : EditableAttribute.ALL) {
            ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute());
            double target = targets.getOrDefault(rl, 0.0D);
            double def = defaultTotals.getOrDefault(rl, 0.0D);
            if (Math.abs(target - def) >= 1.0E-6D) {
                // 注意：UUID 种子与修饰符名是旧版物品的持久化身份，重新编辑时要按同一
                // UUID 先删旧条目，故虽模组改名仍保留 tiaoshi- 前缀，不可更改。
                UUID uuid = UUID.nameUUIDFromBytes(
                        ("tiaoshi-item-attr-" + rl).getBytes(StandardCharsets.UTF_8));
                AttributeModifier modifier = new AttributeModifier(
                        uuid, "tiaoshi_edited_" + rl.getPath(),
                        target, AttributeModifier.Operation.ADDITION);
                stack.addAttributeModifier(ea.attribute(), modifier, primarySlot);
            }
        }

        // 5. 保留旧的未知条目（直接追加原始 tag）
        if (!preserved.isEmpty()) {
            ListTag list = stack.getOrCreateTag().getList("AttributeModifiers", Tag.TAG_COMPOUND);
            for (Tag t : preserved) {
                list.add(t);
            }
        }
    }

    /**
     * 物品的主装备槽位：有默认护甲属性的物品用其对应护甲槽（头盔→头部），
     * 其余物品用主手。修改过的属性修饰符绑定该槽位，
     * 使 tooltip 只显示一行且在物品的正常使用位置生效。
     */
    private static EquipmentSlot primaryEquipmentSlot(ItemStack stack) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!stack.getItem().getDefaultAttributeModifiers(slot).isEmpty()) {
                return slot;
            }
        }
        return EquipmentSlot.MAINHAND;
    }

    /**
     * 名称清洗：截断长度并移除原版格式控制符。
     */
    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), MAX_NAME_LENGTH));
        for (int i = 0; i < name.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char c = name.charAt(i);
            if (c == '§' || c == '') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 判断属性 id 是否由编辑器管理。
     */
    public static boolean isManagedAttribute(ResourceLocation rl) {
        for (EditableAttribute ea : EditableAttribute.ALL) {
            if (BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute()).equals(rl)) {
                return true;
            }
        }
        return false;
    }
}
