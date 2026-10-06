package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.entity.PseudoCreativeState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端→服务端：伪创造模式开关（即时生效，不走实体编辑器主提交）。
 * <p>
 * 服务端按实体 id 取目标（-1 表示编辑者自己，与实体编辑器快照提交一致），
 * 要求提交者处于创造模式且目标是玩家，随后持久化到目标玩家
 * {@code persistentData}（{@code superdbg_pseudo_creative} /
 * {@code superdbg_pseudo_health}）。开启瞬间记录当前血量作为创造模式锁血基准。
 */
public record SetPseudoCreativePacket(int entityId, boolean enabled) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeBoolean(enabled);
    }

    public static SetPseudoCreativePacket decode(FriendlyByteBuf buf) {
        return new SetPseudoCreativePacket(buf.readVarInt(), buf.readBoolean());
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
        PseudoCreativeState.set(target, enabled);
        Constants.LOG.info("[SuperDbg] 伪创造模式 {}：{}（锁定血量 {}）", target.getName().getString(),
                enabled ? "开" : "关", enabled ? target.getHealth() : "-");
    }
}
