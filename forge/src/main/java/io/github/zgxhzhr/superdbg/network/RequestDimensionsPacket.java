package io.github.zgxhzhr.superdbg.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端→服务端：请求全部已加载维度列表。
 * <p>
 * 客户端登录同步注册表中不包含 dimension（LevelStem）注册表，自行枚举只能拿到
 * 本次会话见过的维度，模组维度切不过去。服务端 {@code server.levelKeys()}
 * 在开服时就为每个静态注册维度（含模组维度）创建了 ServerLevel，枚举最全。
 */
public record RequestDimensionsPacket() {

    public static void encode(RequestDimensionsPacket pkt, FriendlyByteBuf buf) {
    }

    public static RequestDimensionsPacket decode(FriendlyByteBuf buf) {
        return new RequestDimensionsPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null || !player.isCreative()) {
            return;
        }
        List<net.minecraft.resources.ResourceLocation> dims = new ArrayList<>();
        player.server.levelKeys().stream()
                .map(net.minecraft.resources.ResourceKey::location)
                .sorted()
                .forEach(dims::add);
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new DimensionListPacket(dims));
    }
}
