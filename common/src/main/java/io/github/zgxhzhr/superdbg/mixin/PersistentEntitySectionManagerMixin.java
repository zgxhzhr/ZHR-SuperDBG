package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import io.github.zgxhzhr.superdbg.guard.GuardedUuidSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PersistentEntitySectionManager Mixin：三重职责。
 * <p>
 * 1) <b>守卫容器</b>：构造完成后把 knownUuids（SRG f_157491_）替换为
 *    {@link GuardedUuidSet}。配合 {@link EntityLookupMixin}（byId/byUuid）、
 *    {@link EntityTickListMixin}（active）、{@link ClassInstanceMultiMapMixin}
 *    （section 存储），清除模组反射直写管理器内部容器的删除手段全部被门禁拦截。
 * <p>
 * 2) <b>强制移除（编辑器）</b>：在管理器 tick HEAD 对 FORCE_REMOVE 实体执行完整的
 *    字段级摘除（即原版实体卸载流程），绕过 Boss 对 isRemoved() 的覆写。
 *    <b>必须在实体原位置执行</b>——getSection
 *    依赖实体当前坐标，传送到未加载区块会导致 section 为 null 而残留。
 *    流程：section 摘除 → stopTicking → stopTracking（chunkSource + visibleStorage）
 *    → scoreboard → knownUuids → levelCallback=NULL → removeSectionIfEmpty。
 * <p>
 * 3) <b>方法级守卫</b>：拦截 stopTicking / stopTracking，非白名单调用方删除守卫实体时取消。
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin<T extends EntityAccess> {

    @Shadow
    @Final
    @Mutable
    Set<UUID> knownUuids;

    @Shadow
    @Final
    EntitySectionStorage<T> sectionStorage;

    @Shadow
    abstract void stopTracking(T entity);

    @Shadow
    abstract void stopTicking(T entity);

    @Shadow
    abstract void removeSectionIfEmpty(long sectionPos, EntitySection<T> section);

    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void superdbg$wrapKnownUuids(CallbackInfo ci) {
        if (!(this.knownUuids instanceof GuardedUuidSet)) {
            this.knownUuids = new GuardedUuidSet(this.knownUuids);
        }
    }

    /** fullDelete 连续失败计数：超过上限放弃重试并清理标记，避免每 tick 刷日志 */
    private static final java.util.Map<UUID, Integer> FAIL_COUNTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_FAILS = 100;

    /** "世界持有但本管理器 section 找不到"异常形态诊断计数（每 UUID 每 40 tick 一条） */
    private static final java.util.Map<UUID, Integer> MISS_LOG_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 已开始处理的 UUID（成功路径只记一条） */
    private static final java.util.Set<UUID> START_LOGGED = ConcurrentHashMap.newKeySet();

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1)
    private void superdbg$forceRemoveTick(CallbackInfo ci) {
        if (!RemovalGuard.getForceRemoveEntities().isEmpty()) {
            for (Entity entity : new ArrayList<>(RemovalGuard.getForceRemoveEntities())) {
                this.superdbg$processForceRemove(entity);
            }
        }
        // 合法离场（收魂/死亡）兜底：levelCallback 断链等原因导致原版 onRemove 未跑完时，
        // knownUuids/byId/byUuid 残留 → 魂符放出报 UUID already exists，
        // tracking 未销毁 → 客户端只剩实体阴影。检测到残留即用 fullDelete 补完摘除。
        this.superdbg$processDismissedLeftovers();
    }

    @SuppressWarnings("unchecked")
    private void superdbg$processDismissedLeftovers() {
        java.util.List<Entity> dismissed = RemovalGuard.getDismissedSnapshot();
        if (dismissed.isEmpty()) {
            return;
        }
        for (Entity old : dismissed) {
            if (!(old.level() instanceof ServerLevel sl)) {
                continue;
            }
            // 只处理归属于本管理器的实体（每个维度各有一个管理器）
            Object manager = ((ServerLevelAccessor) sl).superdbg$getEntityManager();
            if (manager != (Object) this) {
                continue;
            }
            UUID uuid = old.getUUID();
            // visibleStorage 中该 UUID 当前的持有者：null=孤儿键 / ==old=旧实例独占 / 其它=新实例接管
            net.minecraft.world.level.entity.EntityLookup<Entity> lookup =
                    ((PersistentEntitySectionManagerAccessor) this).superdbg$getVisibleEntityStorage();
            Object holderObj = lookup != null
                    ? ((EntityLookupAccessor) lookup).superdbg$getByUuid().get(uuid)
                    : null;
            Entity holder = holderObj instanceof Entity held ? held : null;
            boolean knownUuidsLeft = knownUuids != null && knownUuids.contains(uuid);
            // 键已无残留也必须做对象级清理（stopTicking/untrack/销毁包）：
            // onRemove 的 stopTicking/stopTracking 因 visibility 降级可能被跳过，
            // ChunkMap.trackedEntities / entityTickList 的残留会造成客户端阴影、
            // 气泡仍发送、并污染同 UUID 新实体的模型数据（透明/默认名）。
            if (holder != old && !knownUuidsLeft) {
                RemovalGuard.runWithoutGuard(() -> superdbg$dismissedCleanup(old, sl, false));
                RemovalGuard.finishDismissed(old);
                continue;
            }
            try {
                // UUID 键清理条件：无新实例接管（孤儿 / 旧实例独占）→ 全清；
                // 有新实例（放出/重建）接管 → 只清旧实例"对象专属"痕迹，
                // 绝不动共享的 UUID 键——否则会误删新实例的入册索引引发重建死循环。
                boolean withUuidKeys = holder == null || holder == old;
                RemovalGuard.runWithoutGuard(() -> superdbg$dismissedCleanup(old, sl, withUuidKeys));
                RemovalGuard.finishDismissed(old);
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[SuperDbg] 离场实体管理器残留清理完成: {} uuid={} knownUuids残留={} uuid键持有者={} callback={} 清UUID键={}",
                        old, uuid, knownUuidsLeft,
                        holder == null ? "孤儿" : (holder == old ? "旧实例" : "新实例接管"),
                        RemovalGuard.describeLevelCallback(old), withUuidKeys);
            } catch (Exception e) {
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[SuperDbg] 离场实体兜底清理失败: {} uuid={}", old, uuid, e);
            }
        }
    }

    /**
     * 已合法离场实体的残留清理（<b>纯按对象</b>，except 显式判定为孤儿/独占的 UUID 键）。
     * 与 {@link #superdbg$fullDelete} 的区别：后者用于编辑器强删（实体独占 UUID，可全删），
     * 这里必须容忍"同 UUID 新实例已入册"的场景，只摘旧实例自身。
     */
    @SuppressWarnings("unchecked")
    private void superdbg$dismissedCleanup(Entity old, ServerLevel sl, boolean withUuidKeys) {
        UUID uuid = old.getUUID();
        // 1. 从 section 的 ClassInstanceMultiMap 摘除（按对象，守卫容器 BYPASS 放行）
        long sectionPos = SectionPos.asLong(old.blockPosition());
        EntitySection<T> section = sectionStorage.getSection(sectionPos);
        if (section != null) {
            section.remove((T) old);
        }
        // 2. 停止 tick（按对象）
        this.stopTicking((T) old);
        // 3. 摘除服务端追踪并广播销毁包（客户端阴影根除；按对象/实体ID，不碰 UUID 键）
        try {
            net.minecraft.server.level.ServerChunkCache chunkSource = sl.getChunkSource();
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<Object> tracked =
                    (it.unimi.dsi.fastutil.ints.Int2ObjectMap<Object>)
                            CHUNK_TRACKED.get(chunkSource.chunkMap);
            Object te = tracked.remove(old.getId());
            net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket packet =
                    new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(old.getId());
            if (te != null) {
                TRACKED_BROADCAST.invoke(te, packet);
            } else {
                // 追踪条目缺失（visibility 降级导致 stopTracking 被跳过等）也要广播销毁包，
                // 否则客户端残留的旧渲染会污染同 UUID 新实体的模型数据
                for (net.minecraft.server.level.ServerPlayer player : sl.players()) {
                    player.connection.send(packet);
                }
            }
        } catch (Exception e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 摘除离场实体追踪失败: {}", old, e);
        }
        // 4. 记分板实体移除（按对象）
        sl.getScoreboard().entityRemoved(old);
        // 5. UUID 键清理：仅当 byUuid 无新实例持有（孤儿或旧实例独占）时
        if (withUuidKeys) {
            net.minecraft.world.level.entity.EntityLookup<Entity> lookup =
                    ((PersistentEntitySectionManagerAccessor) this).superdbg$getVisibleEntityStorage();
            if (lookup != null) {
                java.util.Map<UUID, ?> byUuid =
                        ((EntityLookupAccessor) lookup).superdbg$getByUuid();
                if (byUuid.get(uuid) == old) {
                    byUuid.remove(uuid);
                }
                @SuppressWarnings("rawtypes")
                it.unimi.dsi.fastutil.ints.Int2ObjectMap byId =
                        ((EntityLookupAccessor) lookup).superdbg$getById();
                byId.remove(old.getId());
            }
            if (knownUuids != null) {
                knownUuids.remove(uuid);
            }
        }
        // 6. 断开回调
        old.setLevelCallback(EntityInLevelCallback.NULL);
        // 7. 空 section 清理
        if (section != null) {
            this.removeSectionIfEmpty(sectionPos, section);
        }
    }

    /** ChunkMap.trackedEntities（SRG f_140150_）：实体ID → TrackedEntity */
    private static final java.lang.reflect.Field CHUNK_TRACKED;
    /** TrackedEntity.broadcast(Packet)（SRG m_140489_）：向所有可见玩家发包 */
    private static final java.lang.reflect.Method TRACKED_BROADCAST;

    static {
        java.lang.reflect.Field f = null;
        java.lang.reflect.Method m = null;
        try {
            f = net.minecraft.server.level.ChunkMap.class.getDeclaredField("f_140150_");
            f.setAccessible(true);
            m = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity")
                    .getDeclaredMethod("m_140489_", net.minecraft.network.protocol.Packet.class);
            m.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.error("[SuperDbg] 初始化离场清理反射失败", e);
        }
        CHUNK_TRACKED = f;
        TRACKED_BROADCAST = m;
    }

    /**
     * addNewEntity 冲突自愈：守卫女仆收容后 knownUuids/byUuid 若有残留（onRemove 断链等），
     * 魂符放出的新女仆（同 UUID）入册会被原版拒绝（"UUID of added entity already exists"）。
     * 判定旧持有者确已离场（DISMISSED 在册 / 近期已完成清理）→ 现场清理残留键并放行入册。
     */
    @Inject(method = "addNewEntity(Lnet/minecraft/world/level/entity/EntityAccess;)Z",
            at = @At("HEAD"), require = 1)
    private void superdbg$healDuplicateUuid(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof Entity incoming)) {
            return;
        }
        UUID uuid = incoming.getUUID();
        if (knownUuids == null || !knownUuids.contains(uuid)) {
            return;
        }
        // visibleStorage 中该 UUID 的当前持有者
        net.minecraft.world.level.entity.EntityLookup<Entity> lookup =
                ((PersistentEntitySectionManagerAccessor) this).superdbg$getVisibleEntityStorage();
        Object holderObj = lookup != null
                ? ((EntityLookupAccessor) lookup).superdbg$getByUuid().get(uuid)
                : null;
        LivingEntity old = RemovalGuard.getDismissedSnapshotEntity(uuid);
        boolean holderStale = holderObj == null
                || (holderObj instanceof LivingEntity le
                    && (RemovalGuard.isDismissed(le) || le.isRemoved()));
        // 旧持有者已离场（或在册待清 / 近期已清理过）才放行；否则是真实的 UUID 占用冲突
        if (!holderStale && old == null && !RemovalGuard.isRecentlyFinished(uuid)) {
            return;
        }
        try {
            // 持有者是已离场守卫实体 → 顺带完成其对象级清理
            if (holderObj instanceof LivingEntity le && RemovalGuard.isDismissed(le)
                    && le.level() instanceof ServerLevel sl
                    && ((ServerLevelAccessor) sl).superdbg$getEntityManager() == (Object) this) {
                RemovalGuard.runWithoutGuard(() -> superdbg$dismissedCleanup(le, sl, false));
                RemovalGuard.finishDismissed(le);
            }
            // 清残留键（byUuid 持有者确认已 stale，knownUuids 键同步清除）放行入册
            RemovalGuard.runWithoutGuard(() -> {
                if (lookup != null) {
                    ((EntityLookupAccessor) lookup).superdbg$getByUuid().remove(uuid);
                    if (holderObj instanceof Entity held) {
                        ((EntityLookupAccessor) lookup).superdbg$getById().remove(held.getId());
                    }
                }
                if (knownUuids != null) {
                    knownUuids.remove(uuid);
                }
            });
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 入册冲突放行：清理已离场旧实例残留 uuid={} holder={} incoming={}",
                    uuid, holderObj, incoming);
        } catch (Exception e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 入册冲突放行清理失败 uuid={}", uuid, e);
        }
    }

    private void superdbg$processForceRemove(Entity entity) {
            UUID uuid = entity.getUUID();
            if (!(entity.level() instanceof ServerLevel sl)) {
                RemovalGuard.notifyForceRemoveCompleted(entity);
                FAIL_COUNTS.remove(uuid);
                return;
            }
            // 权威归属判据：实体当前坐标所在的 section 是否由本管理器实际持有。
            // 不能只看 knownUuids——某些模组的召唤/复活链可能制造出
            // 索引与管理器状态不一致的实体，只认 knownUuids 会永远静默跳过。
            long sectionPos = SectionPos.asLong(entity.blockPosition());
            EntitySection<T> section = sectionStorage.getSection(sectionPos);
            boolean inThisManager = false;
            if (section != null) {
                final Entity probe = entity;
                try {
                    inThisManager = section.getEntities().anyMatch(ea -> ea == probe);
                } catch (Exception ignored) {
                    // 容器被并发修改等异常：按未持有处理，下一 tick 重试
                }
            }
            boolean worldHolds = sl.getEntity(uuid) == entity;
            if (!inThisManager) {
                if (!worldHolds) {
                    // 本管理器不持有、世界索引也找不到：已被别的流程删除，清理标记
                    RemovalGuard.notifyForceRemoveCompleted(entity);
                    FAIL_COUNTS.remove(uuid);
                    MISS_LOG_COUNT.remove(uuid);
                } else {
                    // 世界索引持有但本管理器 section 未找到（可能在别的维度管理器，
                    // 或是索引/管理器不一致的异常实体）：节流记录现场，继续等待
                    int n = MISS_LOG_COUNT.merge(uuid, 1, Integer::sum);
                    if (n == 1 || n % 40 == 0) {
                        io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                                "[SuperDbg] 强删等待: 世界持有但本管理器 section 未找到: {} uuid={} "
                                        + "pos=({},{},{}) knownUuids={} sectionNull={} dim={}",
                                entity, uuid, entity.getX(), entity.getY(), entity.getZ(),
                                knownUuids != null && knownUuids.contains(uuid),
                                section == null, sl.dimension().location());
                    }
                }
                return;
            }
            if (START_LOGGED.size() < 1024 && START_LOGGED.add(uuid)) {
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[SuperDbg] 管理器 tick 开始强删: {} uuid={} knownUuids={} worldHolds={}",
                        entity, uuid,
                        knownUuids != null && knownUuids.contains(uuid), worldHolds);
            }
            try {
                // 高频高伤害补刀（编辑器强删的兜底清除手段）：对覆写字段手术/死亡免疫
                // 的 Boss 先走一轮杀伤，再做字段级摘除，两路径任一成功即可让其消失
                RemovalGuard.damageToKill(entity);
                // BYPASS + FORCE_REMOVE 双保险：所有守卫容器/方法拦截直接放行
                RemovalGuard.runWithoutGuard(() -> superdbg$fullDelete(entity, section, sectionPos));
                FAIL_COUNTS.remove(uuid);
                MISS_LOG_COUNT.remove(uuid);
                // 删除成功后立刻摘复活名册（必须在第三方复活 tick 之前）
                io.github.zgxhzhr.superdbg.compat.ThirdPartyBossCompat.afterFullDelete(entity);
                RemovalGuard.notifyForceRemoveCompleted(entity);
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[SuperDbg] 管理器 tick 完整字段级删除: {} uuid={}",
                        entity, uuid);
            } catch (Exception e) {
                int fails = FAIL_COUNTS.merge(uuid, 1, Integer::sum);
                if (fails >= MAX_FAILS) {
                    FAIL_COUNTS.remove(uuid);
                    RemovalGuard.notifyForceRemoveCompleted(entity);
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                            "[SuperDbg] 完整删除连续失败 {} 次，放弃并清理标记: {} uuid={}",
                            fails, entity, uuid, e);
                } else if (fails == 1 || fails % 20 == 0) {
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                            "[SuperDbg] 管理器 tick 完整删除失败({}/{}): {} uuid={}",
                            fails, MAX_FAILS, entity, uuid, e);
                }
            }
    }

    /** 完整的字段级删除流程（原版实体卸载步骤）。
     *  必须由 FORCE_REMOVE/BYPASS 上下文调用，守卫容器全部放行。
     *  <b>在实体原位执行</b>：section 由调用方按实体当前 blockPosition 查好后传入。 */
    @SuppressWarnings("unchecked")
    private void superdbg$fullDelete(Entity entity, EntitySection<T> section, long sectionPos) {
        // 1. 从区块 section 的 ClassInstanceMultiMap 摘除（守卫容器，BYPASS 放行）
        if (section != null) {
            section.remove((T) entity);
        }
        // 2. 停止 tick（onTickingEnd → entityTickList 移除）
        this.stopTicking((T) entity);
        // 3. 停止跟踪（onTrackingEnd → chunkSource.removeEntity + visibleEntityStorage 移除）
        this.stopTracking((T) entity);
        // 4. 记分板实体移除（Boss 队伍/计分项清理）
        if (entity.level() instanceof ServerLevel serverLevel) {
            serverLevel.getScoreboard().entityRemoved(entity);
        }
        // 5. knownUuids 移除（stopTracking 不删此集合；实体若本就不在集合中则无操作）
        knownUuids.remove(entity.getUUID());
        // 6. 断开实体与管理器的回调联系（最后一步：此前仍可安全操作各结构）
        entity.setLevelCallback(EntityInLevelCallback.NULL);
        // 7. 空 section 清理
        if (section != null) {
            this.removeSectionIfEmpty(sectionPos, section);
        }
    }

    /**
     * 防复活黑名单（挂原版入口）：第三方模组的同对象复活最终都要经过原版的
     * {@code PersistentEntitySectionManager.addNewEntityWithoutEvent} 入册，
     * 在该原版方法 HEAD 拦截即可，无需注入对方自己的类。
     * 编辑器强删成功后的 UUID 在 {@link RemovalGuard#isRecentlyDeleted} 的
     * TTL（120 秒）内
     * 禁止重新入册。排除玩家（重登同 UUID）。
     */
    // 该方法在 SRG 混淆表中就是原名，无需重映射
    @Inject(method = "addNewEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void superdbg$blockRevive(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Entity e && !(entity instanceof Player)
                && RemovalGuard.isRecentlyDeleted(e.getUUID())) {
            cir.setReturnValue(false);
            UUID uuid = e.getUUID();
            if (REVIVE_LOGGED.size() < 1024 && REVIVE_LOGGED.add(uuid)) {
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[SuperDbg] 拦截实体复活入册（第三方复活机制或其它路径）: {} uuid={}",
                        e, uuid);
            }
        }
    }

    /** 复活拦截日志去重：同 UUID 只记一次（复活机制每 tick 重试会形成高频调用） */
    private static final Set<UUID> REVIVE_LOGGED = ConcurrentHashMap.newKeySet();

    // 拦截 stopTracking：对防移除实体取消非白名单调用方的删除
    @Inject(method = "stopTracking(Lnet/minecraft/world/level/entity/EntityAccess;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$guardStopTracking(T entity, CallbackInfo ci) {
        superdbg$guardRemovalOp(entity, ci, "stopTracking");
    }

    // 拦截 stopTicking：防止清除模组通过 stopTicking 把防移除实体从 tick 列表移除
    @Inject(method = "stopTicking(Lnet/minecraft/world/level/entity/EntityAccess;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$guardStopTicking(T entity, CallbackInfo ci) {
        superdbg$guardRemovalOp(entity, ci, "stopTicking");
    }

    private void superdbg$guardRemovalOp(T entity, CallbackInfo ci, String op) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (!RemovalGuard.has(living)) {
            return;
        }
        if (RemovalGuard.isForceRemoved(living) || RemovalGuard.isBypassing()
                || RemovalGuard.isDismissed(living)) {
            return;
        }
        // 死亡动画已在播放（自然死亡收尾）：不拦 stopTicking/stopTracking，
        // 否则实体摘除链断裂，尸体永不消失
        if (living.isDeadOrDying() && living.deathTime > 0) {
            return;
        }
        String illegal = RemovalGuard.findIllegalCaller();
        if (illegal != null) {
            ci.cancel();
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 拦截 {}: uuid={} 非白名单调用方={}",
                    op, entity.getUUID(), illegal);
        }
    }
}
