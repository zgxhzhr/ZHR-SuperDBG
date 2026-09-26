package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 世界层守卫兜底（服务端）：每 tick 在 ServerLevel.tick HEAD 调用
 * {@link RemovalGuard#worldTickGuard}——保证被外部直接反射置位的 removed 字段
 * 在世界管理器摘除检查之前被清掉（管理器每 tick 处理，低频清理跑不过它）。
 * 全部为缓存反射 + 纯读操作，无高频 getDeclaredField/索引查询/addNew，
 * 不会重演女仆"不动/下落动画"异常。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), require = 1)
    private void superdbg$guardWorldTick(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        // 容器完整性校验 + levelCallback 自愈（早于实体 tick/move）
        io.github.zgxhzhr.superdbg.guard.GuardIntegrity.verify(level);
        RemovalGuard.worldTickGuard(level);
    }
}