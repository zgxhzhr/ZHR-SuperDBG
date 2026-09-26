package io.github.zgxhzhr.superdbg.compat;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 对"带复活名册 / 自定义血条"的第三方 Boss 模组的通用软兼容
 * （Class.forName 检测，目标模组未安装时全部空操作）。
 * <p>
 * 编辑器强删这类实体时，光让实体消失不够：部分模组在自己的静态名册里
 * 还登记着它，下一 tick 会把同一对象重新加回世界；实体上挂的
 * {@link ServerBossEvent} 不清玩家订阅，屏幕顶部血条会残留。
 * 本类只做两件标准清理：
 * <ol>
 *   <li>若实体属于某模组的"可复活"接口，调用该模组自己提供的名册移除方法；</li>
 *   <li>扫描实体字段中的 {@link ServerBossEvent}，解除全部玩家订阅并隐藏。</li>
 * </ol>
 * 均为反射调用对方公开/常规清理入口，不包含任何第三方代码。
 */
public final class ThirdPartyBossCompat {

    private ThirdPartyBossCompat() {
    }

    private static volatile Class<?> respawnableInterface;
    private static volatile boolean interfaceResolved;
    private static volatile Method respawningRemove;
    private static volatile boolean methodsResolved;

    private static Class<?> respawnableInterface() {
        if (!interfaceResolved) {
            synchronized (ThirdPartyBossCompat.class) {
                if (!interfaceResolved) {
                    try {
                        respawnableInterface = Class.forName(
                                "flashfur.omnimobs.entities.flashfur.powers.IRespawningEntity");
                    } catch (ClassNotFoundException e) {
                        respawnableInterface = null;
                    }
                    interfaceResolved = true;
                }
            }
        }
        return respawnableInterface;
    }

    private static void resolveMethods() {
        if (methodsResolved) {
            return;
        }
        synchronized (ThirdPartyBossCompat.class) {
            if (methodsResolved) {
                return;
            }
            try {
                Class<?> respawning = Class.forName(
                        "flashfur.omnimobs.entities.flashfur.powers.Respawning");
                Class<?> iface = respawnableInterface();
                if (iface != null) {
                    respawningRemove = respawning.getMethod("remove", iface);
                }
            } catch (Throwable ignored) {
            }
            methodsResolved = true;
        }
    }

    /** 编辑器强删准备阶段：先摘一次复活名册（最终以 {@link #afterFullDelete} 再摘为准），
     *  并清理实体上可能残留的 Boss 血条订阅。 */
    public static void beforeForceRemove(Entity target) {
        removeFromRespawnRoster(target, "beforeForceRemove");
        clearBossBars(target);
    }

    /** fullDelete 成功后立即调用：从复活名册再摘除一次（幂等），
     *  防止同 tick 稍后被模组的复活 tick 重新加回世界。 */
    public static void afterFullDelete(Entity target) {
        removeFromRespawnRoster(target, "afterFullDelete");
    }

    private static void removeFromRespawnRoster(Entity target, String phase) {
        Class<?> iface = respawnableInterface();
        if (iface == null) {
            return;
        }
        resolveMethods();
        if (respawningRemove == null || !iface.isInstance(target)) {
            return;
        }
        try {
            respawningRemove.invoke(null, target);
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 第三方复活名册移除成功({}): uuid={}", phase, target.getUUID());
        } catch (Throwable e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 第三方复活名册移除失败({}): uuid={}", phase, target.getUUID(), e);
        }
    }

    /**
     * 通用 Boss 血条清理：遍历实体全部声明字段，找到 {@link ServerBossEvent}
     * 就解除玩家订阅并隐藏。对任何模组挂在实体上的血条都生效，不依赖具体模组 API。
     */
    private static void clearBossBars(Entity target) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                if (!ServerBossEvent.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(target);
                    if (value instanceof ServerBossEvent bossEvent) {
                        bossEvent.removeAllPlayers();
                        bossEvent.setVisible(false);
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
}
