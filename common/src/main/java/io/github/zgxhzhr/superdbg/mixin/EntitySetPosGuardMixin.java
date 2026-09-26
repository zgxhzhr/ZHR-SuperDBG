package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 守卫实体异常瞬移拦截。
 * <p>
 * 第三方清除武器在删除实体前会先把实体瞬移到世界边界外的极端坐标
 * （实测 z≈1.8 亿），同 tick 内再移回，靠这个往返扰乱区块归属与客户端
 * 状态。世界监控的每 tick 检查看不见同 tick 往返，唯一根治点是在位置
 * 写入入口按目的地拦截：{@code setPos} 是所有直接写坐标通道
 * （teleportTo/moveTo）的漏斗。
 * <p>
 * 只拦"目的地越界 + 非白名单调用栈"，正常走路/击退/女仆跟随传送不受影响。
 */
@Mixin(Entity.class)
public abstract class EntitySetPosGuardMixin {

    @Inject(method = "setPos(DDD)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$guardExtremeTeleport(double x, double y, double z, CallbackInfo ci) {
        if (RemovalGuard.isAbnormalTeleport((Entity) (Object) this, x, y, z)) {
            ci.cancel();
        }
    }
}
