package io.github.zgxhzhr.superdbg.init;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.mixin.RangedAttributeAccessor;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 启动时把全部 {@link RangedAttribute}（含模组注册属性）的上限提升到 int 最大值。
 * <p>
 * 原版属性上限多为 1024（生命、速度等）或 30/20/1（护甲类），
 * 编辑器写入的超大基础值/物品修饰符会在 {@code AttributeInstance.getValue}
 * 的 sanitizeValue 阶段被钳回，因此必须放宽注册表属性单例的 maxValue。
 * <p>
 * minValue 保持不变：负值下限保护（如最小生命 1）继续生效。
 * 1.20.1 的 maxValue 是 private final 且无 setRange，通过
 * {@link RangedAttributeAccessor} Mixin 访问器写入。
 * <p>
 * 遍历源为 {@link ForgeRegistries#ATTRIBUTES}，因此 Forge 内置属性
 * （BLOCK_REACH/ENTITY_REACH 等）以及其它模组（如 TLM 的 maid_*）注册的
 * 属性上限也会被一并放开，避免"放开上限但仅对原版生效"的表面化问题。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AttributeRangeExpander {

    /** 属性上限：int 最大值（2147483647） */
    public static final double EXPANDED_MAX = Integer.MAX_VALUE;

    private AttributeRangeExpander() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(AttributeRangeExpander::expandAll);
    }

    /**
     * 遍历 Forge 属性注册表，放宽所有 RangedAttribute 的上限。
     * 抽成静态方法便于 GameTest 直接调用验证。
     */
    public static void expandAll() {
        int count = 0;
        for (Attribute attribute : ForgeRegistries.ATTRIBUTES.getValues()) {
            if (attribute instanceof RangedAttribute ranged && ranged.getMaxValue() < EXPANDED_MAX) {
                ((RangedAttributeAccessor) ranged).superdbg$setMaxValue(EXPANDED_MAX);
                count++;
            }
        }
        Constants.LOG.info("已将 {} 个属性的上限提升至 {}", count, (int) EXPANDED_MAX);
    }
}
