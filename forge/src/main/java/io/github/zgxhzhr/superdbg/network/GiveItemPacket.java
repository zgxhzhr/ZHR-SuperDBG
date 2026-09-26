package io.github.zgxhzhr.superdbg.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端→服务端：实体编辑器「获取」页的快速发放请求。
 * <p>
 * 每条 = 物品 + 总数量 + 每组个数（可超原版堆叠上限，最高 999，
 * 由 Count 序列化 Mixin 保证存档/网络不截断）+ 可选 NBT（如附魔书）。
 * 服务端按组拆分后直接放入目标玩家背包空槽（不与既有堆叠合并），
 * 放不下则掉落在脚下。
 */
public record GiveItemPacket(int entityId, List<Entry> entries) {

    public record Entry(ResourceLocation item, int count, int stackSize, CompoundTag tag) {
    }

    /** 单条/单次发放的硬上限，防误填天文数字卡服 */
    private static final int MAX_COUNT = 100_000;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeResourceLocation(e.item());
            buf.writeVarInt(e.count());
            buf.writeVarInt(e.stackSize());
            buf.writeNbt(e.tag());
        }
    }

    public static GiveItemPacket decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readResourceLocation(), buf.readVarInt(),
                    buf.readVarInt(), buf.readNbt()));
        }
        return new GiveItemPacket(entityId, entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null || !sender.isCreative()) {
                return;
            }
            // 目标是玩家（自己或其它玩家都允许），按实体 id 在玩家列表里查
            ServerPlayer target = null;
            for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
                if (p.getId() == entityId) {
                    target = p;
                    break;
                }
            }
            if (target == null) {
                sender.displayClientMessage(Component.literal("§c目标玩家不在线"), false);
                return;
            }
            int total = 0;
            for (Entry e : entries) {
                Item item = BuiltInRegistries.ITEM.get(e.item());
                if (item == Items.AIR) {
                    continue;
                }
                int count = Math.max(1, Math.min(MAX_COUNT, e.count()));
                int stackSize = Math.max(1, Math.min(999, e.stackSize()));
                int remaining = count;
                while (remaining > 0) {
                    int n = Math.min(remaining, stackSize);
                    ItemStack stack = new ItemStack(item, n);
                    // 透传选择器带回的 NBT（如附魔书 StoredEnchantments）
                    if (e.tag() != null) {
                        stack.setTag(e.tag().copy());
                    }
                    // 放背包空槽（保持整组不拆散）；满则掉落在脚下
                    int free = target.getInventory().getFreeSlot();
                    if (free >= 0) {
                        target.getInventory().setItem(free, stack);
                    } else {
                        target.drop(stack, false);
                    }
                    remaining -= n;
                }
                total += count;
            }
            int given = total;
            sender.displayClientMessage(Component.literal(
                    "§a已发放 §e" + given + "§a 个物品给 §e" + target.getName().getString()), false);
            if (target != sender) {
                target.displayClientMessage(Component.literal(
                        "§a[调试器] 你收到了 §e" + given + "§a 个物品"), false);
            }
        });
        ctx.setPacketHandled(true);
    }
}
