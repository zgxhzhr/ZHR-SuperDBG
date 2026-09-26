package io.github.zgxhzhr.superdbg.potion;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 药水编辑器的扩展点接口。
 * <p>
 * 后续若要支持其他可编辑物品（如药箭、谜之炖菜等），
 * 只需新增本接口的实现并注册即可，无需修改核心流程。
 */
public interface PotionEditorProvider {

    /**
     * 判断该物品是否可被本编辑器处理。
     *
     * @param stack 待判断的物品堆
     * @return 可编辑返回 true
     */
    boolean canEdit(ItemStack stack);

    /**
     * 读取物品上的所有药水效果。
     *
     * @param stack 物品堆
     * @return 效果数据列表（不可修改视图）
     */
    List<PotionEffectData> readEffects(ItemStack stack);

    /**
     * 原子地修改指定索引的效果等级与时长。
     * <p>
     * 必须在同一线程内完成读-改-写，不产生中间状态。
     *
     * @param stack     物品堆
     * @param index     效果索引
     * @param amplifier 新等级（0-255）
     * @param duration  新时长（-1 表示永久，或非负 tick 数）
     * @throws IndexOutOfBoundsException 索引越界
     * @throws IllegalArgumentException  数值越界或物品不可编辑
     */
    void writeEffect(ItemStack stack, int index, int amplifier, int duration);
}
