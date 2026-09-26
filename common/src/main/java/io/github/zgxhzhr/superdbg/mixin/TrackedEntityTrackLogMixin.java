package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断：守卫实体的 TrackedEntity 级追踪拆除日志。
 * <p>
 * 客户端收到 remove 实体包的服务端合法来源只有两类：
 * ① {@code broadcastRemoved()} —— 整体摘除追踪，向所有追踪玩家广播 remove 包；
 * ② {@code removePlayer(ServerPlayer)} —— 按玩家解绑（如玩家走出追踪距离），
 *    只向该玩家发 remove 包，之后 {@code updatePlayer} 重新配对时会再发 add 包。
 * 两类路径都在此记录（仅守卫实体，1 秒节流，附调用栈），与
 * {@link ChunkMapTrackLogMixin} 配合即可定位"谁拆了守卫实体的追踪"。
 * <p>
 * 目标类是包私有内部类，编译期不可见，用字符串形式指定；entity 字段反射读取
 * （开发名 entity / 生产 SRG 名 f_140472_，双名回退）。方法名同样双名 +
 * remap=false——字符串 targets 的 Mixin AP 不生成 refmap 条目，必须运行时兜底。
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity", remap = false)
public abstract class TrackedEntityTrackLogMixin {

    private static final java.lang.reflect.Field ENTITY_FIELD;

    private static long lastTrackLog;

    static {
        java.lang.reflect.Field f = null;
        try {
            Class<?> teClass = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity");
            for (String name : new String[]{"entity", "f_140472_"}) {
                try {
                    f = teClass.getDeclaredField(name);
                    f.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (ReflectiveOperationException e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.error("[SuperDbg] TrackedEntityTrackLogMixin 反射初始化失败", e);
        }
        ENTITY_FIELD = f;
    }

    @Inject(method = {"broadcastRemoved()V", "m_140482_()V"}, at = @At("HEAD"), require = 1, remap = false)
    private void superdbg$logBroadcastRemoved(CallbackInfo ci) {
        log("broadcastRemoved");
    }

    @Inject(method = {"removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", "m_140485_(Lnet/minecraft/server/level/ServerPlayer;)V"},
            at = @At("HEAD"), require = 1, remap = false)
    private void superdbg$logRemovePlayer(ServerPlayer player, CallbackInfo ci) {
        log("removePlayer(" + (player == null ? "null" : player.getName().getString()) + ")");
    }

    private void log(String op) {
        if (ENTITY_FIELD == null) {
            return;
        }
        Entity entity;
        try {
            entity = (Entity) ENTITY_FIELD.get(this);
        } catch (IllegalAccessException e) {
            return;
        }
        if (!(entity instanceof LivingEntity living) || !RemovalGuard.has(living)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTrackLog < 1000) {
            return;
        }
        lastTrackLog = now;
        io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                "[SuperDbg] 守卫实体追踪变更 TrackedEntity.{}: id={} {} pos=({},{},{})",
                op, living.getId(), living,
                String.format("%.1f", living.getX()),
                String.format("%.1f", living.getY()),
                String.format("%.1f", living.getZ()),
                new Throwable("调用栈"));
    }
}
