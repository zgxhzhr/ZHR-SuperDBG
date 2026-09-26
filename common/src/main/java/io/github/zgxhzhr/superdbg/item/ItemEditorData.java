package io.github.zgxhzhr.superdbg.item;

import net.minecraft.resources.ResourceLocation;

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
    /** 受管属性 id → 当前总值（ADDITION 之和） */
    public final Map<ResourceLocation, Double> attributes = new LinkedHashMap<>();
}
