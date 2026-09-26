package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.potion.PotionEditors;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/**
 * 客户端→服务端：请求打开药水编辑器。
 * <p>
 * 客户端在取消右键事件后发送此包，服务端校验条件后打开菜单。
 */
public record OpenEditorPacket() {

    public void encode(FriendlyByteBuf buf) {
        // 无数据
    }

    public static OpenEditorPacket decode(FriendlyByteBuf buf) {
        return new OpenEditorPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleOnServer(ctx));
        ctx.setPacketHandled(true);
    }

    private void handleOnServer(NetworkEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) return;

        if (!player.isCreative()) return;
        if (!player.isShiftKeyDown()) return;
        if (!player.getMainHandItem().isEmpty()) return;

        ItemStack offhand = player.getOffhandItem();
        if (!PotionEditors.canEdit(offhand)) return;

        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return offhand.getHoverName();
            }

            @Override
            public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player p) {
                return new io.github.zgxhzhr.superdbg.menu.PotionEditorMenu(windowId, inventory);
            }
        };
        NetworkHooks.openScreen(player, provider);
        Constants.LOG.debug("玩家 {} 请求打开药水编辑器", player.getName().getString());
    }
}
