package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.Constants;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端同 UUID 幽灵实体清理。
 * <p>
 * 原版 {@code EntityLookup.add}（SRG m_156814_）字节码实测：方法开头先查
 * {@code byUuid.containsKey(uuid)}，<b>为 true 时只打一行 "Duplicate entity UUID"
 * 警告并直接 return</b>——byUuid/byId 一律不写，新实体整体被拒。渲染
 * （byId.values）与数据包路由（getEntity(id)）都走该表，于是先到的 remove 包
 * 留下的旧实体（已 removed、不再随同步包移动）继续渲染成"不动的幽灵"，
 * 后到的同 UUID/同 id 新实体隐形；玩家与真实实体交互（掉落物等）发生在
 * 服务端真实位置，与客户端幽灵位置错位。
 * <p>
 * 残留来源：玩家走远后服务端 removePlayer 解绑，客户端收到 remove 包把旧实体
 * 标记 DISCARDED，但其 onRemove 摘除链未把 byUuid/byId 删净（levelCallback 断链
 * 或经守卫容器包装的删除未生效）。
 * <p>
 * 修复（直接注入冲突发生处 EntityLookup.add HEAD，仅客户端）：发现同 UUID 旧实体
 * （非本地玩家）时——
 * ① 先调旧实体存活的 levelCallback.onRemove，走原版完整摘除（section/ticklist/lookup）；
 * ② 再反射剥掉守卫容器包装（GuardedUuidMap/GuardedInt2ObjectMap），在最底层映射上
 *    强制 remove byUuid/byId，兜底 ① 触碰不到的断链/包装残留；
 * ③ 本地标记旧实体 removed。
 * 清理后原版 add 的 containsKey 检查通过，新实体正常入表接管 id，位置同步与交互恢复。
 */
@Mixin(EntityLookup.class)
public abstract class ClientDuplicateEntityMixin<T extends EntityAccess> {

    @Shadow
    @Final
    private Map<UUID, T> byUuid;

    @Shadow
    @Final
    private Int2ObjectMap<T> byId;

    /** Entity.levelCallback（开发名 levelCallback / 生产 SRG 名 f_146801_），双名回退 */
    private static Field levelCallbackField;

    @SuppressWarnings("unchecked")
    @Inject(method = "add(Lnet/minecraft/world/level/entity/EntityAccess;)V",
            at = @At("HEAD"), require = 1)
    private void superdbg$evictUuidDuplicate(T incoming, CallbackInfo ci) {
        if (!(incoming instanceof Entity entity) || !(entity.level() instanceof ClientLevel)) {
            return;
        }
        T oldT = this.byUuid.get(entity.getUUID());
        if (!(oldT instanceof Entity old) || old == entity || old instanceof LocalPlayer) {
            return;
        }
        // ① 原版完整摘除链：回调存活时一次性摘 section / entityTickList / byUuid / byId
        try {
            Field f = superdbg$levelCallbackField();
            if (f != null) {
                Object cb = f.get(old);
                // onRemove(RemovalReason)：回调内部自持所属实体（即 old），
                // 传 DISCARDED 与 ClientLevel.removeEntity 的正常摘除理由一致
                if (cb instanceof EntityInLevelCallback realCb && cb != EntityInLevelCallback.NULL) {
                    realCb.onRemove(Entity.RemovalReason.DISCARDED);
                }
            }
        } catch (Throwable ignored) {
        }
        // ② 剥掉守卫容器包装，在最底层映射强删（兜底回调 NULL / 经包装方法未删净）
        boolean uuidLeft = true;
        try {
            Object rawUuid = superdbg$unwrap(this.byUuid);
            if (rawUuid instanceof Map<?, ?> m) {
                ((Map<UUID, Object>) m).remove(entity.getUUID());
            }
            Object rawId = superdbg$unwrap(this.byId);
            if (rawId instanceof Int2ObjectMap<?> m2) {
                ((Int2ObjectMap<Object>) m2).remove(old.getId());
            }
            uuidLeft = this.byUuid.containsKey(entity.getUUID());
        } catch (Throwable ignored) {
        }
        // ③ 本地标记移除，避免旧对象继续被当作活实体参与拾取/遍历
        try {
            old.setRemoved(Entity.RemovalReason.DISCARDED);
        } catch (Throwable ignored) {
        }
        if (uuidLeft) {
            Constants.LOG.warn("[SuperDbg] 客户端同UUID清理后byUuid仍残留（新实体可能仍被拒）: {}", old);
        } else {
            Constants.LOG.warn("[SuperDbg] 客户端收到同UUID新实体，已清旧幽灵: old={} new={}",
                    old, entity);
        }
    }

    /** 取出守卫容器（guard 包）内部的 delegate 底层映射；非守卫容器原样返回。 */
    private static Object superdbg$unwrap(Object guarded) {
        Class<?> c = guarded.getClass();
        if (c.getName().startsWith("io.github.zgxhzhr.superdbg.guard.")) {
            try {
                Field f = c.getDeclaredField("delegate");
                f.setAccessible(true);
                return f.get(guarded);
            } catch (Throwable ignored) {
            }
        }
        return guarded;
    }

    private static Field superdbg$levelCallbackField() {
        if (levelCallbackField == null) {
            for (String name : new String[]{"f_146801_", "levelCallback"}) {
                try {
                    Field f = Entity.class.getDeclaredField(name);
                    f.setAccessible(true);
                    levelCallbackField = f;
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return levelCallbackField;
    }
}
