package io.github.zgxhzhr.superdbg.item;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 物品编辑器可调整的属性定义。
 * <p>
 * 不再硬编码原版 8 项：动态枚举属性注册表全部条目，模组注册的属性同样可调。
 * 全部以 {@code ADDITION} 操作写入 NBT（{@code AttributeModifiers} 列表），
 * 数值含义为"物品提供的加成"（不含玩家基础值，例如玩家基础攻击 1 不计入）。
 * <p>
 * 上限统一为 int 最大值：模组启动时已通过 Mixin 把全部 RangedAttribute
 * （含模组属性）的 sanitizeValue 上限提升到 int 最大值，超大数值可以真正生效。
 * 输入方式为直接填精确数值，不做步进吸附（避免破坏 -2.4 这类默认值）。
 *
 * @param attribute 属性
 */
public record EditableAttribute(Attribute attribute) {

    /** 统一输入范围：±int 最大值（2147483647） */
    public static final double MAX_VALUE = Integer.MAX_VALUE;

    /**
     * 动态枚举全部已注册属性（含模组属性）。
     * 原版（minecraft 命名空间）排在前面，其余按注册名排序，保证界面顺序稳定。
     */
    public static List<EditableAttribute> all() {
        List<EditableAttribute> list = new ArrayList<>();
        BuiltInRegistries.ATTRIBUTE.forEach(a -> list.add(new EditableAttribute(a)));
        list.sort(Comparator
                .comparing((EditableAttribute ea) -> !isVanilla(ea))
                .thenComparing(EditableAttribute::id));
        return list;
    }

    private static boolean isVanilla(EditableAttribute ea) {
        return BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute()).getNamespace().equals("minecraft");
    }

    private static String id(EditableAttribute ea) {
        return BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute()).toString();
    }

    /**
     * 把输入值限制到范围。服务端校验与客户端输入均使用。
     * 非法浮点（NaN/Infinity）一律按 0 处理，避免后续序列化或比较异常。
     */
    public double normalize(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(-MAX_VALUE, Math.min(MAX_VALUE, value));
    }
}
