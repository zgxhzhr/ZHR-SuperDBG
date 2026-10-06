package io.github.zgxhzhr.superdbg.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 伪创造模式状态（服务端）。
 * <p>
 * 开启后玩家「受击流程完全正常」（怪物正常索敌、攻击命中、受击音效/击退/红闪照旧），
 * 只是生命值不掉：
 * <ul>
 *   <li>创造模式：血量锁定为开启瞬间的值（每 tick 拉回），并禁止药水/效果恢复
 *       （{@code heal} 拦截）与饱食度自然恢复（{@code FoodData.tick} 拦截）</li>
 *   <li>生存模式：不做 tick 干预，受击掉血由 {@code LivingEntity.hurt} 前后快照拉回，
 *       死亡由 {@code die} 拦截</li>
 * </ul>
 * 状态持久化在目标玩家 {@code persistentData}（键
 * {@code superdbg_pseudo_creative} / {@code superdbg_pseudo_health}），
 * 重进世界后仍生效。
 */
public final class PseudoCreativeState {

    /** 伪创造开关键 */
    private static final String KEY = "superdbg_pseudo_creative";
    /** 开启瞬间记录的生命值（float），创造模式锁血基准 */
    private static final String HEALTH_KEY = "superdbg_pseudo_health";

    private PseudoCreativeState() {
    }

    /** 伪创造是否开启（客户端一律视为关闭；非玩家实体持久化无此键，自然为 false） */
    public static boolean isEnabled(LivingEntity entity) {
        if (entity == null || entity.level() == null || entity.level().isClientSide) {
            return false;
        }
        return persistentData(entity).getBoolean(KEY);
    }

    /** 是否有锁定血量（开启瞬间已记录） */
    public static boolean hasLockedHealth(Player player) {
        return persistentData(player).contains(HEALTH_KEY, net.minecraft.nbt.Tag.TAG_FLOAT);
    }

    /** 锁定血量；无记录返回 -1.0F */
    public static float getLockedHealth(Player player) {
        CompoundTag data = persistentData(player);
        return data.contains(HEALTH_KEY, net.minecraft.nbt.Tag.TAG_FLOAT)
                ? data.getFloat(HEALTH_KEY) : -1.0F;
    }

    /** 开启：记录开关并写入开启瞬间血量；关闭：清除全部伪创造状态 */
    public static void set(Player player, boolean enabled) {
        CompoundTag data = persistentData(player);
        if (enabled) {
            data.putBoolean(KEY, true);
            data.putFloat(HEALTH_KEY, player.getHealth());
        } else {
            clear(player);
        }
    }

    /** 清除全部伪创造状态（关闭开关） */
    public static void clear(Player player) {
        CompoundTag data = persistentData(player);
        data.remove(KEY);
        data.remove(HEALTH_KEY);
    }

    /**
     * 服务端每 tick 维持（玩家 tick 事件调用）：仅创造模式需要 tick 干预
     * （锁血）；生存模式受击由 {@code hurt} 前后快照拉回，无需干预。
     */
    public static void tick(ServerPlayer player) {
        if (player == null || player.level().isClientSide) {
            return;
        }
        CompoundTag data = persistentData(player);
        if (!data.getBoolean(KEY)) {
            return;
        }
        // 创造模式才锁血；生存模式不做任何 tick 干预
        if (!player.isCreative()) {
            return;
        }
        // 无锁定值（异常/旧档）以当前值兜底，避免数值异常
        float locked = data.contains(HEALTH_KEY, net.minecraft.nbt.Tag.TAG_FLOAT)
                ? data.getFloat(HEALTH_KEY) : player.getHealth();
        if (Math.abs(player.getHealth() - locked) > 1.0E-4F) {
            player.setHealth(locked);
        }
    }

    private static CompoundTag persistentData(LivingEntity entity) {
        try {
            return (CompoundTag) Entity.class.getMethod("getPersistentData").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("无法获取实体 persistentData", e);
        }
    }
}
