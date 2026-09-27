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
 * <p>
 * Forge 给 FriendlyByteBuf 额外打了 writeItemStack/readItemStack 两个方法，
 * 与原版 writeItem/readItem 是<strong>并列实现</strong>（不互相委托）。
 * l2serial（L2Hostility/L2Library 生态）收发不对称：写包走 Forge 的
 * writeItemStack（count=writeByte），读包走原版 readItem（本类已改 VarInt）。
 * 一旦有 count ≥ 128 的物品经 l2serial 同步，读端 VarInt 多吞一个字节，
 * 后续 NBT 全部错位 → "Root tag must be a named compound tag" → 登录掉线。
 * 故把 Forge 两条路径也统一为 VarInt，四条收发路径编码完全一致。
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

    /**
     * Forge 发包路径（Forge 补丁新增方法，仅 Forge 环境存在，Fabric 下 require=0 跳过）。
     * Forge 新增方法不参与混淆映射，运行时即实名 → remap=false 跳过 AP 映射校验
     * （common 模组编译类路径是纯原版 MC，没有该方法）。
     * limitedTag 语义保留：true 时数量上限 99（原版供配方等受限通道使用）。
     */
    @Inject(method = "writeItemStack(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/network/FriendlyByteBuf;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void superdbg$writeItemStack(ItemStack stack, boolean limitedTag,
                                         CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf buf = (FriendlyByteBuf) (Object) this;
        if (stack.isEmpty()) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            Item item = stack.getItem();
            buf.writeId(BuiltInRegistries.ITEM, item);
            buf.writeVarInt(limitedTag ? Math.min(stack.getCount(), 99) : stack.getCount());
            CompoundTag tag = null;
            if (item.canBeDepleted() || item.shouldOverrideMultiplayerNbt()) {
                tag = stack.getTag();
            }
            buf.writeNbt(tag);
        }
        cir.setReturnValue(buf);
    }

    /** Forge 收包路径：与 Forge 写端对称，count 读 VarInt */
    @Inject(method = "readItemStack()Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void superdbg$readItemStack(CallbackInfoReturnable<ItemStack> cir) {
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
