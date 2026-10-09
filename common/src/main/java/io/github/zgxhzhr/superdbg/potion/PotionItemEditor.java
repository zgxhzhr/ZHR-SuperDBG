package io.github.zgxhzhr.superdbg.potion;

import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 原版药水物品（普通药水、喷溅药水、滞留药水）的编辑器实现。
 * <p>
 * 三种药水共用相同的 NBT 结构（{@code Potion} 基础药水标签 +
 * {@code CustomPotionEffects} 自定义效果列表），因此统一处理。
 * <p>
 * 写入策略：将所有效果（含基础药水效果）统一写入 {@code CustomPotionEffects}，
 * 并把基础药水置为 {@link Potions#EMPTY}，确保编辑后的效果完全由自定义标签承载，
 * 避免基础药水与自定义效果叠加导致重复生效。
 */
public class PotionItemEditor implements PotionEditorProvider {

    @Override
    public boolean canEdit(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var item = stack.getItem();
        return item instanceof PotionItem
                || item instanceof SplashPotionItem
                || item instanceof LingeringPotionItem;
    }

    @Override
    public List<PotionEffectData> readEffects(ItemStack stack) {
        if (!canEdit(stack)) {
            return Collections.emptyList();
        }
        List<MobEffectInstance> instances = PotionUtils.getMobEffects(stack);
        List<PotionEffectData> result = new ArrayList<>(instances.size());
        for (MobEffectInstance inst : instances) {
            result.add(new PotionEffectData(
                    inst.getEffect(),
                    inst.getAmplifier(),
                    inst.getDuration(),
                    inst.isAmbient(),
                    inst.isVisible(),
                    inst.showIcon()
            ));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public void writeEffect(ItemStack stack, int index, int amplifier, int duration) {
        if (!canEdit(stack)) {
            throw new IllegalArgumentException("物品不可编辑：" + stack);
        }

        // 读取全部效果（基础药水 + 自定义）
        List<MobEffectInstance> effects = new ArrayList<>(PotionUtils.getMobEffects(stack));
        if (index < 0 || index >= effects.size()) {
            throw new IndexOutOfBoundsException("效果索引越界：" + index + "，总数 " + effects.size());
        }

        // 校验数值
        new PotionEffectData(null, amplifier, duration, false, true, true).validate();

        MobEffectInstance original = effects.get(index);
        // 保留原效果的 ambient/visible/showIcon/factorData，仅替换 amplifier 与 duration
        MobEffectInstance updated = new MobEffectInstance(
                original.getEffect(),
                duration,
                amplifier,
                original.isAmbient(),
                original.isVisible(),
                original.showIcon(),
                null,
                original.getFactorData()
        );
        effects.set(index, updated);

        writeAllEffects(stack, effects);
    }

    @Override
    public void addEffect(ItemStack stack, MobEffect effect, int amplifier, int duration) {
        if (!canEdit(stack)) {
            throw new IllegalArgumentException("物品不可编辑：" + stack);
        }
        if (effect == null) {
            throw new IllegalArgumentException("药水效果为空");
        }

        // 校验数值
        new PotionEffectData(effect, amplifier, duration, false, true, true).validate();

        // 在原有全部效果之后追加一条新效果
        List<MobEffectInstance> effects = new ArrayList<>(PotionUtils.getMobEffects(stack));
        effects.add(new MobEffectInstance(effect, duration, amplifier));

        writeAllEffects(stack, effects);
    }

    /**
     * 原子写回：基础药水置空，全部效果写入自定义标签。
     * <p>
     * 保存原显示名（{@code setPotion(EMPTY)} 会把名称变成"不可合成的药水"），
     * 并先清除旧的 {@code CustomPotionEffects}（{@code setCustomEffects} 只追加不覆盖）。
     */
    private static void writeAllEffects(ItemStack stack, List<MobEffectInstance> effects) {
        Component originalName = stack.getHoverName();
        PotionUtils.setPotion(stack, Potions.EMPTY);
        stack.removeTagKey("CustomPotionEffects");
        PotionUtils.setCustomEffects(stack, effects);
        // 恢复原始显示名称
        stack.setHoverName(originalName);
    }
}
