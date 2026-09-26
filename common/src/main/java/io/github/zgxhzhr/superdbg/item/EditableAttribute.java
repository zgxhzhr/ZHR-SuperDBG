package io.github.zgxhzhr.superdbg.item;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * 物品编辑器可调整的原版属性定义。
 * <p>
 * 全部以 {@code ADDITION} 操作、绑定主槽位的形式写入 NBT，
 * 数值含义为"物品提供的加成"（不含玩家基础值，例如玩家基础攻击 1 不计入）。
 * <p>
 * 上限统一为 int 最大值：模组启动时已通过 Mixin 把全部 RangedAttribute
 * （含这些原版属性）的 sanitizeValue 上限提升到 int 最大值，
 * 超大数值可以真正生效。下限保留原版语义（攻速负修正、护甲非负等）。
 * 输入方式为直接填精确数值，不做步进吸附（避免破坏 -2.4 这类默认值）。
 *
 * @param attribute 属性
 * @param min       校验下限
 * @param max       校验上限
 */
public record EditableAttribute(Attribute attribute, double min, double max) {

    /** 统一上限：int 最大值（2147483647） */
    public static final double MAX_VALUE = Integer.MAX_VALUE;

    public static final List<EditableAttribute> ALL = List.of(
            new EditableAttribute(Attributes.ATTACK_DAMAGE, 0.0D, MAX_VALUE),
            new EditableAttribute(Attributes.ATTACK_SPEED, -4.0D, MAX_VALUE),
            new EditableAttribute(Attributes.MAX_HEALTH, 0.0D, MAX_VALUE),
            new EditableAttribute(Attributes.MOVEMENT_SPEED, -1.0D, MAX_VALUE),
            new EditableAttribute(Attributes.ARMOR, 0.0D, MAX_VALUE),
            new EditableAttribute(Attributes.ARMOR_TOUGHNESS, 0.0D, MAX_VALUE),
            new EditableAttribute(Attributes.KNOCKBACK_RESISTANCE, 0.0D, MAX_VALUE),
            new EditableAttribute(Attributes.LUCK, -1024.0D, MAX_VALUE)
    );

    /**
     * 把输入值限制到范围。服务端校验与客户端输入均使用。
     */
    public double normalize(double value) {
        return Math.max(min, Math.min(max, value));
    }
}
