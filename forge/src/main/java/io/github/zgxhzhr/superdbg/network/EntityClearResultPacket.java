package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.client.entityclear.EntityClearClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 服务端→客户端：实体清除扫描/执行结果。
 * <p>
 * counts 为"通过保护筛选后"各类型符合条件的实体数量（与选取模式无关），
 * 客户端再按白名单/排除模式自行汇总将清除的总量与明细。
 *
 * @param executed       本次是否真正执行了清除（false=仅预览扫描）
 * @param protectedCount 被保护名单跳过的实体总数
 * @param counts         类型 id → 可清除数量
 */
public record EntityClearResultPacket(boolean executed, int protectedCount,
                                      Map<ResourceLocation, Integer> counts) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(executed);
        buf.writeVarInt(protectedCount);
        buf.writeVarInt(counts.size());
        counts.forEach((id, n) -> {
            buf.writeResourceLocation(id);
            buf.writeVarInt(n);
        });
    }

    public static EntityClearResultPacket decode(FriendlyByteBuf buf) {
        boolean executed = buf.readBoolean();
        int protectedCount = buf.readVarInt();
        int n = buf.readVarInt();
        Map<ResourceLocation, Integer> counts = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) {
            ResourceLocation id = buf.readResourceLocation();
            counts.put(id, buf.readVarInt());
        }
        return new EntityClearResultPacket(executed, protectedCount, counts);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> EntityClearClientState.acceptResult(executed, protectedCount, counts));
        ctx.setPacketHandled(true);
    }
}
