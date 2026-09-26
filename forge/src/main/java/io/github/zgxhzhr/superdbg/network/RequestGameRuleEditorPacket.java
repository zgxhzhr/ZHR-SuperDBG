package io.github.zgxhzhr.superdbg.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 客户端→服务端：请求打开 gamerule 编辑器。服务端推回 OpenGameRuleEditorPacket。
 */
public record RequestGameRuleEditorPacket() {

    public static void encode(RequestGameRuleEditorPacket pkt, FriendlyByteBuf buf) {
    }

    public static RequestGameRuleEditorPacket decode(FriendlyByteBuf buf) {
        return new RequestGameRuleEditorPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;
        if (!player.isCreative() || !player.hasPermissions(2)) return;
        OpenGameRuleEditorPacket pkt = OpenGameRuleEditorPacket.build(player);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), pkt);
    }
}
