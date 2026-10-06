package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.entity.RenderNameStore;
import io.github.zgxhzhr.superdbg.event.PlayerDisplayNameHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * 客户端→服务端：渲染名即时保存（不依赖调试器保存按钮）。
 *
 * <p>服务端按实体 id 取目标（-1 表示编辑者自己），要求提交者处于创造模式且目标是玩家，
 * 写入 {@link RenderNameStore} 后广播渲染名（头顶名牌 + Tab 列表显示名）并刷新
 * displayname 缓存。渲染名为 null/空白表示清除（恢复真实名）。</p>
 */
public record SetRenderNamePacket(int entityId, @Nullable String renderName) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(renderName != null);
        if (renderName != null) {
            buf.writeUtf(renderName);
        }
    }

    public static SetRenderNamePacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        String renderName = buf.readBoolean() ? buf.readUtf() : null;
        return new SetRenderNamePacket(entityId, renderName);
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
        RenderNameStore.set(target, renderName);
        // 清除 displayname 缓存，使下一次 getDisplayName 重新走 NameFormat 事件拿到新名
        target.refreshDisplayName();
        // 广播渲染名：SyncRenderNamePacket（头顶名牌/客户端缓存）+ PlayerInfoUpdatePacket（Tab 列表显示名）
        PlayerDisplayNameHandler.broadcast(player.getServer(), target);
        io.github.zgxhzhr.superdbg.Constants.LOG.info(
                "[SuperDbg] SetRenderNamePacket 写回 renderName={}", renderName);
    }
}
