package io.github.zgxhzhr.superdbg.menu;

import io.github.zgxhzhr.superdbg.compat.l2hostility.L2HostilityCompat;
import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.entity.EntityEditorService;
import io.github.zgxhzhr.superdbg.init.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 实体编辑器菜单。
 * <p>
 * 服务端构造直接持有目标实体（provider.createMenu 创建）；
 * 客户端构造从 extraData buf 读取实体 id 与数据快照（服务端生成，
 * 客户端读不到未同步的属性）。
 * <p>
 * 无 Slot：实体不是物品，属性/效果变更由原版机制自动同步，无需广播兜底。
 */
public class EntityEditorMenu extends AbstractContainerMenu {

    private final LivingEntity target;
    private final EntityEditorData data;
    /** 客户端侧无法拿到实体实例时的降级引用（仅记录 id） */
    private final int entityId;

    /** 服务端构造（MenuProvider.createMenu 直接调用） */
    public EntityEditorMenu(int windowId, Inventory inventory, LivingEntity target) {
        super(ModMenus.ENTITY_EDITOR.get(), windowId);
        this.target = target;
        this.entityId = target.getId();
        // 玩家可"移除"（走死亡流程）；非玩家生物中女仆受保护
        boolean removable = target instanceof Player || !EntityEditorService.isProtectedMaid(target);
        this.data = new EntityEditorData(entityId, removable,
                target.getHealth(),
                EntityEditorService.readAttributes(target),
                EntityEditorService.readEffects(target),
                L2HostilityCompat.readTraits(target),
                L2HostilityCompat.readLevel(target),
                io.github.zgxhzhr.superdbg.entity.RemovalGuard.has(target),
                io.github.zgxhzhr.superdbg.compat.curios.CuriosCompat.readSlots(target),
                io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.snapshot(target),
                EntityEditorService.readTrades(target),
                EntityEditorService.readEntityLoot(target),
                EntityEditorService.readTypeLoot(target),
                io.github.zgxhzhr.superdbg.loot.VanillaLootParser.parse(target),
                EntityEditorService.readGiftPool(target));
    }

    /** 客户端构造（IForgeMenuType 工厂，从 extraData 读取） */
    public EntityEditorMenu(int windowId, Inventory inventory, FriendlyByteBuf buf) {
        super(ModMenus.ENTITY_EDITOR.get(), windowId);
        this.target = null;
        this.entityId = buf.readVarInt();
        this.data = EntityEditorData.readSnapshot(buf, entityId);
    }

    /** 服务端：目标实体 */
    public LivingEntity getTarget() {
        return target;
    }

    public int getEntityId() {
        return entityId;
    }

    /** 打开时的数据快照（双端一致） */
    public EntityEditorData getData() {
        return data;
    }

    @Override
    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!player.isCreative()) {
            return false;
        }
        // 客户端拿不到实体实例，跳过实体侧检查防止界面误关；
        // 安全校验全部在服务端（打开包与提交包双重校验）
        if (target == null) {
            return true;
        }
        return target.isAlive() && EntityEditorService.canEdit(target, player);
    }
}
