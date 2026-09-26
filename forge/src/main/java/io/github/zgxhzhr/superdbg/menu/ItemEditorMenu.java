package io.github.zgxhzhr.superdbg.menu;

import io.github.zgxhzhr.superdbg.init.ModMenus;
import io.github.zgxhzhr.superdbg.item.ItemEditorData;
import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 通用物品编辑器菜单。
 * <p>
 * 编辑数据从玩家副手物品读取（双端各自读取本地同步的副手物品）。
 * 副手槽以不可见形式加入菜单，使 NBT 写回后能经 broadcastChanges 同步。
 */
public class ItemEditorMenu extends AbstractContainerMenu {

    private final Player player;
    private final ItemEditorData data;

    /** 服务端构造（MenuType 工厂） */
    public ItemEditorMenu(int windowId, Inventory inventory) {
        this(windowId, inventory.player);
    }

    /** 客户端构造（IForgeMenuType 工厂） */
    public ItemEditorMenu(int windowId, Inventory inventory, FriendlyByteBuf buf) {
        this(windowId, inventory.player);
    }

    private ItemEditorMenu(int windowId, Player player) {
        super(ModMenus.ITEM_EDITOR.get(), windowId);
        this.player = player;
        this.data = ItemEditorService.read(player.getOffhandItem());

        // 不可见副手槽：仅用于 NBT 变更同步
        this.addSlot(new Slot(player.getInventory(), 40, -100, -100) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public boolean isActive() {
                return false;
            }
        });
    }

    public ItemEditorData getData() {
        return data;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isCreative() && !player.getOffhandItem().isEmpty();
    }
}
