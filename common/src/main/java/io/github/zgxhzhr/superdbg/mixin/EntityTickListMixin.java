package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.guard.GuardedInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EntityTickList 守卫包装：active（SRG f_156903_）替换为守卫 Int2ObjectMap。
 * <p>
 * 反射直写 {@code entityTickList.active.remove(entityId)} 的字段级删除可绕过
 * {@link RemovalGuardMixin} 对 setRemoved 的拦截；包装后该删除被门禁拒绝。
 * <p>
 * {@code ensureActiveIsNotIterated} 在 forEach 迭代期间发生 add/remove 时会交换
 * active 与 passive（active 指向全新未包装 map），所以在它 TAIL 重新包装，
 * 保证 active 字段始终是守卫容器。
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListMixin {

    @Shadow
    @Mutable
    private Int2ObjectMap<Entity> active;

    @Inject(method = "<init>()V", at = @At("TAIL"), require = 1)
    private void superdbg$wrapActive(CallbackInfo ci) {
        this.active = new GuardedInt2ObjectMap<>(this.active);
    }

    @Inject(method = "ensureActiveIsNotIterated()V", at = @At("TAIL"), require = 1)
    private void superdbg$rewrapActiveAfterSwap(CallbackInfo ci) {
        if (!(this.active instanceof GuardedInt2ObjectMap)) {
            this.active = new GuardedInt2ObjectMap<>(this.active);
        }
    }
}
