package io.github.zgxhzhr.superdbg.gift;

import io.github.zgxhzhr.superdbg.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 女仆回赠礼物池的读取与抽取（服务端）。
 * <p>
 * 生效机制：{@code TmaRandomGiftMixin} 以 {@code @Pseudo} 零编译依赖方式注入
 * TMA 的 {@code RandomGiftService.rollGiftStack} HEAD——该女仆的自定义池存在且
 * 非空时直接返回池中加权抽取的物品（可携带 NBT），否则取消注入、放行 TMA 默认池。
 * <p>
 * 配置仅存于女仆实体 persistentData（{@link GiftPoolConfig#NBT_KEY}），随女仆存档。
 */
public final class GiftPoolService {

    private GiftPoolService() {
    }

    /** 读取目标女仆的自定义回礼池配置；无配置返回 null */
    public static GiftPoolConfig findPool(LivingEntity maid) {
        CompoundTag persistent = persistentData(maid);
        if (persistent == null
                || !persistent.contains(GiftPoolConfig.NBT_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        return GiftPoolConfig.fromNbt(persistent.getCompound(GiftPoolConfig.NBT_KEY));
    }

    /**
     * 按自定义池抽取一件礼物；无配置、空池或物品全部失效时返回
     * {@link ItemStack#EMPTY}，调用方（Mixin）据此放行 TMA 默认抽取逻辑。
     */
    public static ItemStack roll(LivingEntity maid) {
        try {
            GiftPoolConfig pool = findPool(maid);
            if (pool == null || pool.entries().isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack gift = pool.roll(maid.getRandom());
            if (gift.isEmpty()) {
                Constants.LOG.debug("[SuperDbg] 女仆自定义回礼池无有效物品，回退 TMA 默认池: {}",
                        maid);
            }
            return gift;
        } catch (Exception e) {
            // 配置损坏等异常不得打断 TMA 发礼物流程
            Constants.LOG.warn("[SuperDbg] 读取女仆自定义回礼池异常，回退 TMA 默认池", e);
            return ItemStack.EMPTY;
        }
    }

    /**
     * 通过反射调用 Forge 扩展方法 {@code Entity.getPersistentData()}，
     * 兼容 multi-loader common 模块编译（与 LootOverrideService 同款模式）。
     */
    private static CompoundTag persistentData(LivingEntity entity) {
        try {
            return (CompoundTag) net.minecraft.world.entity.Entity.class
                    .getMethod("getPersistentData").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("无法获取实体 persistentData", e);
        }
    }
}
