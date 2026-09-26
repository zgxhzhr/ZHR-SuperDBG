package io.github.zgxhzhr.superdbg.potion;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 药水编辑器注册表。
 * <p>
 * 集中管理所有 {@link PotionEditorProvider} 实现，按注册顺序匹配第一个可编辑的提供者。
 * 后续新增可编辑物品类型时，只需调用 {@link #register(PotionEditorProvider)} 即可。
 */
public final class PotionEditors {

    private static final List<PotionEditorProvider> PROVIDERS = new ArrayList<>();

    static {
        register(new PotionItemEditor());
    }

    private PotionEditors() {
    }

    /**
     * 注册一个编辑器提供者。
     *
     * @param provider 提供者实例
     */
    public static void register(PotionEditorProvider provider) {
        PROVIDERS.add(provider);
    }

    /**
     * 为指定物品查找第一个匹配的编辑器提供者。
     *
     * @param stack 物品堆
     * @return 匹配的提供者，若无则返回 null
     */
    public static PotionEditorProvider find(ItemStack stack) {
        for (PotionEditorProvider provider : PROVIDERS) {
            if (provider.canEdit(stack)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * 判断物品是否可被任意已注册的编辑器处理。
     *
     * @param stack 物品堆
     * @return 可编辑返回 true
     */
    public static boolean canEdit(ItemStack stack) {
        return find(stack) != null;
    }
}
