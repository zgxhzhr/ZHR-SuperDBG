package io.github.zgxhzhr.superdbg.compat;

import io.github.zgxhzhr.superdbg.platform.Services;

/**
 * yuyu 系列模组（通天之路整合包自研）存在性检测。
 * <p>
 * yuyu 使用原生 ASM 转换器改写了 LivingEntity 的 {@code getHealth}、
 * {@code isDeadOrDying}、{@code setHealth}、{@code die} 及
 * {@code SynchedEntityData.DataItem.setValue} 等方法（伪造血量、死亡守卫、
 * 血量上限钳制）。本模组针对这些改写提供还原逻辑，但<strong>仅在 yuyu
 * 实际存在时启用</strong>，以保证在其它整合包（无 yuyu）中的通用兼容性：
 * 不干预原版/其它模组对这些方法的修改，避免注入冲突（如 Sinytra
 * Connector 的 fabric-entity-events-v1 对 die() 的 WrapOperation）。
 */
public final class YuyuCompat {

    /** 任一 yuyu 模组已加载。类加载时计算一次，运行期零开销。 */
    public static final boolean LOADED = detect();

    private YuyuCompat() {
    }

    private static boolean detect() {
        try {
            return Services.PLATFORM.isModLoaded("yuyu_core")
                    || Services.PLATFORM.isModLoaded("yuyu_weapon")
                    || Services.PLATFORM.isModLoaded("yuyu_compat");
        } catch (Throwable t) {
            return false;
        }
    }
}
