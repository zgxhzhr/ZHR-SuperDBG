package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 移除守卫 Mixin（激进封死版）：守卫实体对其它模组"只读不可改"。
 * <p>
 * 拦截点与放行规则：
 * <ul>
 *   <li>{@code setRemoved/remove/discard}：放行 = 正常死亡（DYING）/ 区块卸载 UNLOADED /
 *       TLM 收魂白名单（{@link RemovalGuard#isLegitRemoval}）/ 死亡动画清理（KILLED 且已死）；
 *       其余一律取消——Entity Remover 用的 DISCARDED 也拦</li>
 *   <li>{@code isRemoved()} 接管恒 false、{@code getRemovalReason()} 接管 null：
 *       世界管理器永远不摘除守卫实体，即便对方反射直接改字段</li>
 *   <li>{@code moveTo/setPos}：大幅瞬移（&gt;阈值）取消，正常寻路小移动放行</li>
 *   <li>{@code changeDimension / teleportTo(ServerLevel,..)}：跨维度传送取消</li>
 * </ul>
 * 优先级 2000，与其它模组对同一方法的注入共存（仅 @Inject，不 @Overwrite）。
 */
@Mixin(value = Entity.class, priority = 2000)
public abstract class RemovalGuardMixin {

    private static boolean shouldGuard(LivingEntity living) {
        if (living == null || !RemovalGuard.has(living) || RemovalGuard.isBypassing()) {
            return false;
        }
        // 守卫是服务端功能：客户端只显示状态，不拦截——否则会卡住死亡动画/尸体清理
        if (living.level() != null && living.level().isClientSide) {
            return false;
        }
        return true;
    }

    /**
     * 拦截带理由的移除入口（setRemoved / remove）。
     * 放行：调用栈全白名单的正常流程（TLM 收魂 / 原版死亡 / 区块卸载 / 本模组），
     * 仅拦截"非白名单调用方"的异常清除（Entity Remover 等）。
     */
    @Inject(
            method = {
                    "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
                    "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"
            },
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void superdbg$guardRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!(((Object) this) instanceof LivingEntity living) || !shouldGuard(living)) {
            return;
        }
        if (RemovalGuard.isAbnormalRemoval(living, reason)) {
            ci.cancel();
            String caller = RemovalGuard.findIllegalCaller();
            RemovalGuard.logIntercepted(living, "将实体移除出世界（setRemoved, reason=" + reason + "）", caller);
            if (caller != null && caller.startsWith("com.github.tartaricacid")) {
                sendCaptureBlockedMessage(living);
            }
        } else {
            // 合法的真离场登记放行：isRemoved 透传真值，管理器才会真正摘除。
            // - KILLED/DISCARDED：收魂/死亡
            // - UNLOADED_TO_CHUNK/UNLOADED_WITH_PLAYER：区块卸载/退出世界
            //   必须登记，否则 superdbg$guardRemovalOp（stopTicking/stopTracking）
            //   会走到 findIllegalCaller 检查调用栈，退出世界时若含第三方模组栈帧
            //   会误拦 stopTicking/stopTracking，实体滞留 tick 列表。
            //   实体随存档回来，重进时 readAdditionalSaveData 重新武装，不影响守卫持续性。
            if (reason == Entity.RemovalReason.KILLED
                    || reason == Entity.RemovalReason.DISCARDED
                    || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                    || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
                RemovalGuard.markDismissed(living);
            }
            io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 放行移除(removal): entity={}, reason={}", living, reason);
        }
    }

    /** 拦截 discard()：同样按调用栈白名单判别（discard 语义视为 DISCARDED 移除）。 */
    @Inject(method = "discard()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$guardDiscard(CallbackInfo ci) {
        if (!(((Object) this) instanceof LivingEntity living) || !shouldGuard(living)) {
            return;
        }
        if (RemovalGuard.isAbnormalRemoval(living, Entity.RemovalReason.DISCARDED)) {
            ci.cancel();
            String caller = RemovalGuard.findIllegalCaller();
            RemovalGuard.logIntercepted(living, "丢弃实体（discard()）", caller);
            // 给附近玩家发提示（TLM 收容类 caller → 提示关闭防移除，其它异常移除只 log）
            if (caller != null && caller.startsWith("com.github.tartaricacid")) {
                sendCaptureBlockedMessage(living);
            }
        } else {
            // 合法离场（区块卸载等非收容场景）
            RemovalGuard.markDismissed(living);
            io.github.zgxhzhr.superdbg.Constants.LOG.debug("[SuperDbg] 放行移除(discard): entity={}", living);
        }
    }

    /**
     * 接管 isRemoved()：守卫实体恒返回 false（伪装存活）。
     * Entity Remover 直接反射写 removed 字段时，字段虽被置位，
     * 但管理器摘除检查/实体 tick/同步全部走 isRemoved() 方法——读到 false，
     * 永不摘除、不影响 AI/动画。没有"每 tick 清字段"的拉锯。
     */
    @Inject(method = "isRemoved()Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$pretendAlive(CallbackInfoReturnable<Boolean> cir) {
        if (((Object) this) instanceof LivingEntity living) {
            // 已合法离场（收魂/死亡）：不干预，透传真实 isRemoved，管理器据此完成摘除
            if (RemovalGuard.isDismissed(living)) {
                return;
            }
            // 编辑器强制移除：返回 true，使管理器用正规流程从所有内部结构中一致删除
            if (RemovalGuard.isForceRemoved(living)) {
                cir.setReturnValue(true);
                return;
            }
            // 守卫：返回 false，使外部直写 removed 字段不影响实体
            if (RemovalGuard.shouldPretendAlive(living)) {
                cir.setReturnValue(false);
            }
        }
    }

    /** 接管 getRemovalReason()：与 isRemoved() 同条件返回。 */
    @Inject(method = "getRemovalReason()Lnet/minecraft/world/entity/Entity$RemovalReason;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$hideRemovalReason(CallbackInfoReturnable<Entity.RemovalReason> cir) {
        if (((Object) this) instanceof LivingEntity living) {
            // 合法离场：透传真实 reason
            if (RemovalGuard.isDismissed(living)) {
                return;
            }
            if (RemovalGuard.isForceRemoved(living)) {
                cir.setReturnValue(Entity.RemovalReason.DISCARDED);
                return;
            }
            if (RemovalGuard.shouldPretendAlive(living)) {
                cir.setReturnValue(null);
            }
        }
    }

    /** 拦截跨维度传送（leave-level calls）。守卫实体不允许被换维度。
     *  保留原逻辑不变。 */
    @Inject(method = "changeDimension(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$guardDimension(ServerLevel target, CallbackInfoReturnable<Entity> cir) {
        if (!(((Object) this) instanceof LivingEntity living) || !shouldGuard(living)) {
            return;
        }
        if (RemovalGuard.isLegitRemoval((Entity) (Object) this)) {
            return;
        }
        cir.setReturnValue((Entity) (Object) this); // 原样返回当前实体，不换维度
    }

    /** 取消收容时给发起交互的玩家发红字提示（精确到人，不广播附近）。 */
    private static void sendCaptureBlockedMessage(LivingEntity living) {
        try {
            // 从 PlayerInteractHandler → RemovalGuard ThreadLocal 取（EntityInteract 事件已登记玩家）
            Player blocker = RemovalGuard.getCaptureBlocker();
            if (blocker != null && blocker instanceof ServerPlayer sp) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "无法收容：该实体已开启防移除，请先在实体编辑器中关闭防移除").withStyle(net.minecraft.ChatFormatting.RED));
                RemovalGuard.clearCaptureBlocker();
            }
        } catch (Exception ignored) {
        }
    }
}