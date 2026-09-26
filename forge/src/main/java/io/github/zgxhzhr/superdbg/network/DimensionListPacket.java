package io.github.zgxhzhr.superdbg.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端→客户端：下发全部已加载维度 id（响应 {@link RequestDimensionsPacket}）。
 * 含静态注册的模组维度——玩家没去过也会列出。
 */
public record DimensionListPacket(List<ResourceLocation> dimensions) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(dimensions.size());
        for (ResourceLocation dim : dimensions) {
            buf.writeResourceLocation(dim);
        }
    }

    public static DimensionListPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ResourceLocation> dims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            dims.add(buf.readResourceLocation());
        }
        return new DimensionListPacket(dims);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
                io.github.zgxhzhr.superdbg.client.ClientGuiHandler.applyDimensionList(dimensions));
        ctx.setPacketHandled(true);
    }
}
