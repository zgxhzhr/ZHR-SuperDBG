package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 守卫实体 blockPosition 缓存返回值保护。
 * <p>
 * 第三方清除武器绕过 {@code setPos}/{@code setPosRaw} 的惯用手法是：
 * 反射直写 {@code position} 字段（f_19825_）到极端坐标后，直接调用
 * {@code levelCallback.onMove()}。{@code onMove} 内部的新区块由
 * {@code entity.blockPosition()} 缓存（f_19826_）现算——缓存停在武器写入的
 * 极端值，导致 section 归属被错误登记到不 tick 的区块，实体冻结成幽灵。
 * <p>
 * 本 Mixin 在 {@code blockPosition()} RETURN 注入：守卫实体调用方拿到的
 * 永远是实时坐标对应的正确 BlockPos，从根上让武器的缓存污染失效。
 * 正常走路/击退/传送不受影响（实时坐标与缓存一致时返回值不变）。
 */
@Mixin(Entity.class)
public abstract class BlockPosGuardMixin {

    @Inject(method = "blockPosition()Lnet/minecraft/core/BlockPos;", at = @At("RETURN"), cancellable = true, require = 1)
    private void superdbg$guardBlockPos(CallbackInfoReturnable<BlockPos> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof LivingEntity living)) {
            return;
        }
        if (!RemovalGuard.has(living) || RemovalGuard.isBypassing()) {
            return;
        }
        BlockPos live = RemovalGuard.liveBlockPos(self);
        if (!live.equals(cir.getReturnValue())) {
            cir.setReturnValue(live);
        }
    }
}
