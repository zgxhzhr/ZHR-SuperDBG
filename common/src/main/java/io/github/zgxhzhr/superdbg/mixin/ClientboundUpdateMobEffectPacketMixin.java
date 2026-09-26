package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.network.FullAmplifierAccess;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 放开 {@code ClientboundUpdateMobEffectPacket} 中 amplifier 的 byte 瓶颈。
 * <p>
 * 原版网络包将 amplifier 存为 {@code byte}，传输时用 {@code writeByte}，
 * 接收时用 {@code readByte}，导致任何超过 255 的等级在网络同步时被截断。
 * <p>
 * 本 Mixin 给包对象增加一个 int 字段承载完整等级，wire format 改为 VarInt：
 * <ul>
 *   <li>服务端构造时：从 {@link MobEffectInstance} 捕获完整 amplifier</li>
 *   <li>写入网络流：{@code writeByte} 改为 {@code writeVarInt}</li>
 *   <li>客户端读包：{@code readByte} 改为 {@code readVarInt} 并存入字段</li>
 * </ul>
 * 字段跟随包对象走，构造线程（服务端主线程）与编码线程（Netty IO 线程）
 * 不同也不会串值。
 */
@Mixin(ClientboundUpdateMobEffectPacket.class)
public abstract class ClientboundUpdateMobEffectPacketMixin implements FullAmplifierAccess {

    @Unique
    private int superdbg$fullAmplifier = FullAmplifierAccess.UNSET;

    @Override
    public int superdbg$getFullAmplifier() {
        return superdbg$fullAmplifier;
    }

    @Override
    public void superdbg$setFullAmplifier(int amplifier) {
        this.superdbg$fullAmplifier = amplifier;
    }

    // ========== 服务端构造：从 MobEffectInstance 创建包 ==========

    @Inject(
            method = "<init>(ILnet/minecraft/world/effect/MobEffectInstance;)V",
            at = @At("TAIL")
    )
    private void superdbg$captureFullAmplifier(int entityId, MobEffectInstance effect, CallbackInfo ci) {
        this.superdbg$fullAmplifier = effect.getAmplifier();
    }

    // ========== 客户端构造：从 FriendlyByteBuf 读包 ==========

    /**
     * 将原版读取 amplifier 的第一个 {@code readByte()} 改为 {@code readVarInt()}，
     * 完整值存入扩展字段。
     */
    @Redirect(
            method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readByte()B", ordinal = 0)
    )
    private byte superdbg$readAmplifierAsVarInt(FriendlyByteBuf buffer) {
        int full = buffer.readVarInt();
        this.superdbg$fullAmplifier = full;
        // 返回截断的 byte 给原版字段赋值（原版字段类型无法改）
        return (byte) full;
    }

    // ========== 写入方法：将 amplifier 的 writeByte 改为 writeVarInt ==========

    /**
     * 将原版写入 amplifier 的第一个 {@code writeByte} 改为 {@code writeVarInt}，
     * 直接取本包对象扩展字段里的完整 int 值。
     */
    @Redirect(
            method = "write",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeByte(I)Lio/netty/buffer/ByteBuf;", ordinal = 0)
    )
    private ByteBuf superdbg$writeAmplifierAsVarInt(FriendlyByteBuf buffer, int value) {
        int full = this.superdbg$fullAmplifier;
        return buffer.writeVarInt(full == FullAmplifierAccess.UNSET ? value : full);
    }
}
