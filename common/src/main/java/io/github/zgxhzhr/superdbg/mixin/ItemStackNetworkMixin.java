package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 物品堆叠数量的网络通道放宽：原版 writeItem/readItem 把 Count 序列化为
 * byte（-128~127），本模组允许一组最多 999（调试发放），改用 VarInt 传输。
 * 与 {@link ItemStackCountMixin}（NBT 通道）配套；两端均装本模组且协议
 * 版本一致才能进服，改线格式不影响兼容性。
 */
@Mixin(FriendlyByteBuf.class)
public abstract class ItemStackNetworkMixin {

    /** 发包：与原版唯一差异是 count 用 VarInt 而非 byte */
    @Inject(method = "writeItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$writeItem(ItemStack stack, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;
        if (stack.isEmpty()) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            Item item = stack.getItem();
            buf.writeId(BuiltInRegistries.ITEM, item);
            buf.writeVarInt(stack.getCount());
            CompoundTag tag = null;
            if (item.canBeDepleted() || item.shouldOverrideMultiplayerNbt()) {
                tag = stack.getTag();
            }
            buf.writeNbt(tag);
        }
        cir.setReturnValue(buf);
    }

    /** 收包：与写端对称，count 读 VarInt */
    @Inject(method = "readItem", at = @At("HEAD"), cancellable = true, require = 1)
    private void superdbg$readItem(CallbackInfoReturnable<ItemStack> cir) {
        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;
        if (!buf.readBoolean()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        Item item = buf.readById(BuiltInRegistries.ITEM);
        if (item == null) {
            item = Items.AIR;
        }
        ItemStack stack = new ItemStack(item, buf.readVarInt());
        stack.setTag(buf.readNbt());
        cir.setReturnValue(stack);
    }
}
