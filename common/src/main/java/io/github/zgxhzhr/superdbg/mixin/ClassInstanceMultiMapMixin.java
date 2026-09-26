package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.guard.GuardedByClassMap;
import io.github.zgxhzhr.superdbg.guard.GuardedEntityList;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * ClassInstanceMultiMap 守卫包装：构造完成后把 byClass（SRG f_13527_）、
 * allInstances 替换为守卫容器。
 * <p>
 * EntitySection 用该 multimap 存储区块内实体。反射遍历 byClass 的每个 List
 * 调 remove(entity) 的字段级摘除——包装为 {@link GuardedByClassMap} 后，
 * 无论走原方法还是反射字段，删除均经过守卫门禁。
 */
@Mixin(ClassInstanceMultiMap.class)
public abstract class ClassInstanceMultiMapMixin<T> {

    @Shadow
    @Final
    @Mutable
    private Map<Class<?>, List<T>> byClass;

    @Shadow
    @Final
    @Mutable
    private List<T> allInstances;

    @Inject(method = "<init>(Ljava/lang/Class;)V", at = @At("TAIL"), require = 1)
    private void superdbg$wrapContainers(Class<T> baseClass, CallbackInfo ci) {
        this.byClass = new GuardedByClassMap<>(this.byClass);
        this.allInstances = new GuardedEntityList<>(this.allInstances);
    }
}
