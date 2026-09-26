package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：守卫实体的服务端追踪装/拆日志。
 * <p>
 * ChunkMap.addEntity/removeEntity 决定实体是否进入追踪表（entityMap），
 * 拆装时客户端会收到 add/remove 实体包。第三方清除武器的部分副作用不直接
 * 删实体，而是拆掉追踪后由别处的逻辑重新装上——客户端表现为幽灵实体。
 * 仅在涉及守卫实体时记录（1 秒节流），附调用栈定位发起者。
 * <p>
 * 双方法名（开发名 addEntity/removeEntity + 生产 SRG 名）+ remap=false，
 * 与 {@link CallbackOnMoveGuardMixin} 同因：字符串/双重命名下 AP 不生成
 * refmap 条目，必须运行时双名兜底。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackLogMixin {

    private static long lastTrackLog;

    @Inject(method = {"addEntity(Lnet/minecraft/world/entity/Entity;)V", "m_140199_(Lnet/minecraft/world/entity/Entity;)V"},
            at = @At("HEAD"), require = 1, remap = false)
    private void superdbg$logAddEntity(Entity entity, CallbackInfo ci) {
        log("addEntity", entity);
    }

    @Inject(method = {"removeEntity(Lnet/minecraft/world/entity/Entity;)V", "m_140331_(Lnet/minecraft/world/entity/Entity;)V"},
            at = @At("HEAD"), require = 1, remap = false)
    private void superdbg$logRemoveEntity(Entity entity, CallbackInfo ci) {
        log("removeEntity", entity);
    }

    private static void log(String op, Entity entity) {
        if (!(entity instanceof LivingEntity living) || !RemovalGuard.has(living)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTrackLog < 1000) {
            return;
        }
        lastTrackLog = now;
        io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                "[SuperDbg] 守卫实体追踪变更 ChunkMap.{}: id={} {} pos=({},{},{})",
                op, living.getId(), living,
                String.format("%.1f", living.getX()),
                String.format("%.1f", living.getY()),
                String.format("%.1f", living.getZ()),
                new Throwable("调用栈"));
    }
}
