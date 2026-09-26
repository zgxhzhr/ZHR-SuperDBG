package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移除 yuyu_weapon 对 SynchedEntityData.DataItem.setValue 的血量上限钳制。
 * <p>
 * 背景：通天之路整合包的 yuyu_weapon 通过 ASM 转换器
 * （{@code ApocalypseHealthLockTransformer}）在 DataItem.setValue 中
 * 注入了血量上限钳制（约 2000 万），导致实体编辑器无法把血量调到上限以上。
 * <p>
 * 这里直接覆写 setValue 的行为：把传入值原样写入 value 字段，不做任何钳制。
 * 由于 value 是 private 字段，通过 {@link Shadow} 反射访问。
 */
@Mixin(SynchedEntityData.DataItem.class)
public abstract class DataItemMixin {

    @Shadow
    private Object value;

    /**
     * 直接把传入值写入 value 字段，绕过 yuyu 的上限钳制。
     * 仅在 yuyu 存在时接管（见 {@link io.github.zgxhzhr.superdbg.compat.YuyuCompat#LOADED}），
     * 其它环境走原版 setValue，避免屏蔽其它模组对该方法的修改。
     */
    @Inject(method = "setValue", at = @At("HEAD"), cancellable = true)
    private void superdbg$setValueNoCap(Object pValue, CallbackInfo ci) {
        if (!io.github.zgxhzhr.superdbg.compat.YuyuCompat.LOADED) {
            return;
        }
        this.value = pValue;
        ci.cancel();
    }
}
