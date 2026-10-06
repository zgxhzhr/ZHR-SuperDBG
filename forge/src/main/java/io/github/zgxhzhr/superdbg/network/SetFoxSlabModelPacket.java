package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * 客户端→服务端：点选魂符展示模型后立即写回（不依赖调试器保存按钮）。
 *
 * <p>服务端按实体 id 取目标（-1 表示编辑者自己，与实体编辑器快照提交一致），
 * 要求提交者处于创造模式且目标是玩家，随后经反射调用
 * 人是狐（playermaid）的 {@code FoxMaidApi#setSlabModelId} 持久化；
 * 模型 id 为 null 表示清除。</p>
 */
public record SetFoxSlabModelPacket(int entityId, @Nullable String modelId) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(modelId != null);
        if (modelId != null) {
            buf.writeUtf(modelId);
        }
    }

    public static SetFoxSlabModelPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        String modelId = buf.readBoolean() ? buf.readUtf() : null;
        return new SetFoxSlabModelPacket(entityId, modelId);
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
        // -1 表示编辑者自己
        Entity entity = entityId == -1 ? player : player.level().getEntity(entityId);
        if (!(entity instanceof ServerPlayer target)) {
            return;
        }
        PlayerMaidCompat.setSlabModelId(target, modelId);
    }
}
