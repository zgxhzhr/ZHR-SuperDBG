package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.entityclear.EntityClearConfig;
import io.github.zgxhzhr.superdbg.entityclear.EntityClearService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 客户端→服务端：实体清除器的"预览扫描"或"立即执行"。
 *
 * @param execute false=只扫描返回各类符合条件的数量；true=真正执行清除
 */
public record EntityClearPacket(boolean execute, EntityClearConfig config) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(execute);
        config.writeToBuf(buf);
    }

    public static EntityClearPacket decode(FriendlyByteBuf buf) {
        boolean execute = buf.readBoolean();
        return new EntityClearPacket(execute, EntityClearConfig.readFromBuf(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            // 服务端复检：仅创造模式可用，配置不可信
            if (player == null || !player.isCreative()) {
                return;
            }
            EntityClearService.Result result =
                    EntityClearService.run(player, config, execute);
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new EntityClearResultPacket(execute, result.protectedCount(), result.counts()));
        });
        ctx.setPacketHandled(true);
    }
}
