package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.client.ClientRenderNameCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端→客户端：同步某个玩家的自定义渲染名（null 表示清除）。
 * 按玩家 UUID 索引（玩家死亡重生后实体 id 会变化，UUID 稳定）。
 */
public record SyncRenderNamePacket(UUID playerUuid, @Nullable String name) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerUuid);
        buf.writeBoolean(name != null);
        if (name != null) {
            buf.writeUtf(name);
        }
    }

    public static SyncRenderNamePacket decode(FriendlyByteBuf buf) {
        UUID playerUuid = buf.readUUID();
        String name = buf.readBoolean() ? buf.readUtf() : null;
        return new SyncRenderNamePacket(playerUuid, name);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> ClientRenderNameCache.set(playerUuid, name));
        ctx.setPacketHandled(true);
    }
}
