package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 物品堆叠数量的 NBT 通道放宽：原版把 Count 存为 byte（-128~127），
 * 本模组允许一组最多 999（调试发放），存档时改写 int，读档时兼容还原。
 * 与药水 Amplifier 的 byte→int 升级同款模式。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackCountMixin {

    /** 存档：Count 由 byte 改写为 int（读到的截断 byte 参数忽略，取真实 count） */
    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/CompoundTag;putByte(Ljava/lang/String;B)V"), require = 1)
    private void superdbg$saveCountAsInt(CompoundTag tag, String key, byte truncated) {
        tag.putInt(key, ((ItemStack) (Object) this).getCount());
    }

    /** 读档：若 Count 是 int（新版写入），按 int 还原完整数量 */
    @Inject(method = "of", at = @At("RETURN"), require = 1)
    private static void superdbg$readCountAsInt(CompoundTag tag, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack stack = cir.getReturnValue();
        if (stack != null && !stack.isEmpty() && tag.contains("Count", Tag.TAG_INT)) {
            stack.setCount(tag.getInt("Count"));
        }
    }
}
