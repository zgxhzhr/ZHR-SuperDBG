package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.compat.GameRuleRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端→服务端：提交 gamerule 修改值。
 * 服务端逐项校验（OP 权限、Key 存在、值类型匹配）后原子写回 GameRules。
 */
public record SubmitGameRuleEditorPacket(Map<String, String> changes) {

    public static void encode(SubmitGameRuleEditorPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.changes.size());
        for (Map.Entry<String, String> e : pkt.changes.entrySet()) {
            buf.writeUtf(e.getKey(), 256);
            buf.writeUtf(e.getValue(), 32);
        }
    }

    public static SubmitGameRuleEditorPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<String, String> changes = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            changes.put(buf.readUtf(256), buf.readUtf(32));
        }
        return new SubmitGameRuleEditorPacket(changes);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        // 只有创造模式 OP 能改 gamerule
        if (!player.isCreative() || !player.hasPermissions(2)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        GameRuleRegistry.build();
        GameRules rules = server.getGameRules();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String id = e.getKey();
            String val = e.getValue();
            GameRules.Key<?> key = GameRuleRegistry.getKey(id);
            if (key == null) {
                continue; // 客户端发来不存在的 key，静默跳过
            }
            try {
                if (GameRuleRegistry.isBoolean(id)) {
                    boolean boolVal = Boolean.parseBoolean(val);
                    @SuppressWarnings("unchecked")
                    GameRules.Key<GameRules.BooleanValue> bKey = (GameRules.Key<GameRules.BooleanValue>) key;
                    rules.getRule(bKey).set(boolVal, server);
                } else {
                    int intVal = Integer.parseInt(val.trim());
                    @SuppressWarnings("unchecked")
                    GameRules.Key<GameRules.IntegerValue> iKey = (GameRules.Key<GameRules.IntegerValue>) key;
                    rules.getRule(iKey).set(intVal, server);
                }
            } catch (NumberFormatException ignored) {
                // 客户端非法整数值，跳过
            } catch (Exception ex) {
                io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] gamerule 写回失败: {} -> {}", id, val, ex);
            }
        }
    }
}
