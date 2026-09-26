package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第三方"清除武器"入口短路（守卫实体保护）。
 * <p>
 * 部分模组提供能绕过常规 discard 流程的清除武器，还会附带自定义客户端销毁包、
 * 极端坐标传送等副作用；容器层门禁只能保住服务端索引，挡不住这些副作用，
 * 唯一根治点是在其清除方法入口对守卫实体直接 cancel。
 * <p>
 * 目标类不是原版类（remap=false），方法名为其源码原名；{@link Pseudo}
 * 表示软目标——未安装该模组时找不到目标类仅跳过并警告，不会导致
 * mixin config 初始化失败（Mixin 0.8.5 的 mixins.json 数组只接受
 * 字符串，不支持 {"name","required"} 对象写法，软依赖只能靠 @Pseudo）。
 * 该入口类本身可以被普通 @Inject 命中；该模组另一处会复活实体的工具类
 * 无法直接注入，防复活改挂原版 PersistentEntitySectionManager.addNewEntityWithoutEvent。
 */
@Pseudo
@Mixin(targets = "flashfur.omnimobs.items.EntityRemover", remap = false, priority = 2000)
public abstract class ThirdPartyRemoverMixin {

    @Inject(
            method = "removeEntity(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void superdbg$blockGuardRemoval(Entity entity, Level level, CompoundTag tag, CallbackInfo ci) {
        if (RemovalGuard.isExternalRemoveBlocked(entity)) {
            ci.cancel();
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 拦截第三方清除武器: entity={} uuid={}",
                    entity, entity.getUUID());
        }
    }
}
