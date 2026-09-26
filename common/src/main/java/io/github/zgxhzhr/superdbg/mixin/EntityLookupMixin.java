package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.guard.GuardedInt2ObjectMap;
import io.github.zgxhzhr.superdbg.guard.GuardedUuidMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityLookup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/**
 * EntityLookup 守卫包装：构造完成后把 byId（SRG f_156807_）、
 * byUuid（SRG f_156808_）替换为守卫容器。
 * <p>
 * 同时覆盖服务端 PersistentEntitySectionManager.visibleEntityStorage 和客户端
 * TransientEntitySectionManager 的同名存储。第三方清除类模组反射直写字段删除实体
 * （byUuid.remove / byId.remove）时被容器门禁拦截。
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupMixin<T extends EntityAccess> {

    @Shadow
    @Final
    @Mutable
    private Int2ObjectMap<T> byId;

    @Shadow
    @Final
    @Mutable
    private Map<UUID, T> byUuid;

    @Inject(method = "<init>()V", at = @At("TAIL"), require = 1)
    private void superdbg$wrapContainers(CallbackInfo ci) {
        this.byId = new GuardedInt2ObjectMap<>(this.byId);
        this.byUuid = new GuardedUuidMap<>(this.byUuid);
    }
}
