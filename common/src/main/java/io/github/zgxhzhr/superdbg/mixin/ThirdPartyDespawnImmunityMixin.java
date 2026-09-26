package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 第三方模组"防消失免疫总闸"改判 Mixin（双向）。
 * <p>
 * 部分 Boss 模组用 coremod 在原版多处实体移除入口（stopTicking、stopTracking、
 * EntitySection/ClassInstanceMultiMap/EntityLookup/ChunkMap 的 remove、
 * PersistentEntitySectionManager 的移除回调等）统一注入一个静态免疫判定方法，
 * 按调用栈白名单 + 实体类型决定是否取消移除。
 * <p>
 * 我们编辑器对 Boss 的字段级删除链（fullDelete）每步本身都成功，但经过这些
 * 注入点时会被以"非白名单调用方"为由整体取消，实体仍留在容器中。本 Mixin
 * 在该总闸 HEAD 按我们自己的上下文改判：
 * <ol>
 *   <li><b>BYPASS / FORCE_REMOVE</b>：强制返回 false（解除免疫），
 *       fullDelete 的摘除、die()/hurt() 杀伤路径全部生效；</li>
 *   <li><b>我方守卫实体 + 非法外部调用方</b>：强制返回 true，把对方的
 *       多个删除点变成我方防移除体系的额外纵深（判据沿用
 *       {@link RemovalGuard#findIllegalCaller()}，原版/Forge 等正常流程
 *       一律放行，不影响跨区块 stopTracking/startTracking 等正常逻辑）。</li>
 * </ol>
 * {@link Pseudo} 保证未安装该模组时仅跳过不崩。
 */
@Pseudo
@Mixin(targets = "flashfur.omnimobs.coremod.CoreModHooks", remap = false, priority = 2000)
public final class ThirdPartyDespawnImmunityMixin {

    private ThirdPartyDespawnImmunityMixin() {
    }

    @Inject(method = "checkDespawnImmunity(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void superdbg$despawnImmunityGate(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null) {
            return;
        }
        // 1) 我方主动强删（编辑器移除）：解除第三方防消失免疫
        if (RemovalGuard.isBypassing() || RemovalGuard.isForceRemoved(entity)) {
            cir.setReturnValue(false);
            return;
        }
        // 1.5) 自然死亡 / 已合法离场：永不赋予免疫。
        // 守卫只保护"活着"的实体。死亡动画结束后原版 onRemove 会经 stopTicking/
        // stopTracking/knownUuids.remove 完成摘除，而实体 tick 调用栈上可能带有性能类
        // 模组的非白名单帧，若此处强制免疫，对方 coremod 会取消移除步骤，导致摘除链
        // 中断（reason 已置 KILLED 但实体僵尸化，尸体只能靠兜底清理延迟消失）。
        if (entity instanceof LivingEntity dying
                && (RemovalGuard.isDismissed(dying) || dying.isDeadOrDying())) {
            cir.setReturnValue(false);
            return;
        }
        // 2) 我方守卫实体被外部非白名单调用方删除：借其免疫点加深防护
        if (entity instanceof LivingEntity living && RemovalGuard.has(living)) {
            String illegalCaller = RemovalGuard.findIllegalCaller();
            if (illegalCaller != null) {
                cir.setReturnValue(true);
            }
        }
    }
}
