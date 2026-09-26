package io.github.zgxhzhr.superdbg.compat.curios;

import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.util.ISlotHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Curios 饰品栏兼容层。
 * <p>
 * 本类是模组中直接引用 Curios 类型的地方之一。主代码仅在
 * {@link #isLoaded()} 为真时才调用本类，JVM 对方法体内类型为延迟加载，
 * 未安装 Curios 时本类不会被加载，因此不会触发 NoClassDefFoundError。
 * <p>
 * 数据模型：Curios 通过 Capability（{@link ICuriosItemHandler}）给实体
 * 挂饰品栏，每个槽位类型（ring/necklace/curio 等）对应一个
 * {@link ICurioStacksHandler}，其 {@code getSlots()} 返回当前槽位数量。
 * 修改数量通过 {@link ISlotHelper#setSlotsForType} 自动 grow/shrink。
 */
public final class CuriosCompat {

    /** Curios 模组 id */
    public static final String MODID = "curios";

    /** 实体编辑器允许的饰品栏数量上限 */
    public static final int MAX_SLOTS = 999;

    private CuriosCompat() {
    }

    /**
     * Curios 是否安装。所有调用方必须先过此守卫。
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded(MODID);
    }

    /**
     * 目标实体是否有 Curios 饰品栏（Capability 不为空且至少有一个槽位类型）。
     */
    public static boolean isSupported(LivingEntity entity) {
        if (!isLoaded() || entity == null) {
            return false;
        }
        return !CuriosApi.getEntitySlots(entity).isEmpty();
    }

    /**
     * 读取实体当前所有 Curios 槽位类型及数量快照。
     * 仅返回实体实际拥有的槽位类型（来自 datapack 注册）。
     */
    public static List<EntityEditorData.CuriosSlotEntry> readSlots(LivingEntity entity) {
        if (!isSupported(entity)) {
            return List.of();
        }
        List<EntityEditorData.CuriosSlotEntry> result = new ArrayList<>();
        CuriosApi.getCuriosInventory(entity).ifPresent(handler -> {
            for (ICurioStacksHandler stacks : handler.getCurios().values()) {
                result.add(new EntityEditorData.CuriosSlotEntry(
                        stacks.getIdentifier(), stacks.getSlots()));
            }
        });
        return result;
    }

    /**
     * 全量写回 Curios 槽位数量。
     * <p>
     * 通过 {@link ISlotHelper#setSlotsForType} 自动计算 diff 并 grow/shrink，
     * 内部会触发 {@link top.theillusivec4.curios.common.inventory.CurioStacksHandler#update}
     * 重新计算 modifiers 并 resize DynamicStackHandler。
     *
     * @param submitted 全量槽位列表（未包含的槽位类型保持不变）
     */
    @SuppressWarnings("deprecation")
    public static void apply(LivingEntity entity, List<EntityEditorData.CuriosSlotEntry> submitted) {
        if (!isSupported(entity) || submitted == null) {
            return;
        }
        ISlotHelper slotHelper = CuriosApi.getSlotHelper();
        if (slotHelper == null) {
            return;
        }
        for (EntityEditorData.CuriosSlotEntry entry : submitted) {
            int amount = Math.max(0, Math.min(MAX_SLOTS, entry.amount()));
            slotHelper.setSlotsForType(entry.identifier(), entity, amount);
        }
    }
}
