package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.menu.ItemEditorMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/**
 * 客户端→服务端：请求打开通用物品编辑器。
 * <p>
 * 触发：创造模式 + 副手持有物品 + 主手为空 + Shift + 鼠标中键。
 */
public record OpenItemEditorPacket() {

    public void encode(FriendlyByteBuf buf) {
    }

    public static OpenItemEditorPacket decode(FriendlyByteBuf buf) {
        return new OpenItemEditorPacket();
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
        if (offhand.isEmpty()) return;

        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return offhand.getHoverName();
            }

            @Override
            public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player p) {
                return new ItemEditorMenu(windowId, inventory);
            }
        };
        NetworkHooks.openScreen(player, provider);
        Constants.LOG.debug("玩家 {} 请求打开物品编辑器", player.getName().getString());
    }
}
