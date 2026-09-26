package io.github.zgxhzhr.superdbg.menu;

import io.github.zgxhzhr.superdbg.init.ModMenus;
import io.github.zgxhzhr.superdbg.potion.PotionEffectData;
import io.github.zgxhzhr.superdbg.potion.PotionEditorProvider;
import io.github.zgxhzhr.superdbg.potion.PotionEditors;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 药水编辑器菜单。
 * <p>
 * 不包含物品槽，仅承载药水效果数据供 Screen 渲染与编辑。
 * 效果列表从玩家副手药水读取，编辑时由 Screen 发送网络包到服务端写回。
 */
public class PotionEditorMenu extends AbstractContainerMenu {

    private final Player player;
    private final List<PotionEffectData> effects;

    /**
     * 服务端构造。
     */
    public PotionEditorMenu(int windowId, Inventory inventory) {
        this(windowId, inventory.player);
    }

    /**
     * 客户端构造（由 MenuType 工厂调用）。
     */
    public PotionEditorMenu(int windowId, Inventory inventory, FriendlyByteBuf buf) {
        this(windowId, inventory.player);
    }

    private PotionEditorMenu(int windowId, Player player) {
        super(ModMenus.POTION_EDITOR.get(), windowId);
        this.player = player;
        this.effects = loadEffects(player);

        // 添加副手槽（不可见、不可交互），仅用于服务端→客户端物品同步
        // broadcastChanges() 会检测此槽的 NBT 变化并自动发送给客户端
        this.addSlot(new Slot(player.getInventory(), 40, -100, -100) {
            @Override
            public boolean mayPlace(ItemStack stack) { return false; }

            @Override
            public boolean mayPickup(Player player) { return false; }

            @Override
            public boolean isActive() { return false; }
        });
    }

    private static List<PotionEffectData> loadEffects(Player player) {
        ItemStack offhand = player.getOffhandItem();
        PotionEditorProvider editor = PotionEditors.find(offhand);
        if (editor == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(editor.readEffects(offhand));
    }

    /**
     * 当前编辑的药水效果列表。
     */
    public List<PotionEffectData> getEffects() {
        return effects;
    }

    /**
     * 客户端编辑时本地更新列表（乐观更新）。
     * 服务端真实写回由网络包处理。
     */
    public void updateEffectLocal(int index, int amplifier, int duration) {
        if (index < 0 || index >= effects.size()) {
            return;
        }
        PotionEffectData old = effects.get(index);
        effects.set(index, new PotionEffectData(
                old.effect(), amplifier, duration,
                old.ambient(), old.visible(), old.showIcon()
        ));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        // 创造模式且副手仍为可编辑药水时保持有效
        return player.isCreative() && PotionEditors.canEdit(player.getOffhandItem());
    }
}
