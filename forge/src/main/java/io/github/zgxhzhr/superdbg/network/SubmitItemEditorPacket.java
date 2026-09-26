package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.item.ItemEditorData;
import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import io.github.zgxhzhr.superdbg.menu.ItemEditorMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端→服务端：物品编辑器全量提交。
 * <p>
 * 一次性携带名称、标志位、附魔、属性，服务端原子写回，
 * 所有数值在服务端重新校验/clamp，不信任客户端。
 */
public record SubmitItemEditorPacket(String customName,
                                     boolean unbreakable,
                                     boolean debug,
                                     Map<ResourceLocation, Integer> enchantments,
                                     Map<ResourceLocation, Double> attributes) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(customName);
        buf.writeBoolean(unbreakable);
        buf.writeBoolean(debug);

        buf.writeVarInt(enchantments.size());
        for (Map.Entry<ResourceLocation, Integer> e : enchantments.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeVarInt(e.getValue());
        }

        buf.writeVarInt(attributes.size());
        for (Map.Entry<ResourceLocation, Double> e : attributes.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeDouble(e.getValue());
        }
    }

    public static SubmitItemEditorPacket decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        boolean unbreakable = buf.readBoolean();
        boolean debug = buf.readBoolean();

        int enchCount = buf.readVarInt();
        Map<ResourceLocation, Integer> ench = new LinkedHashMap<>();
        for (int i = 0; i < enchCount; i++) {
            ench.put(buf.readResourceLocation(), buf.readVarInt());
        }

        int attrCount = buf.readVarInt();
        Map<ResourceLocation, Double> attrs = new LinkedHashMap<>();
        for (int i = 0; i < attrCount; i++) {
            attrs.put(buf.readResourceLocation(), buf.readDouble());
        }
        return new SubmitItemEditorPacket(name, unbreakable, debug, ench, attrs);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;

        // 必须仍然开着物品编辑器且处于创造模式
        if (!player.isCreative()) return;
        if (!(player.containerMenu instanceof ItemEditorMenu)) return;

        ItemStack offhand = player.getOffhandItem();
        if (offhand.isEmpty()) return;

        ItemEditorData data = new ItemEditorData();
        data.customName = customName;
        data.unbreakable = unbreakable;
        data.debug = debug;
        data.enchantments.putAll(enchantments);
        data.attributes.putAll(attributes);

        // apply 内部完成所有 clamp、registry 存在性校验、调试斧强制规则
        ItemEditorService.apply(offhand, data);

        player.getInventory().setChanged();
        // 客户端点确认后会立即关闭界面：编辑器菜单（非 0 容器 id）的槽位同步包会被
        // 已关闭界面的客户端丢弃，且 doCloseContainer 的 transferState 会静默对齐
        // inventoryMenu 快照导致之后每 tick 广播认为无变化。因此必须再经
        // inventoryMenu（containerId=0）同步一次，副手 NBT 才能真正到达客户端。
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }
}
