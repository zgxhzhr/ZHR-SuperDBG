package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.compat.GameRuleRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端→客户端：推送当前世界全部 gamerule 的快照。
 * 客户端只持 id 字符串、值字符串、类型标记（boolean/integer）和分类，
 * 绝不持有 GameRules.Key 对象。
 */
public record OpenGameRuleEditorPacket(List<GameRuleEntry> entries) {

    /** 单条 gamerule 的传输数据 */
    public record GameRuleEntry(String id, String descriptionId, String categoryKey,
                                boolean isBoolean, String defaultValue, String currentValue) {
    }

    public static void encode(OpenGameRuleEditorPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entries.size());
        for (GameRuleEntry e : pkt.entries) {
            buf.writeUtf(e.id(), 256);
            buf.writeUtf(e.descriptionId(), 256);
            buf.writeUtf(e.categoryKey(), 128);
            buf.writeBoolean(e.isBoolean());
            buf.writeUtf(e.defaultValue(), 32);
            buf.writeUtf(e.currentValue(), 32);
        }
    }

    public static OpenGameRuleEditorPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<GameRuleEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new GameRuleEntry(
                    buf.readUtf(256),
                    buf.readUtf(256),
                    buf.readUtf(128),
                    buf.readBoolean(),
                    buf.readUtf(32),
                    buf.readUtf(32)
            ));
        }
        return new OpenGameRuleEditorPacket(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> io.github.zgxhzhr.superdbg.client.ClientGuiHandler.openGameRuleEditor(this));
        ctx.setPacketHandled(true);
    }

    /** 服务端侧构造：从 ServerLevel 读所有已注册 gamerule */
    public static OpenGameRuleEditorPacket build(ServerPlayer player) {
        GameRuleRegistry.build();
        GameRules rules = player.getServer().getGameRules();
        List<GameRuleEntry> entries = new ArrayList<>();
        // 通过 visitGameRuleTypes 枚举全部（含模组注册的）
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key, GameRules.Type<GameRules.BooleanValue> type) {
                String cur = String.valueOf(rules.getBoolean(key));
                // type.createRule() 得到默认值
                GameRules.BooleanValue def = type.createRule();
                String catKey = key.getCategory() != null
                        ? key.getCategory().getDescriptionId()
                        : "gamerule.category.misc";
                // 模组自定义的 Category 可能不是枚举值（artifacts 全写 PLAYER），
                // 但我们只在渲染时用 categoryKey 做分组标题翻译键
                entries.add(new GameRuleEntry(key.getId(), key.getDescriptionId(), catKey,
                        true, String.valueOf(def.get()), cur));
            }

            @Override
            public void visitInteger(GameRules.Key<GameRules.IntegerValue> key, GameRules.Type<GameRules.IntegerValue> type) {
                String cur = String.valueOf(rules.getInt(key));
                GameRules.IntegerValue def = type.createRule();
                String catKey = key.getCategory() != null
                        ? key.getCategory().getDescriptionId()
                        : "gamerule.category.misc";
                entries.add(new GameRuleEntry(key.getId(), key.getDescriptionId(), catKey,
                        false, String.valueOf(def.get()), cur));
            }
        });
        return new OpenGameRuleEditorPacket(entries);
    }
}
