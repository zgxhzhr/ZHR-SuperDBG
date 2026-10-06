package io.github.zgxhzhr.superdbg.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物品编辑器的完整编辑数据（一次提交的原子载荷）。
 * <p>
 * 纯 POJO，不依赖网络类；编解码由 forge 侧网络包负责。
 */
public final class ItemEditorData {

    /** 被编辑物品的注册 id（用于调试斧等物品特判） */
    public ResourceLocation itemId;
    /** 自定义名称，空字符串表示无自定义名 */
    public String customName = "";
    /** 无法破坏 */
    public boolean unbreakable = false;
    /** 调试状态（仅金斧有效） */
    public boolean debug = false;
    /** 附魔 id → 等级（1-32767），不含未附加的附魔 */
    public final Map<ResourceLocation, Integer> enchantments = new LinkedHashMap<>();
    /** 属性 id → 目标总值（ADDITION 之和），涵盖全部已注册属性 */
    public final Map<ResourceLocation, Double> attributes = new LinkedHashMap<>();
    /** 属性 id → 自定义修饰符生效的装备槽位（仅目标值与默认不同的属性实际写入） */
    public final Map<ResourceLocation, EquipmentSlot> attributeSlots = new LinkedHashMap<>();
}
