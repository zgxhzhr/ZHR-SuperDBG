package io.github.zgxhzhr.superdbg.guard;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import io.github.zgxhzhr.superdbg.mixin.EntityLookupAccessor;
import io.github.zgxhzhr.superdbg.mixin.EntityTickListAccessor;
import io.github.zgxhzhr.superdbg.mixin.PersistentEntitySectionManagerAccessor;
import io.github.zgxhzhr.superdbg.mixin.ServerLevelAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 守卫容器完整性校验：清除模组若反射把管理器内部字段重新赋值为原始容器
 * （绕过我们的守卫包装），在 ServerLevel 每 tick HEAD 检测并立即重新包装，
 * 最长一个 tick 的窗口。同时恢复被反射置 NULL 的守卫实体 levelCallback。
 */
public final class GuardIntegrity {

    private GuardIntegrity() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void verify(ServerLevel level) {
        PersistentEntitySectionManager<?> manager =
                ((ServerLevelAccessor) level).superdbg$getEntityManager();
        if (manager == null) {
            return;
        }
        PersistentEntitySectionManagerAccessor managerAccessor =
                (PersistentEntitySectionManagerAccessor) manager;

        // 1. knownUuids 必须是 GuardedUuidSet
        Set<UUID> knownUuids = managerAccessor.superdbg$getKnownUuids();
        if (!(knownUuids instanceof GuardedUuidSet)) {
            managerAccessor.superdbg$setKnownUuids(new GuardedUuidSet(knownUuids));
            io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] knownUuids 守卫包装被替换，已重新包装");
        }

        // 2. visibleEntityStorage.byId / byUuid 必须是守卫容器
        EntityLookup<?> lookup = managerAccessor.superdbg$getVisibleEntityStorage();
        if (lookup != null) {
            EntityLookupAccessor lookupAccessor = (EntityLookupAccessor) lookup;
            Object byId = lookupAccessor.superdbg$getById();
            if (!(byId instanceof GuardedInt2ObjectMap)) {
                lookupAccessor.superdbg$setById(new GuardedInt2ObjectMap((Int2ObjectMap) byId));
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] EntityLookup.byId 守卫包装被替换，已重新包装");
            }
            Object byUuid = lookupAccessor.superdbg$getByUuid();
            if (!(byUuid instanceof GuardedUuidMap)) {
                lookupAccessor.superdbg$setByUuid(new GuardedUuidMap((Map<UUID, ?>) byUuid));
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] EntityLookup.byUuid 守卫包装被替换，已重新包装");
            }
        }

        // 3. entityTickList.active 必须是守卫容器
        EntityTickList tickList = ((ServerLevelAccessor) level).superdbg$getEntityTickList();
        if (tickList != null) {
            EntityTickListAccessor tickAccessor = (EntityTickListAccessor) tickList;
            if (!(tickAccessor.superdbg$getActive() instanceof GuardedInt2ObjectMap)) {
                tickAccessor.superdbg$setActive(new GuardedInt2ObjectMap(tickAccessor.superdbg$getActive()));
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] EntityTickList.active 守卫包装被替换，已重新包装");
            }
        }

        // 4. 守卫实体 levelCallback 自愈（反射置 NULL 无法 Mixin 拦截）
        for (LivingEntity entity : RemovalGuard.getGuardedEntities()) {
            if (entity != null && entity.level() == level) {
                RemovalGuard.restoreLevelCallbackIfNull(entity);
            }
        }
    }
}
