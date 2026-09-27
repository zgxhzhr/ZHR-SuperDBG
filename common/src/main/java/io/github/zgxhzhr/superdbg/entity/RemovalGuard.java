package io.github.zgxhzhr.superdbg.entity;

import java.lang.reflect.Field;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 防移除（激进封死版）：打上标记后，守卫实体对除本模组之外的任何方
 * 「只读不可改」——等同于把关键字段对其它模组设成 private：
 * <ul>
 *   <li>setRemoved / remove / discard：一律拦截（正常死亡由 DYING 放行，
 *       区块卸载 UNLOADED 放行，TLM 收魂由 {@link #isLegitRemoval} 白名单放行）</li>
 *   <li>{@code isRemoved()} / {@code getRemovalReason()}：接管返回"未移除"，
 *       世界管理器永远不摘除它（即便对方反射改字段）</li>
 *   <li><b>PersistentEntitySectionManager.stopTicking / stopTracking</b>：拦截非白名单调用方
 *       （{@link io.github.zgxhzhr.superdbg.mixin.PersistentEntitySectionManagerMixin}）。
 *       <p>经验：清除模组可绕过 {@code setRemoved} 拦截，直接反射调用管理器的
 *       stopTicking/stopTracking 把实体从 {@code entityTickList}/{@code visibleEntityStorage}
 *       中删除。完整删除流程是三步：stopTicking + stopTracking + knownUuids.remove，
 *       缺任一步都会让实体残留（仅 stopTracking 时实体仍在 entityTickList 继续走路）。</li>
 *   <li>大幅瞬移 / 跨维度：直接取消（正常寻路小移动不受影响）</li>
 *   <li>异常伤害（秒杀型）/ genericKill / 虚空：免疫或限幅</li>
 * </ul>
 * 存储策略：{@code persistentData} 持久化 + {@code SyncedEntityData} 同步客户端（Jade 显示）。
 * <p>对覆写 {@code isRemoved()} 不调用 super 的 Boss：
 * {@code RemovalGuardMixin} 的 isRemoved @Inject 不触发，但 Boss 自身返回 false 已等于
 * "防移除"——管理器 tick 永远不判定它移除。此时若要主动移除，必须通过
 * {@link io.github.zgxhzhr.superdbg.mixin.PersistentEntitySectionManagerMixin} 在管理器 tick HEAD 直接
 * 调用完整删除流程绕过 isRemoved() 检查。
 */
public final class RemovalGuard {

    private static final String KEY = "superdbg_removal_guard";
    /** 旧版（改名前）守卫标记：读取时兼容，写入时自动迁移到 {@link #KEY} */
    private static final String LEGACY_KEY = "tiaoshi_removal_guard";

    /** 真 UUID 封存键：守卫期间对外返回假 UUID，真值存这里，关闭/收魂/读档时还原 */
    private static final String KEY_REAL_UUID = "superdbg_guard_real_uuid";
    /** 旧版真 UUID 封存键，读档兼容 */
    private static final String LEGACY_KEY_REAL_UUID = "tiaoshi_guard_real_uuid";

    /** 大幅瞬移拦截阈值：守卫实体被强制位移超过该距离（格）视为"传送出了 nothing/跨区" */
    public static final double TELEPORT_THRESHOLD = 512.0D;

    /** 异常位置阈值：离原点 1e7 格外视为被"Teleport into nothing" */
    private static final double ABNORMAL_POS = 10_000_000.0D;

    /**
     * SyncedEntityData 访问器，由 {@link io.github.zgxhzhr.superdbg.mixin.LivingEntityMixin} 在
     * {@code defineSynchedData} 中通过 {@code defineId} 创建并合并到 LivingEntity 类中。
     */
    private static EntityDataAccessor<Boolean> dataAccessor;

    /** 线程局部绕过标记：为 true 时守卫不拦截，供本模组内部操作使用 */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    /** 线程局部"当前收容交互的玩家"：EntityInteract 事件登记，discard/remove 拦截时精确提示。
     *  跨模块安全（common/forge 都能引用）。 */
    private static final ThreadLocal<net.minecraft.world.entity.player.Player> CAPTURE_BLOCKER = new ThreadLocal<>();

    /** 登记/清除/查询当前发起收容交互的玩家（供 PlayerInteractHandler ↔ RemovalGuardMixin 通信）。 */
    public static void setCaptureBlocker(net.minecraft.world.entity.player.Player p) { CAPTURE_BLOCKER.set(p); }
    public static net.minecraft.world.entity.player.Player getCaptureBlocker() { return CAPTURE_BLOCKER.get(); }
    public static void clearCaptureBlocker() { CAPTURE_BLOCKER.remove(); }

    /**
     * 线程局部"正常死亡中"标记：
     * {@link LivingEntity#die(net.minecraft.world.damagesource.DamageSource)} HEAD 置 true、RETURN/finally 置 false，
     * 用于区分"die() 内部的 setRemoved(KILLED)/discard（允许）"与"外部直接 remove（拦截）"。
     */
    public static final ThreadLocal<Boolean> DYING = ThreadLocal.withInitial(() -> false);

    /** TLM 收魂白名单：玩家手持空魂符（smart_slab）右键女仆时刻的实体 ID → 时间戳。
     *  必须用强引用键的 CHM：曾用 WeakHashMap，装箱 Integer 无强引用被 GC 后标记随机失效。 */
    private static final java.util.Map<Integer, Long> LEGIT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 已合法放行离场的实体实例集合（TLM 魂符/照片收容的 discard、自然死亡的 KILLED、
     * 万法皆通等白名单模组的合法移除）。
     * <p>
     * 背景：守卫实体的 {@code isRemoved()} 被接管恒 false（防外部直写 removed 摘除），
     * 仅"不 cancel remove/discard"不够——管理器读 isRemoved()=false 不会执行摘除，
     * 实体变成 removed 字段已置位但永不离场的幽灵（客户端残影、编辑器打不开、
     * 同 UUID 占位导致魂符放出的新女仆加入即消失）。合法离场一经判定即登记本集合：
     * isRemoved/getRemovalReason 透传真值、全部容器门禁开口，让管理器完成真实摘除。
     * <p>
     * 按实体对象登记（不按 UUID：语义是"这一个实例的生命周期已合法结束"），
     * 弱键随实体 GC 自动清理。实体上的持久守卫标记<b>保留</b>——魂符 NBT 在 discard
     * 之前已写出，放出时是带标记的新实例，readAdditionalSaveData 会重新武装。
     */
    private static final java.util.Set<Entity> DISMISSED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** 登记实体实例合法离场（仅在移除入口判定"非异常"后调用，幂等） */
    public static void markDismissed(Entity entity) {
        if (entity != null && DISMISSED.add(entity)) {
            DISMISSED_UUIDS.put(entity.getUUID(), System.currentTimeMillis());
            io.github.zgxhzhr.superdbg.Constants.LOG.debug(
                    "[SuperDbg] 守卫实体合法离场登记: entity={}, reason={}, levelCallback={}",
                    entity, readReasonField(entity), describeLevelCallback(entity));
        }
    }

    /** 该实例是否已合法放行离场（isRemoved 透传 / 容器门禁开口 / 守护不再干预） */
    public static boolean isDismissed(Entity entity) {
        return entity != null && DISMISSED.contains(entity);
    }

    /** 已登记离场的实体快照（供管理器 tick 检测摘除残留并补刀） */
    public static java.util.List<Entity> getDismissedSnapshot() {
        synchronized (DISMISSED) {
            return new java.util.ArrayList<>(DISMISSED);
        }
    }

    /** 按 UUID 查已登记离场的实体实例（供入册冲突自愈反查旧持有者） */
    public static LivingEntity getDismissedSnapshotEntity(java.util.UUID uuid) {
        synchronized (DISMISSED) {
            for (Entity e : DISMISSED) {
                if (uuid.equals(e.getUUID()) && e instanceof LivingEntity living) {
                    return living;
                }
            }
        }
        return null;
    }

    /** 离场清理完成：移出 DISMISSED/UUID 表/守卫名册（防逐 tick 重复处理） */
    public static void finishDismissed(Entity entity) {
        if (entity == null) {
            return;
        }
        DISMISSED.remove(entity);
        DISMISSED_UUIDS.remove(entity.getUUID());
        FINISHED_UUIDS.put(entity.getUUID(), System.currentTimeMillis());
        removeFromGuards(entity);
    }

    /** 已完成离场清理的 UUID（120 秒 TTL）：供入册冲突自愈识别"旧持有者已离场" */
    private static final java.util.Map<java.util.UUID, Long> FINISHED_UUIDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long FINISHED_TTL_MS = 120_000L;

    /** 该 UUID 是否在近期完成过离场清理（放出女仆入册冲突时的放行判据） */
    public static boolean isRecentlyFinished(java.util.UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long t = FINISHED_UUIDS.get(uuid);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t > FINISHED_TTL_MS) {
            FINISHED_UUIDS.remove(uuid);
            return false;
        }
        return true;
    }

    /** 读取实体当前 levelCallback 的类名（诊断摘除回调是否断链为 NULL） */
    public static String describeLevelCallback(Entity entity) {
        try {
            Field f = levelCallbackField();
            if (f == null) {
                return "unknown";
            }
            Object cb = f.get(entity);
            if (cb == null) {
                return "null";
            }
            if (cb == net.minecraft.world.level.entity.EntityInLevelCallback.NULL) {
                return "NULL";
            }
            return cb.getClass().getName();
        } catch (Exception e) {
            return "err:" + e;
        }
    }

    /** 离场实例的真实 UUID → 登记时间（供 UUID 级容器门禁开口，60 秒后自动过期） */
    private static final java.util.Map<java.util.UUID, Long> DISMISSED_UUIDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long DISMISSED_UUID_TTL_MS = 60_000L;

    /** UUID 是否在离场登记 TTL 内（供容器诊断/门禁使用） */
    public static boolean isDismissedUuidPublic(java.util.UUID uuid) {
        return isDismissedUuid(uuid);
    }

    private static boolean isDismissedUuid(java.util.UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long t = DISMISSED_UUIDS.get(uuid);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t > DISMISSED_UUID_TTL_MS) {
            DISMISSED_UUIDS.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * 世界卸载时清理该维度相关的全部 static 状态。
     * <p>
     * 退出世界/重进存档时，旧 {@link net.minecraft.server.level.ServerLevel} 销毁，
     * 但 SNAPSHOTS/TICKET_ANCHORS/LAST_KNOWN 等强引用集合仍持有旧实体，若不清理：
     * <ul>
     *   <li>新实体 set() 时 updateChunkTicket 对旧 level 调 setChunkForced 可能抛异常中断武装</li>
     *   <li>新旧实体同 UUID 在 SNAPSHOTS 中混淆，容器门禁/位置跟踪锚到旧实例</li>
     *   <li>FINISHED_UUIDS 残留导致重进实体被 blockRevive 拒绝入册</li>
     * </ul>
     * 由 forge 侧 {@code LevelEvent.Unload} 触发（每个维度卸载各调一次）。
     */
    public static void onLevelUnload(net.minecraft.server.level.ServerLevel level) {
        if (level == null) {
            return;
        }
        // 1. 释放该维度全部常加载票据并清理锚点
        TICKET_ANCHORS.entrySet().removeIf(e -> {
            if (e.getValue().level() == level) {
                TicketAnchor a = e.getValue();
                int left = TICKET_REFS.merge(a, -1, Integer::sum);
                if (left <= 0) {
                    TICKET_REFS.remove(a);
                }
                return true;
            }
            return false;
        });
        // 2. 清理强引用快照（旧实体随 SNAPSHOTS 释放后可被 GC，WeakHashMap 条目自动清理）
        SNAPSHOTS.entrySet().removeIf(e -> {
            LivingEntity le = e.getValue();
            return le != null && le.level() == level;
        });
        // 3. 按 UUID 索引的状态：UUID 可能在新世界重用，一律清掉
        LAST_KNOWN.clear();
        LAST_TICK_COUNT.clear();
        DISMISSED_UUIDS.clear();
        FINISHED_UUIDS.clear();
        FORCE_REMOVE.clear();
        // 4. 按实体对象的 WeakHashMap 无需主动清理（SNAPSHOTS 释放后旧实体可 GC）
        io.github.zgxhzhr.superdbg.Constants.LOG.info("[SuperDbg] 世界卸载清理完成: level={} 剩余快照={}",
                level.dimension().location(), SNAPSHOTS.size());
    }

    /** 白名单有效期：右键后的 3 秒内，TLM 完成"存档 NBT → remove 实体" */
    private static final long LEGIT_WINDOW_MS = 3000L;

    /** 守卫实体的原位基准：打标记时记录，被传送到 nothing/虚空/其它维度时拉回 */
    private static final java.util.Map<Entity, net.minecraft.core.BlockPos> BASE_POS =
            new java.util.WeakHashMap<>();

    /** 守卫实体标记时的维度键（防 leave-level 传送到其它维度） */
    private static final java.util.Map<Entity, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> BASE_DIM =
            new java.util.WeakHashMap<>();

    private RemovalGuard() {
    }

    // ---- 反射缓存：避免每 tick getDeclaredField/setAccessible 的高开销（曾导致女仆 AI 卡顿/动画异常） ----

    private static Field removedField;
    private static Field reasonField;

    private static Field removedField() {
        if (removedField == null) {
            try {
                removedField = Entity.class.getDeclaredField("f_19861_");
                removedField.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                removedField = null;
            }
        }
        return removedField;
    }

    private static Field reasonField() {
        if (reasonField == null) {
            try {
                reasonField = Entity.class.getDeclaredField("f_146795_");
                reasonField.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                reasonField = null;
            }
        }
        return reasonField;
    }

    // ---- 调用栈白名单判别（借鉴 maidspell 锚定核心）----

    /**
     * 调用方白名单：栈帧类名命中前缀即视为"受信任的流程"（原版 / Forge / 模组自身 /
     * TLM / maidspell / 后勤库），其余（如 Entity Remover）视为异常清除调用方。
     */
    public static boolean isCallerAllowed(String className) {
        return className.startsWith("net.minecraft")
                || className.startsWith("net.minecraftforge")
                || className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.reflect")
                || className.startsWith("jdk.internal.reflect")
                || className.startsWith("it.unimi.dsi")
                || className.startsWith("com.google")
                || className.startsWith("com.mojang")
                || className.startsWith("io.redspace")
                || className.startsWith("whocraft")
                || className.startsWith("top.theillusivec4.curios")
                || className.startsWith("tschipp.carryon")
                || className.startsWith("dev.xkmc.l2")               // 莱特兰库
                || className.startsWith("net.js03")                  // extra_enchantments
                || className.startsWith("io.github.zgxhzhr.superdbg")               // 本模组
                || className.contains("backup")
                || className.contains("c2me");
    }

    /** 扫描调用栈，返回第一个非白名单的类名；全白名单返回 null */
    public static String findIllegalCaller() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (!isCallerAllowed(e.getClassName())) {
                return e.getClassName();
            }
        }
        return null;
    }

    /**
     * 判定"异常移除"：守卫实体被带理由移除，且调用栈中出现非白名单调用方
     * （即 Entity Remover 等清除工具在不经过任何受信任流程的情况下直接 remove）。
     * 全白名单（TLM 自身收魂、原版死亡等）放行，不影响正常流程。
     */
    public static boolean isAbnormalRemoval(LivingEntity living, Entity.RemovalReason reason) {
        if (living == null || !has(living) || isBypassing()) {
            return false;
        }
        // 已合法放行离场：摘除链上的一切后续调用（含管理器容器删除）一律放行
        if (isDismissed(living)) {
            return false;
        }
        if (Boolean.TRUE.equals(DYING.get())) {
            return false; // 原版死亡流程（die 内）放行
        }
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return false; // 区块卸载属于正常生命周期
        }
        if (reason == Entity.RemovalReason.KILLED && living.isDeadOrDying()) {
            // 原版死亡动画正常播完：LivingEntity.tickDeath 在 deathTime==20 自行
            // remove(KILLED)，这是自然死亡唯一的收尾时机，无条件放行。
            // 不能扫调用栈——服务端实体 tick 调度链上只要存在性能/AI 类模组的
            // 非白名单栈帧就会误判，导致尸体永不消失。
            if (living.deathTime >= 20) {
                return false;
            }
            // 动画未播完的 KILLED：清除工具写 0 血伪装死亡，栈帧含其类 → 判异常拦截
            return findIllegalCaller() != null;
        }
        // 死亡动画已在播放（deathTime>0）的实体，无论何种理由移除都视为自然死亡收尾：
        // 守卫只保护"活着"的实体，血已归零、尸体动画开始后再拦只会留下永不消失的尸体。
        if (living.isDeadOrDying() && living.deathTime > 0) {
            return false;
        }
        // 其余 DISCARDED 等：一律看调用栈白名单
        return findIllegalCaller() != null;
    }

    /** 判定实体是否开启了防移除。优先读 SyncedEntityData（客户端可用），回退 persistentData。 */
    public static boolean has(LivingEntity entity) {
        EntityDataAccessor<Boolean> acc = accessor();
        if (acc != null && entity.getEntityData().get(acc)) {
            return true;
        }
        return persistentData(entity).getBoolean(KEY)
                || persistentData(entity).getBoolean(LEGACY_KEY);
    }

    /** 设置防移除状态：同时写 SyncedEntityData（同步客户端）和 persistentData（持久化）。 */
    public static void set(LivingEntity entity, boolean enabled) {
        EntityDataAccessor<Boolean> acc = accessor();
        if (acc != null) {
            entity.getEntityData().set(acc, enabled, true);
        }
        CompoundTag data = persistentData(entity);
        if (enabled) {
            BASE_POS.put(entity, entity.blockPosition());
            if (entity.level() != null) {
                BASE_DIM.put(entity, entity.level().dimension());
            }
            saveLevelCallback(entity);
            GUARDED.add(entity);
            SNAPSHOTS.put(entity.getUUID(), entity);
            updateChunkTicket(entity);
            if (entity.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                LAST_KNOWN.put(entity.getUUID(),
                        new LastKnown(sl, entity.getX(), entity.getY(), entity.getZ()));
            }
            data.putBoolean(KEY, true);
            data.remove(LEGACY_KEY); // 旧版标记迁移到新 key
            // 封存真 UUID：getUUID() 被接管返回假值，真值从字段直接读取（SRG f_19820_），
            // 供存档后读档还原（否则按假 UUID 存盘会丢失真实身份）
            try {
                java.lang.reflect.Field uuidField = Entity.class.getDeclaredField("f_19820_");
                uuidField.setAccessible(true);
                data.putString(KEY_REAL_UUID, uuidField.get(entity).toString());
                data.remove(LEGACY_KEY_REAL_UUID);
            } catch (ReflectiveOperationException ignored) {
            }
        } else {
            BASE_POS.remove(entity);
            BASE_DIM.remove(entity);
            GUARDED.remove(entity);
            SNAPSHOTS.remove(entity.getUUID());
            releaseChunkTicket(entity.getUUID());
            LAST_KNOWN.remove(entity.getUUID());
            LAST_TICK_COUNT.remove(entity.getUUID());
            data.remove(KEY_REAL_UUID);
            data.remove(LEGACY_KEY_REAL_UUID);
            data.remove(KEY);
            data.remove(LEGACY_KEY);
        }
        io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 防移除 set={} on {} -> has={}",
                enabled, entity, has(entity));
    }

    /** 从守卫名册与快照中移除（关闭守卫 / 死亡 / 重建后） */
    private static void removeFromGuards(Entity e) {
        GUARDED.remove(e);
        if (e != null) {
            // 条件删除：名册值可能已被同 UUID 的重建新实体覆盖，仅在值仍是本对象时删除
            SNAPSHOTS.remove(e.getUUID(), e);
            releaseChunkTicket(e.getUUID());
            LAST_KNOWN.remove(e.getUUID());
            LAST_TICK_COUNT.remove(e.getUUID());
        }
    }

    // ---- 常加载票据：守卫实体所在区块保持实体级常加载 ----
    // 目的：让守卫实体根本不经历"随区块卸载"，从根上消除卸载窗口内的所有异常
    // （滞留索引 / 重建死循环 / 存盘副本冲突）。用原版 ForcedChunksSaveData
    // （/forceload 同一机制），实体级 tick、跨重启保持。
    /** 每只守卫实体当前锚定的（维度, 区块），用于移动/跨维度时迁移票据 */
    private record TicketAnchor(net.minecraft.server.level.ServerLevel level,
                                net.minecraft.world.level.ChunkPos pos) {
    }

    private static final java.util.Map<java.util.UUID, TicketAnchor> TICKET_ANCHORS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** (维度, 区块) → 覆盖它的守卫实体数：同区块多实体共享时引用计数，归零才真正卸载 */
    private static final java.util.Map<TicketAnchor, Integer> TICKET_REFS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 刷新守卫实体的常加载票据：区块/维度变化时先释放旧区块再占用新区块 */
    private static void updateChunkTicket(LivingEntity entity) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        net.minecraft.world.level.ChunkPos now = new net.minecraft.world.level.ChunkPos(liveBlockPos(entity));
        TicketAnchor cur = TICKET_ANCHORS.get(entity.getUUID());
        if (cur != null && cur.level() == sl && cur.pos().equals(now)) {
            return;
        }
        if (cur != null) {
            releaseChunkTicket(entity.getUUID());
        }
        TicketAnchor a = new TicketAnchor(sl, now);
        TICKET_REFS.merge(a, 1, Integer::sum);
        sl.setChunkForced(now.x, now.z, true);
        TICKET_ANCHORS.put(entity.getUUID(), a);
    }

    /** 释放守卫实体的常加载票据（同区块引用计数归零才真正解除） */
    private static void releaseChunkTicket(java.util.UUID uuid) {
        TicketAnchor anchor = TICKET_ANCHORS.remove(uuid);
        if (anchor == null) {
            return;
        }
        int left = TICKET_REFS.merge(anchor, -1, Integer::sum);
        if (left <= 0) {
            TICKET_REFS.remove(anchor);
            // level 可能已关闭（退出世界/重进），setChunkForced 可能抛异常，
            // 吞掉避免中断调用方（如 set()/updateChunkTicket）导致实体武装不完整
            try {
                anchor.level().setChunkForced(anchor.pos().x, anchor.pos().z, false);
            } catch (Exception ignored) {
            }
        }
    }

    // ---- 异常瞬移防护：第三方清除武器会把实体先丢到极端坐标再走删除流程 ----

    /** 上一 tick 的合法位置（维度+坐标）：被丢进死区块时拉回的落点 */
    private record LastKnown(net.minecraft.server.level.ServerLevel level, double x, double y, double z) {
    }

    private static final java.util.Map<java.util.UUID, LastKnown> LAST_KNOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 上次慢扫时的 tickCount：检测"区块在 tick 但实体被冻结"（区块归属被反射破坏） */
    private static final java.util.Map<java.util.UUID, Integer> LAST_TICK_COUNT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 异常瞬移判定（由 Entity.setPos Mixin 在位置写入入口调用）：
     * 守卫实体被写入世界边界外坐标（|x/z| 超三千万或高度越界）且调用栈含非白名单帧时，
     * 视为第三方清除武器的"极端坐标传送"副作用，取消该次位置写入。
     * 判据用"目的地越界"而非"位移距离"：女仆跟随主人传送距离再远也在边界内，不误伤。
     */
    public static boolean isAbnormalTeleport(Entity entity, double x, double y, double z) {
        if (!(entity instanceof LivingEntity living) || !has(living)) {
            return false;
        }
        if (isBypassing()) {
            return false;
        }
        if (Math.abs(x) <= 3.0E7 && Math.abs(z) <= 3.0E7 && y >= -256.0 && y <= 4096.0) {
            return false;
        }
        return findIllegalCaller() != null;
    }

    /** 由实体 ID 派生的稳定假 UUID：守卫期间对外（世界/其它模组）的身份标识，
     *  由 {@code getUUID()} 接管返回——真实 UUID 始终保留在字段上，无需改写。 */
    public static java.util.UUID fakeUuid(Entity entity) {
        return java.util.UUID.nameUUIDFromBytes(
                ("superdbg#guard#" + entity.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 从封存处读取真 UUID 并写回字段（存档用假 UUID 存盘，读档后还原真实身份） */
    public static void restoreRealUuid(LivingEntity entity) {
        try {
            var data = persistentData(entity);
            String real = data.getString(KEY_REAL_UUID);
            if (real.isEmpty()) {
                real = data.getString(LEGACY_KEY_REAL_UUID); // 旧版存档兼容
            }
            if (!real.isEmpty()) {
                java.lang.reflect.Field uuidField = Entity.class.getDeclaredField("f_19820_");
                uuidField.setAccessible(true);
                uuidField.set(entity, java.util.UUID.fromString(real));
            }
        } catch (Exception ignored) {
        }
    }

    // ---- TLM 收魂白名单 ----

    /** 车万女仆（Touhou Little Maid）实体类，软检测：未安装 TLM 时保持 null */
    private static Class<?> maidClass;
    private static boolean maidClassResolved;

    /** 是否为车万女仆（软检测，未安装返回 false） */
    private static boolean isMaid(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!maidClassResolved) {
            maidClassResolved = true;
            try {
                maidClass = Class.forName(
                        "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
            } catch (ClassNotFoundException ignored) {
                maidClass = null;
            }
        }
        return maidClass != null && maidClass.isInstance(entity);
    }

    /** 供平台层（Forge 事件）调用：目标是否为 TLM 女仆（软检测） */
    public static boolean isMaidEntity(LivingEntity entity) {
        return isMaid(entity);
    }

    /** 平台层调用：确认玩家正对女仆执行 TLM 收魂（手持空魂符右键），按实体 ID 打标 */
    public static void markLegit(int entityId) {
        LEGIT.put(entityId, System.currentTimeMillis());
    }

    /** 判定当前是否为"TLM 收魂"上下文：白名单窗口内 → 放行 remove 让女仆正常进魂符。
     *  按实体 ID（而非 UUID）判断——守卫期间 getUUID() 返回假值，ID 恒定不受伪造影响。 */
    public static boolean isLegitRemoval(Entity entity) {
        if (!isMaid(entity)) {
            return false;
        }
        Long t = LEGIT.get(entity.getId());
        return t != null && (System.currentTimeMillis() - t) < LEGIT_WINDOW_MS;
    }

    // ---- 轻量 tick 守护（只在检测到异常时修正，不拉锯） ----

    /**
     * 每 tick 兜底（由 {@code LivingEntity.tick} HEAD 注入，服务端）：
     * 仅当守卫实体被绕过方法直接改动关键状态时修正——
     * <ol>
     *   <li>维度漂移（leave-level）→ 拉回原维度原位</li>
     *   <li>异常位置（传送到 1e7 格外 / 虚空）→ 拉回原位</li>
     *   <li>{@code removed} 字段被反射直接置位 → 清除</li>
     * </ol>
     * 正常状态下每次只做廉价检查，不写任何字段 → 无拉锯无副作用。
     */
    public static void lightTickGuard(LivingEntity living) {
        if (living == null || !has(living) || isBypassing()) {
            return;
        }
        // 已合法离场（收魂/死亡）：不清 removed、不干预，让实体正常完成摘除
        if (isDismissed(living)) {
            removeFromGuards(living);
            return;
        }
        // 已死亡实体放行正常清理，不复活尸体
        if (living.isDeadOrDying()) {
            removeFromGuards(living);
            return;
        }
        // 维度漂移
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> baseDim = BASE_DIM.get(living);
        net.minecraft.world.level.Level cur = living.level();
        if (baseDim != null && cur != null && cur.dimension() != baseDim) {
            try {
                net.minecraft.server.MinecraftServer server =
                        ((net.minecraft.server.level.ServerLevel) cur).getServer();
                net.minecraft.server.level.ServerLevel target = server.getLevel(baseDim);
                net.minecraft.core.BlockPos base = BASE_POS.get(living);
                if (target != null && base != null) {
                    // 拉回必须绕过守卫自己的跨维度拦截（changeDimension 会被取消）
                    runWithoutGuard(() -> {
                        if (living instanceof net.minecraft.server.level.ServerPlayer sp) {
                            sp.teleportTo(target, base.getX() + 0.5, base.getY(), base.getZ() + 0.5,
                                    sp.getYRot(), sp.getXRot());
                        } else {
                            living.changeDimension(target);
                            living.teleportTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
                        }
                    });
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        // 异常位置（拉回同样绕过守卫自身传送拦截）
        if (Math.abs(living.getX()) > ABNORMAL_POS || Math.abs(living.getZ()) > ABNORMAL_POS
                || living.getY() < -70.0D) {
            net.minecraft.core.BlockPos base = BASE_POS.get(living);
            double tx = 0, ty = 100, tz = 0;
            if (base != null) {
                tx = base.getX() + 0.5;
                ty = base.getY();
                tz = base.getZ() + 0.5;
            }
            final double fx = tx, fy = ty, fz = tz;
            runWithoutGuard(() -> {
                try {
                    living.level().getChunkAt(net.minecraft.core.BlockPos.containing(fx, fy, fz));
                    living.teleportTo(fx, fy, fz);
                } catch (Exception ignored) {
                }
            });
        }
        // removed 字段被直接置位 → 清除（管理器摘除前恢复正常）
        clearRemovedField(living);
    }

    /** 守卫实体名册（按对象）：供世界层低频存在性监控使用 */
    private static final java.util.Set<Entity> GUARDED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** worldTickGuard 低频计数器（每 20 tick 一次） */
    private static int tickCounter;

    /** 日志节流：每实体最近一次 warn 时间戳 */
    private static final java.util.Map<Entity, Long> LAST_LOG =
            new java.util.WeakHashMap<>();

    /**
     * 世界层每 tick 兜底（由 {@code ServerLevel.tick} HEAD 注入）——轻量版。
     * <p>
     * 只做两件极廉价的事（缓存反射 + 纯坐标比较，不查询世界索引、不做 addNew）：
     * <ol>
     *   <li>位置异常（被直接改坐标到 1e7 格外 / 虚空）→ 拉回原位，
     *       避免滞留未加载区块被正常卸载——这正是 Entity Remover 的清除链路</li>
     *   <li>removed 字段被绕过方法直接置位 → 清除</li>
     * </ol>
     * 不含实体查找/放回等反射查询（曾导致守卫女仆每 tick 被高频反射干扰，
     * 表现为不动/下落动画异常）。被"直接摘索引"且不可见的极端情况暂不做自动恢复，
     * 优先保证守卫实体日常行为完全不受影响。
     */
    public static void worldTickGuard(net.minecraft.server.level.ServerLevel level) {
        if (GUARDED.isEmpty()) {
            return;
        }
        ++tickCounter;
        final boolean lastIgnored = isBypassing();
        // 诊断日志节流：追踪约每秒一条，心跳约每 5 秒一条
        final boolean slowScan = tickCounter % 20 == 0;
        if (tickCounter % 100 == 0) {
            io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 守卫监控运行中: 名册={}", SNAPSHOTS.size());
        }
        for (LivingEntity living : SNAPSHOTS.values().toArray(new LivingEntity[0])) {
            if (!has(living) || lastIgnored) {
                continue;
            }
            // 每个实体只在自己所在的维度处理（每个维度都会调本方法，避免重复检测/重复重建）
            if (living.level() != level) {
                continue;
            }
            Entity e = living;
            // 常加载票据跟随：实体移动/跨维度时迁移常加载区块（引用计数，
            // 同区块多守卫实体共享；移出名册的路径都会 releaseChunkTicket）
            updateChunkTicket(living);
            // 已合法放行离场（收魂/死亡）：移出名册，绝不重建、不清 removed、不拉位置
            if (isDismissed(living)) {
                removeFromGuards(living);
                continue;
            }
            // TLM 收魂流程中：放行一切兜底动作并移出名册，让实体干净进魂符
            if (isLegitRemoval(living)) {
                removeFromGuards(living);
                io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 守卫实体收魂离场，移出监控名册: {}", living);
                continue;
            }
            // 编辑器强制移除中的实体：完全交给管理器 tick 的字段级删除流程，本守护
            // 不做任何状态判断。必须先于 isDeadOrDying——部分第三方 Boss 模组覆写了
            // isDeadOrDying，直写 removed 字段后它可能误报死亡，提前清掉 FORCE_REMOVE
            // 标记会导致管理器永远等不到该实体（Boss 随即自愈复活）。
            if (isForceRemoved(living)) {
                if (isGoneFromWorld(level, e)) {
                    unmarkForceRemoved(living);
                    removeFromGuards(living);
                    io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 强制移除实体已从世界消失，清理标记: {}", living);
                }
                continue;
            }
            // 已被真实移除（reason 字段非空）但未经放行链登记的守卫实体：
            // 同 UUID 混淆（收容命中重建副本）等场景下 TLM 直接 discard 了它，却不会
            // 走 guardDiscard 登记——isRemoved() 接管返回 false 使其变成"幽灵"（AI 继续
            // tick、原地移动）。自动补登记离场，交给管理器 tick 兜底完整清理。
            Object trueReason = readReasonField(e);
            // 注意不排除 isForceRemoved：force 管理的实体若 reason 字段已非空（被真实
            // discard/remove），说明它确实该离场，必须补登记交管理器清理，否则就是幽灵
            if (trueReason != null && !isDismissed(living)) {
                // 区块卸载理由（UNLOADED_TO_CHUNK/UNLOADED_WITH_PLAYER）单独处理：
                // - 所在区块确实不在实体 tick 状态 = 真随区块卸载：实体正随区块存盘，
                //   直接移出名册（重载时 readAdditionalSaveData 尾注按 NBT 守卫标记自动恢复），
                //   绝不补登记+清理——清理会在保存前摘除实体导致存盘副本丢失，且清掉 UUID 键后
                //   worldTickGuard 又判消失 → 重建 → 又被卸载，形成连环重建死循环。
                // - 所在区块仍在实体 tick 状态 = 第三方伪造 UNLOADED 绕过守卫（守卫容器对
                //   UNLOADED 理由放行）：清 removed 字段恢复存活，绝不放行。
                if (trueReason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                        || trueReason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
                    if (!level.isPositionEntityTicking(liveBlockPos(living))) {
                        removeFromGuards(living);
                    } else {
                        clearRemovedField(living);
                    }
                    continue;
                }
                markDismissed(living);
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[SuperDbg] 守卫实体已被真实移除但未登记离场，自动补登记并清理: {} reason={}",
                        living, trueReason);
                continue;
            }
            // 已死亡实体放行正常清理（不复活尸体）
            if (living.isDeadOrDying()) {
                removeFromGuards(living);
                unmarkForceRemoved(living);
                io.github.zgxhzhr.superdbg.Constants.LOG.info("[SuperDbg] 守卫实体处于死亡态，移出监控名册: {}", living);
                continue;
            }
            // blockPosition 缓存自愈：第三方清除武器反射直写 position 字段会把
            // blockPosition 缓存（f_19826_）污染到虚空洞，导致 section 归属/常加载
            // 票据/死区块检测全部锚错位置（实体冻结成"不动的幽灵"）。先修缓存再强制
            // onMove 重新登记。必须在离场/死亡检查之后——onMove 会把实体重新登记进
            // section，对正在离场的实体调用会打断摘除链。
            if (healSectionRegistration(living)) {
                long nowMs = System.currentTimeMillis();
                Long lastHeal = LAST_LOG.get(living);
                if (lastHeal == null || nowMs - lastHeal > 5000) {
                    LAST_LOG.put(living, nowMs);
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                            "[SuperDbg] 守卫实体 blockPosition 缓存被污染，已修复并重新登记区块归属: {} 正确位置={}",
                            living, liveBlockPos(living));
                }
            }
            // 1.5) 异常位移：单 tick 位移超过 128 格，或被丢进不 tick 的死区块
            //      （第三方清除武器的极端坐标副作用）→ 拉回上一 tick 的合法位置。
            //      teleportTo 本身会触发 onMove 重新登记区块归属，兼修归属错乱
            LastKnown lk = LAST_KNOWN.get(living.getUUID());
            if (lk == null || lk.level() != level) {
                LAST_KNOWN.put(living.getUUID(), new LastKnown(level, e.getX(), e.getY(), e.getZ()));
            } else {
                double dx = e.getX() - lk.x();
                double dy = e.getY() - lk.y();
                double dz = e.getZ() - lk.z();
                double dist2 = dx * dx + dy * dy + dz * dz;
                boolean flungFar = dist2 > 128.0 * 128.0;
                boolean inDeadChunk = !level.isPositionEntityTicking(liveBlockPos(e));
                if (flungFar) {
                    // 真被瞬移走了：拉回上一 tick 的合法位置，teleportTo 顺带触发 onMove 重登记
                    runWithoutGuard(() -> e.teleportTo(lk.x(), lk.y(), lk.z()));
                    long nowMs = System.currentTimeMillis();
                    Long lastLog = LAST_LOG.get(living);
                    if (lastLog == null || nowMs - lastLog > 5000) {
                        LAST_LOG.put(living, nowMs);
                        io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                                "[SuperDbg] 守卫实体异常位移，已拉回上一位置: {} 位移={}格",
                                living, String.format("%.1f", Math.sqrt(dist2)));
                    }
                } else if (inDeadChunk) {
                    // 位置没动但所在区块不在实体 tick：区块归属被第三方破坏。
                    // 坐标没变时 teleportTo 是 no-op（setPosRaw 短路），必须直接强制
                    // onMove 重新登记区块归属；同坐标传送只会空转。
                    forceReRegister(living);
                    long nowMs = System.currentTimeMillis();
                    Long lastLog = LAST_LOG.get(living);
                    if (lastLog == null || nowMs - lastLog > 5000) {
                        LAST_LOG.put(living, nowMs);
                        io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                                "[SuperDbg] 守卫实体所在区块不在实体 tick 状态，已强制重新登记区块归属: {} 位置={}",
                                living, liveBlockPos(e));
                    }
                } else {
                    LAST_KNOWN.put(living.getUUID(), new LastKnown(level, e.getX(), e.getY(), e.getZ()));
                }
            }
            if (slowScan) {
                // 追踪：约每秒记录状态快照（临时诊断），还原"实体是如何一步步消失的"
                // removed 显示真实字段值（isRemoved() 已被接管伪装，读它没意义）
                io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 追踪: {} hp={} pos=({},{},{}) removed字段={} 维度={}",
                        living, living.getHealth(), e.getX(), e.getY(), e.getZ(), isRemovedField(e),
                        living.level().dimension().location());
                // 冻结检测：区块在实体级 tick（常加载票据保证）但实体 tickCount 停了 →
                // 区块归属/tick 列表被第三方反射直写破坏（第三方清除武器的惯用手法），
                // 先自愈 levelCallback，再强制 onMove 按当前位置重新登记区块归属
                Integer lastTick = LAST_TICK_COUNT.put(living.getUUID(), living.tickCount);
                if (lastTick != null && living.tickCount == lastTick
                        && !living.isPassenger()
                        && level.isPositionEntityTicking(liveBlockPos(living))) {
                    restoreLevelCallbackIfNull(living);
                    try {
                        Field f = levelCallbackField();
                        if (f != null) {
                            Object cb = f.get(living);
                            if (cb instanceof net.minecraft.world.level.entity.EntityInLevelCallback realCb
                                    && cb != net.minecraft.world.level.entity.EntityInLevelCallback.NULL) {
                                realCb.onMove();
                            }
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                            "[SuperDbg] 守卫实体停止 tick（区块归属疑似被破坏），已强制重新登记: {}", living);
                }
            }
            // 1) 位置异常（被直接改坐标到 1e7 格外 / 虚空）→ 每 tick 拉回原位，
            //    避免滞留未加载区块被按 UNLOADED_TO_CHUNK 正常摘除
            if (Math.abs(e.getX()) > ABNORMAL_POS || Math.abs(e.getZ()) > ABNORMAL_POS
                    || e.getY() < -70.0D) {
                net.minecraft.core.BlockPos base = BASE_POS.get(e);
                double tx = 0, ty = 100, tz = 0;
                if (base != null) {
                    tx = base.getX() + 0.5;
                    ty = base.getY();
                    tz = base.getZ() + 0.5;
                }
                final double fx = tx, fy = ty, fz = tz;
                runWithoutGuard(() -> e.teleportTo(fx, fy, fz));
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 守卫实体位置异常，已拉回原位: {}", living);
            }
            // 3) 真的被清除（世界检索不到）→ 用保存的 NBT 原位重建，不让实体消失
            if (isGoneFromWorld(level, e)) {
                // 区块未加载：实体只是随区块正常卸载存盘，不是被清除，绝不能重建——
                // 重建塞进未加载区块会被原版摘除链立刻摘除，形成"摘除→重建"死循环
                // （实测每 tick 一次刷屏），且与存盘副本冲突产生多余实体/尸体。
                // 正确出口：区块重载时 LivingEntityMixin.readAdditionalSaveData 尾注
                // 按 NBT 持久化的守卫标记自动重新登记（含 restoreRealUuid）。
                if (!level.isPositionEntityTicking(liveBlockPos(e))) {
                    continue;
                }
                io.github.zgxhzhr.superdbg.Constants.LOG.info(
                        "[防移除守卫·正常兜底] 检测到守卫实体被移出世界索引（多为第三方流程/管理器操作），"
                                + "原位重建接管中——这是守卫在正常工作，实体不会消失: {} removed={}", e, e.isRemoved());
                restoreFromSnapshot(e);
            }
        }
    }

    /** 快照：守卫实体（<strong>强引用</strong>，键 UUID），被清除后仍可访问以做原位重建；
     *  弱引用会在实体被移除失去引用时被 GC，导致永远无法检测并重建。 */
    private static final java.util.Map<java.util.UUID, LivingEntity> SNAPSHOTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 判定守卫实体是否真的已从世界消失（被异常清除）。
     *  纯索引判定：不看 removed 字段（isRemoved() 已被接管伪装，字段也可能被外部直写），
     *  只看世界索引里还能不能找到原本体。以实体"自己所在维度"为准检索。 */
    private static boolean isGoneFromWorld(net.minecraft.server.level.ServerLevel level, Entity e) {
        try {
            net.minecraft.world.level.Level el = e.level();
            if (!(el instanceof net.minecraft.server.level.ServerLevel sl)) {
                return true;
            }
            net.minecraft.world.entity.Entity byUuid = sl.getEntity(e.getUUID());
            net.minecraft.world.entity.Entity byId = sl.getEntity(e.getId());
            return (byUuid != e) || (byId != e); // 本维度世界索引里找不到原本体
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 用快照 NBT 原位重建被清除的守卫实体（同 UUID / 词条 / 效果 / 等级 / 好感 / 聊天记录等全部保留）。
     *  重建到实体"当前所在维度"（若已离场）或原维度。 */
    private static void restoreFromSnapshot(Entity old) {
        try {
            // 防重：同一实体只重建一次（名册里已换人/已移除说明已被处理过）
            if (SNAPSHOTS.get(old.getUUID()) != old) {
                return;
            }
            // 合法离场（收魂/死亡）绝不重建
            if (isDismissed(old)) {
                removeFromGuards(old);
                return;
            }
            // 服务端已有同 UUID 的活实例（重建副本/放出女仆）：旧实例退役，绝不重复入册，
            // 否则 byUuid 互相覆盖、双方 spawn 包都已发出，客户端出现幽灵实体
            if (old.level() instanceof net.minecraft.server.level.ServerLevel snapshotLevel) {
                net.minecraft.world.entity.Entity existing = snapshotLevel.getEntity(old.getUUID());
                if (existing != null && existing != old && !existing.isRemoved()) {
                    removeFromGuards(old);
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                            "[SuperDbg] 同 UUID 已有活实例接管，旧实例退役不重建: old={} existing={}",
                            old, existing);
                    return;
                }
            }
            net.minecraft.world.level.Level oldLevel = old.level();
            if (!(oldLevel instanceof net.minecraft.server.level.ServerLevel level)) {
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 守卫实体重建放弃：所在维度不是服务端世界: {}", old);
                return;
            }
            // 序列化（含 UUID 与全部 NBT；若对象已无法存档则放弃本回合并移出名册）。
            // Entity.save() 直接读 removalReason 字段（同类内部不走路被接管的 getRemovalReason()），
            // 外部直写的 DISCARDED 会让序列化被拒绝（save=false）——先清掉真实字段
            clearRemovedField(old);
            CompoundTag snapshot = new CompoundTag();
            try {
                if (!old.save(snapshot)) {
                    GUARDED.remove(old);
                    SNAPSHOTS.remove(old.getUUID());
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 守卫实体重建失败：NBT 序列化被拒绝（save=false）: {}", old);
                    return;
                }
            } catch (Exception e) {
                GUARDED.remove(old);
                SNAPSHOTS.remove(old.getUUID());
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 守卫实体重建失败：NBT 序列化抛异常: {}", old, e);
                return;
            }
            // 清理旧对象（正式移除，走守卫放行通道）
            runWithoutGuard(old::discard);

            // 原位重建：EntityType.create 会用 NBT 中的 type id / UUID / 位置
            net.minecraft.world.entity.Entity revived =
                    net.minecraft.world.entity.EntityType.create(snapshot, level).orElse(null);
            if (revived == null) {
                GUARDED.remove(old);
                SNAPSHOTS.remove(old.getUUID());
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 守卫实体重建失败：EntityType.create 返回空: {}", old);
                return;
            }
            // 强制位置为原位（BASE_POS），防止 NBT 中残留异常位置。
            // 但 BASE_POS 是打标记时的位置，常加载票据早已随实体迁移走，
            // 若该区块已卸载，重建体落进未加载区块会被原版摘除链立刻摘除，
            // 形成"摘除→重建"连环死循环（实测 id 172→173→174→175 连环重建）。
            // BASE_POS 区块不在实体 tick 状态时回退到实体消失时的位置
            // （NBT Pos，worldTickGuard 重建前已验证该位置处于实体 tick 状态），
            // 并同步修正 BASE_POS，避免下次重建又锚到未加载区块。
            net.minecraft.core.BlockPos base = BASE_POS.get(old);
            if (base != null && level.isPositionEntityTicking(base)) {
                revived.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5,
                        old.getYRot(), old.getXRot());
            } else if (base != null) {
                BASE_POS.put(old, revived.blockPosition());
            }
            if (level.addFreshEntity(revived)) {
                if (revived instanceof LivingEntity lv) {
                    // 重新套守卫（NBT 中 persistentData 已带标记，但显式还原 SyncedEntityData）
                    set(lv, true);
                }
                io.github.zgxhzhr.superdbg.Constants.LOG.info(
                        "[防移除守卫·重建完成] 旧实例已清退，新实例已原位重建并重新挂载守卫——防移除继续生效，实体成功保住: old={}, new={}",
                        old, revived);
            } else {
                io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                        "[防移除守卫·重建失败] addFreshEntity 被拒绝（UUID 冲突？）: old={}, new={}",
                        old, revived);
            }
            removeFromGuards(old);
        } catch (Exception e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn("[防移除守卫·重建失败] 异常，实体未能恢复: {}", old, e);
        }
    }

    /** 只读真实 removalReason 字段（绕过 getRemovalReason() 接管） */
    private static Entity.RemovalReason readReasonField(Entity e) {
        try {
            Field f = reasonField();
            return f == null ? null : (Entity.RemovalReason) f.get(e);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * 守卫实体是否对外伪装"存活"（{@code isRemoved()→false}、{@code getRemovalReason()→null}）。
     * 字段随便外部写，所有读方看到的都是未移除——没有清字段拉锯，实体状态不受干扰。
     * 放行（不伪装）：客户端（死亡动画需要真值）、收魂白名单、正常死亡、
     * 区块卸载（UNLOADED——存盘/卸载流程依赖真值，伪装会导致实体退出世界后丢失）。
     */
    public static boolean shouldPretendAlive(LivingEntity living) {
        if (!has(living) || isBypassing()) {
            return false;
        }
        // 已合法离场：透传真实 isRemoved，管理器才能完成摘除（魂符/死亡的关键出口）
        if (isDismissed(living)) {
            return false;
        }
        // 已完成离场清理并出列的实体：必须继续透传真值，否则 isRemoved() 又变 false，
        // 已删除的实体会"复活"成可交互幽灵（右键还能打开面板）
        if (isRecentlyFinished(living.getUUID())) {
            return false;
        }
        net.minecraft.world.level.Level level = living.level();
        if (level == null || level.isClientSide) {
            return false;
        }
        if (isLegitRemoval(living)) {
            return false;
        }
        if (living.isDeadOrDying()) {
            return false;
        }
        Entity.RemovalReason r = readReasonField(living);
        return r != Entity.RemovalReason.UNLOADED_TO_CHUNK
                && r != Entity.RemovalReason.UNLOADED_WITH_PLAYER;
    }

    /** 强制移除标记集：编辑器移除实体时，让 isRemoved() Mixin 返回 true，
     *  使世界管理器用正规流程从所有内部结构中一致地删除实体（不破坏一致性）。
     *  <p>对覆写 isRemoved() 的 Boss 不生效（子类不调用 super，@Inject 不触发），
     *  此时由 {@link io.github.zgxhzhr.superdbg.mixin.PersistentEntitySectionManagerMixin} 在管理器 tick
     *  HEAD 直接调用完整删除流程绕过 isRemoved() 检查。 */
    private static final java.util.Set<java.util.UUID> FORCE_REMOVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 强制移除实体强引用表：供 PersistentEntitySectionManager Mixin 在 tick 时遍历调用完整删除流程。
     *  <p><b>经验</b>：PersistentEntitySectionManager 没有 removeEntity 方法（之前推测的
     *  SRG m_157606_ 不存在），原版完整删除流程是三步：
     *  <ol>
     *    <li>{@code stopTicking(T)} (SRG m_157570_) → {@code onTickingEnd} →
     *        {@code entityTickList.remove}（停止 tick，否则 Boss 残留 tick 列表继续走路）</li>
     *    <li>{@code stopTracking(T)} (SRG m_157580_) → {@code onTrackingEnd} →
     *        {@code chunkSource.removeEntity} + {@code visibleEntityStorage.remove}
     *        （从 byId/byUuid 删除）</li>
     *    <li>手动 {@code knownUuids.remove(uuid)}（SRG f_157491_，stopTracking 不删此集合）</li>
     *  </ol>
     *  <p>仅调用 stopTracking 不够——日志验证实体仍在 entityTickList 中继续 tick（AlarmThePro
     *  被强制移除后仍能被玩家选中、坐标仍在变化），且 knownUuids 残留影响后续同 UUID 实体注册。
     *  <p>Boss 覆写 isRemoved() 返回 false 时，FORCE_REMOVE 标记依赖的 isRemoved() Mixin 不触发，
     *  需 Mixin 直接在管理器 tick 中按上述完整流程删除，绕过 isRemoved() 检查。 */
    private static final java.util.Map<java.util.UUID, Entity> FORCE_REMOVE_ENTITIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void markForceRemoved(Entity e) {
        FORCE_REMOVE.add(e.getUUID());
        if (e != null) FORCE_REMOVE_ENTITIES.put(e.getUUID(), e);
    }

    public static boolean isForceRemoved(Entity e) {
        return e != null && FORCE_REMOVE.contains(e.getUUID());
    }

    public static void unmarkForceRemoved(Entity e) {
        if (e != null) {
            FORCE_REMOVE.remove(e.getUUID());
            FORCE_REMOVE_ENTITIES.remove(e.getUUID());
        }
    }

    /** 供 PersistentEntitySectionManager Mixin 调用：返回所有待强制移除的实体（强引用，不会 GC） */
    public static java.util.Collection<Entity> getForceRemoveEntities() {
        return FORCE_REMOVE_ENTITIES.values();
    }

    /** 返回全部守卫中实体快照（UUID→实体强引用表），供每 tick callback 自愈遍历 */
    public static java.util.Collection<LivingEntity> getGuardedEntities() {
        return SNAPSHOTS.values();
    }

    /** 强制直写 removed/removalReason 字段（编辑器移除用）：
     *  绕过一切 setRemoved 覆写与其它模组的 Mixin 拦截——与清除工具同级的字段级手段。 */
    public static void forceWriteRemoved(Entity e, Entity.RemovalReason reason) {
        try {
            Field f = removedField();
            if (f != null) {
                f.setBoolean(e, true);
            }
            Field r = reasonField();
            if (r != null) {
                r.set(e, reason);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /** 只读 removed 字段（绕过 isRemoved() 接管，判断"确实处于被移除态"） */
    public static boolean isRemovedField(Entity e) {
        try {
            Field f = removedField();
            return f != null && f.getBoolean(e);
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    /** 编辑器强删专用的高频伤害兜底：在 BYPASS 下依次尝试 setHealth(0)、
     *  genericKill 极伤、generic 1e9 伤害、die()，每种手段独立 try/catch 互不影响。
     *  针对覆写 hurt/死亡免疫/字段自愈的 Boss 形成多路径杀伤，
     *  与字段级摘除（fullDelete）配合，任一成功即可让其消失。
     *  <b>调用前实体必须已 markForceRemoved</b>，否则会触发守卫拦截与重建兜底。
     *  每 tick 可重复调用（管理器 tick HEAD 持续补刀直到删除完成）。 */
    public static void damageToKill(Entity e) {
        if (!(e instanceof net.minecraft.world.entity.LivingEntity living)
                || e instanceof net.minecraft.world.entity.player.Player) {
            return;
        }
        runWithoutGuard(() -> {
            try {
                living.setHealth(0.0F);
            } catch (Throwable ignored) {
            }
            try {
                living.hurt(living.damageSources().genericKill(), Float.MAX_VALUE);
            } catch (Throwable ignored) {
            }
            try {
                living.hurt(living.damageSources().generic(), 1.0e9F);
            } catch (Throwable ignored) {
            }
            try {
                living.die(living.damageSources().genericKill());
            } catch (Throwable ignored) {
            }
        });
    }

    /** 清除实体的 removed / removalReason 字段（绕过方法直接改字段时的兜底，缓存反射） */
    public static boolean clearRemovedField(Entity e) {
        try {
            Field removedField = removedField();
            if (removedField != null && removedField.getBoolean(e)) {
                removedField.setBoolean(e, false);
                Field reasonField = reasonField();
                if (reasonField != null) {
                    reasonField.set(e, null);
                }
                return true;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    /** 大幅瞬移判定：目标位置与当前距离超过阈值（格）视为"传送出了 nothing/跨区" */
    public static boolean isTeleportAway(Entity e, double x, double y, double z) {
        double dx = x - e.getX();
        double dy = y - e.getY();
        double dz = z - e.getZ();
        return (dx * dx + dy * dy + dz * dz) > TELEPORT_THRESHOLD * TELEPORT_THRESHOLD * 4.0D;
    }

    /**
     * 通过反射获取 LivingEntityMixin 合并到 LivingEntity 中的 SyncedEntityData 访问器。
     */
    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Boolean> accessor() {
        if (dataAccessor != null) {
            return dataAccessor;
        }
        try {
            Field f = LivingEntity.class.getDeclaredField("superdbg$DATA_REMOVAL_GUARD");
            f.setAccessible(true);
            dataAccessor = (EntityDataAccessor<Boolean>) f.get(null);
        } catch (ReflectiveOperationException e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 无法获取 superdbg$DATA_REMOVAL_GUARD 字段，防移除同步将失效", e);
        }
        return dataAccessor;
    }

    /** 当前线程是否处于绕过模式。 */
    public static boolean isBypassing() {
        return BYPASS.get();
    }

    // ---- 容器层门禁（守卫容器专用）----
    // 经验：带"清除武器"的模组可绕过 setRemoved/discard 拦截，
    // 直接反射操作 PersistentEntitySectionManager 内部容器删除实体：
    //   sectionStorage(EntitySection.byClass) / entityTickList.active /
    //   visibleEntityStorage.byId、byUuid / knownUuids / levelCallback=NULL
    // 唯一能拦截反射字段操作的办法：把这些容器替换为守卫容器（见 io.github.zgxhzhr.superdbg.guard 包），
    // 删除操作经过守卫容器时统一调用此门禁。

    /**
     * 实体级容器删除门禁：守卫实体被非白名单调用方删除时返回 true（拒绝）。
     * 编辑器强制移除（FORCE_REMOVE）、本模组绕过（BYPASS）、原版/Forge/TLM 等
     * 白名单流程一律放行。
     */
    public static boolean isContainerDeleteBlocked(Entity entity) {
        // 守卫是服务端功能：客户端（渲染层）的容器删除一律放行。
        // 必须放行的原因：服务端收容女仆后兜底 finishDismissed 会移出 DISMISSED，
        // 随后广播的销毁包到达客户端时，若客户端 EntityLookup（被本模组同样包装为
        // 守卫容器）里调用栈非白名单 + 已不在 DISMISSED → 删除被"假装成功"吞掉，
        // 旧实体客户端残留（阴影/气泡）；放出的同 UUID 新女仆在 EntityLookup.add
        // 因 Duplicate UUID 被拒 → 不进渲染索引（透明）且 entityData 全丢（默认模型"博丽灵梦"）。
        if (entity != null && entity.level() != null && entity.level().isClientSide) {
            return false;
        }
        if (!(entity instanceof LivingEntity living) || !has(living)) {
            return false;
        }
        if (isForceRemoved(entity) || BYPASS.get() || isDismissed(entity)) {
            return false;
        }
        // 区块卸载（UNLOADED_TO_CHUNK/UNLOADED_WITH_PLAYER）是正常生命周期：
        // setRemoved 此时已放行（isAbnormalRemoval L307），但 worldTickGuard 要下一 tick
        // 才补登 dismiss，中间窗口若此处拦截容器删除，实体会滞留世界索引——已存盘副本
        // 随区块重载后与滞留体同 UUID 冲突，表现为抽搐/无法互动/无法被攻击/复制。
        Entity.RemovalReason reason = readReasonField(entity);
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return false;
        }
        // reason 字段读不到（反射名差异等）时的兜底：所在区块不在实体 tick 状态
        // 说明正在随区块卸载，同样放行，避免滞留世界索引
        if (reason == null && entity.level() instanceof net.minecraft.server.level.ServerLevel sl
                && !sl.isPositionEntityTicking(liveBlockPos(entity))) {
            return false;
        }
        return findIllegalCaller() != null;
    }

    /**
     * UUID 级容器删除门禁（knownUuids、byUuid 容器只有 UUID 键）：
     * 从守卫快照反查实体。快照中没有的 UUID 一律放行（非守卫实体）。
     */
    public static boolean isContainerUuidDeleteBlocked(java.util.UUID uuid) {
        if (uuid == null || BYPASS.get() || isDismissedUuid(uuid)) {
            return false;
        }
        LivingEntity entity = SNAPSHOTS.get(uuid);
        if (entity == null || isForceRemoved(entity) || isDismissed(entity)) {
            return false;
        }
        // 与实体级门禁一致：区块卸载理由放行（详见 isContainerDeleteBlocked 注释）
        Entity.RemovalReason reason = readReasonField(entity);
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return false;
        }
        // reason 读不到的兜底：区块不在实体 tick 状态 = 正在随区块卸载
        if (reason == null && entity.level() instanceof net.minecraft.server.level.ServerLevel sl
                && !sl.isPositionEntityTicking(liveBlockPos(entity))) {
            return false;
        }
        return findIllegalCaller() != null;
    }

    // ---- levelCallback 保存与自愈 ----
    // 反射式字段级删除最后会把实体的 levelCallback 直写为 NULL 断开实体与
    // 管理器的联系（后续移动不再更新 section）。反射直写 final 字段无法用 Mixin 拦截，
    // 但管理器 tick 早于实体 tick，在其中检测守卫实体 callback 被置 NULL 并写回即可。

    /** 守卫实体打标记时的原 levelCallback（弱引用，实体 GC 后自动清理） */
    private static final java.util.Map<Entity, net.minecraft.world.level.entity.EntityInLevelCallback> SAVED_CALLBACKS =
            new java.util.WeakHashMap<>();

    // ---- blockPosition 缓存自愈 ----
    // Entity 内部有两份位置：position（Vec3 实时坐标，f_19825_）与
    // blockPosition（BlockPos 整数缓存，f_19826_）。setPos/setPosRaw 会同步更新两者，
    // 但第三方清除武器反射直写 position 字段后调 levelCallback.onMove()，
    // onMove 的新区块由 blockPosition() 缓存现算——缓存停在武器写入的虚空洞坐标
    // （实测 section(128,-4,3072) 即方块 2048,-64,49152），position 却是家坐标。
    // 两份位置分裂的连锁后果：
    //   1) onMove 把 section 归属登记到虚空洞区块（不 tick）→ 实体冻结成"不动的幽灵"
    //   2) updateChunkTicket 用 blockPosition() → 常加载票据锚到虚空洞，家区块失去常加载
    //   3) 死区块检测用 blockPosition() → 误报死区块，teleportTo 拉回家时 setPosRaw
    //      发现 position 没变直接 no-op，缓存永远修不好
    // 自愈策略：所有 tick 路径用实时坐标计算区块；发现缓存分裂时直接写回正确值，
    // 并强制调用一次 levelCallback.onMove() 重新登记 section 归属。

    /** blockPosition 缓存字段（开发名 blockPosition / SRG f_19826_），缓存反射 */
    private static Field blockPosCacheField;
    private static boolean blockPosCacheResolved;

    private static Field blockPosCacheField() {
        if (!blockPosCacheResolved) {
            blockPosCacheResolved = true;
            for (String name : new String[]{"blockPosition", "f_19826_"}) {
                try {
                    blockPosCacheField = Entity.class.getDeclaredField(name);
                    blockPosCacheField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return blockPosCacheField;
    }

    /** 用实时坐标计算实体所在方块位置（不读可能被污染的 blockPosition 缓存） */
    public static net.minecraft.core.BlockPos liveBlockPos(Entity e) {
        return net.minecraft.core.BlockPos.containing(e.getX(), e.getY(), e.getZ());
    }

    /**
     * 检测并修复 blockPosition 缓存与实时坐标的分裂。
     * @return true = 发生了修复（调用方应接着触发 onMove 重新登记 section）
     */
    public static boolean healBlockPosCache(Entity e) {
        try {
            net.minecraft.core.BlockPos live = liveBlockPos(e);
            if (live.equals(e.blockPosition())) {
                return false;
            }
            Field f = blockPosCacheField();
            if (f == null) {
                return false;
            }
            f.set(e, live);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 缓存自愈 + 强制重新登记：修复 blockPosition 缓存后调 levelCallback.onMove()，
     * 让 section 归属按正确位置重新登记。返回是否执行了修复。
     */
    public static boolean healSectionRegistration(LivingEntity living) {
        if (!healBlockPosCache(living)) {
            return false;
        }
        forceReRegister(living);
        return true;
    }

    /**
     * 强制重新登记区块归属：直接调用 levelCallback.onMove()。
     * onMove 内部按 blockPosition() 现算新区块并自愈（守卫实体的 blockPosition()
     * 已被出口拦截恒返回实时坐标），可把被第三方破坏的 section 归属拉回正轨。
     */
    public static void forceReRegister(LivingEntity living) {
        restoreLevelCallbackIfNull(living);
        try {
            Field f = levelCallbackField();
            if (f != null) {
                Object cb = f.get(living);
                if (cb instanceof net.minecraft.world.level.entity.EntityInLevelCallback realCb
                        && cb != net.minecraft.world.level.entity.EntityInLevelCallback.NULL) {
                    realCb.onMove();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** levelCallback 字段（SRG f_146801_）缓存：每 tick 自愈调用，严禁重复 getDeclaredField */
    private static Field levelCallbackField;

    private static Field levelCallbackField() {
        if (levelCallbackField == null) {
            try {
                levelCallbackField = Entity.class.getDeclaredField("f_146801_");
                levelCallbackField.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                levelCallbackField = null;
            }
        }
        return levelCallbackField;
    }

    /** 保存实体当前 levelCallback（打守卫标记时调用） */
    public static void saveLevelCallback(Entity entity) {
        try {
            Field f = levelCallbackField();
            if (f == null) {
                return;
            }
            Object cb = f.get(entity);
            if (cb != null && cb != net.minecraft.world.level.entity.EntityInLevelCallback.NULL) {
                SAVED_CALLBACKS.put(entity, (net.minecraft.world.level.entity.EntityInLevelCallback) cb);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /** 若守卫实体的 levelCallback 被反射置为 NULL，写回打标记时保存的原 callback。
     *  在 ServerLevel.tick HEAD 调用（早于实体 tick/move）。字段访问走缓存反射。 */
    public static void restoreLevelCallbackIfNull(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !has(living) || isDismissed(entity)) {
            return; // 合法离场实体摘除时 callback 置 NULL 是正常流程，绝不写回
        }
        try {
            Field f = levelCallbackField();
            if (f == null) {
                return;
            }
            Object cur = f.get(entity);
            if (cur == net.minecraft.world.level.entity.EntityInLevelCallback.NULL) {
                net.minecraft.world.level.entity.EntityInLevelCallback saved = SAVED_CALLBACKS.get(entity);
                if (saved != null) {
                    f.set(entity, saved);
                    io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                            "[SuperDbg] 守卫实体 levelCallback 被置 NULL，已恢复: uuid={}", entity.getUUID());
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /**
     * 编辑器强制移除完成（管理器 fullDelete 成功，或实体本就不在管理器中）后调用：
     * 清 FORCE_REMOVE 标记并移出名册/快照——否则 worldTickGuard 会把已删除实体
     * 当作"被异常清除"而原位重建。
     */
    public static void notifyForceRemoveCompleted(Entity entity) {
        if (entity == null) {
            return;
        }
        RECENTLY_DELETED.put(entity.getUUID(), System.currentTimeMillis());
        unmarkForceRemoved(entity);
        removeFromGuards(entity);
        SAVED_CALLBACKS.remove(entity);
    }

    /**
     * 近期被编辑器完整删除的实体 UUID → 删除时间戳。
     * 防止第三方模组的同对象复活机制在 fullDelete 之后把实体加回世界。保留 {@link #RECENTLY_DELETED_TTL_MS}。
     */
    private static final java.util.Map<java.util.UUID, Long> RECENTLY_DELETED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RECENTLY_DELETED_TTL_MS = 120_000L;

    /** 是否为本模组刚完整删除、禁止任何模组重新加回世界的实体 */
    public static boolean isRecentlyDeleted(java.util.UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long t = RECENTLY_DELETED.get(uuid);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t > RECENTLY_DELETED_TTL_MS) {
            RECENTLY_DELETED.remove(uuid);
            return false;
        }
        return true;
    }

    /**
     * 第三方清除工具入口门禁（直接 Mixin 进清除模组方法时使用）：
     * 目标是守卫实体、且当前不是编辑器强制移除/本模组绕过时返回 true（拒绝整个操作）。
     * 不需要扫调用栈——能进到被 Mixin 的清除方法里，调用方必然就是该清除工具。
     */
    public static boolean isExternalRemoveBlocked(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !has(living)) {
            return false;
        }
        return !isForceRemoved(entity) && !BYPASS.get() && !isDismissed(entity);
    }

    /**
     * 在绕过守卫的状态下执行操作。
     * 本模组自己的 forceRemoveEntity / kill / 收魂等需要真正移除实体的操作必须包裹在此方法中。
     */
    public static void runWithoutGuard(Runnable action) {
        boolean prev = BYPASS.get();
        BYPASS.set(true);
        try {
            action.run();
        } finally {
            BYPASS.set(prev);
        }
    }

    /**
     * 通过反射调用 Forge 的 getPersistentData()，
     * 兼容 multi-loader common 模块编译（不直接依赖 Forge 类）。
     */
    private static CompoundTag persistentData(LivingEntity entity) {
        try {
            return (CompoundTag) Entity.class
                    .getMethod("getPersistentData").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("无法获取实体 persistentData", e);
        }
    }
}