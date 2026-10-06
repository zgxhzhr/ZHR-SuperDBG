package io.github.zgxhzhr.superdbg.network;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import io.github.zgxhzhr.superdbg.entity.EntityEditorService;
import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import io.github.zgxhzhr.superdbg.menu.EntityEditorMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/**
 * 客户端→服务端：请求打开实体编辑器。
 *
 * @param entityId 目标实体 id；-1 表示编辑者自己（右键空气触发）
 */
public record OpenEntityEditorPacket(int entityId) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
    }

    public static OpenEntityEditorPacket decode(FriendlyByteBuf buf) {
        return new OpenEntityEditorPacket(buf.readVarInt());
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
        if (!player.isCreative()) {
            return;
        }
        // 必须主手持调试斧（右键空气场景服务端无法复验准星，信任此校验）
        if (!ItemEditorService.isDebugAxe(player.getMainHandItem())) {
            return;
        }

        LivingEntity target;
        if (entityId == -1) {
            target = player;
        } else {
            Entity entity = player.level().getEntity(entityId);
            if (!(entity instanceof LivingEntity living)) {
                return;
            }
            target = living;
        }

        // 权限规则：自己始终可调；OP 只能被本人调；普通玩家仅 OP 可调
        if (!EntityEditorService.canEdit(target, player)) {
            Constants.LOG.debug("玩家 {} 无权限编辑目标实体 {}", player.getName().getString(), target.getId());
            return;
        }

        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return target.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player p) {
                return new EntityEditorMenu(windowId, inventory, target);
            }
        };
        // extraData：entityId + removable + 服务端生成的数据快照（客户端读不到未同步属性）
        NetworkHooks.openScreen(player, provider, buf -> {
            buf.writeVarInt(target.getId());
            boolean removable = target instanceof net.minecraft.world.entity.player.Player
                    || !EntityEditorService.isProtectedMaid(target);
            EntityEditorData.writeSnapshot(buf, removable,
                    target.getHealth(),
                    EntityEditorService.readAttributes(target),
                    EntityEditorService.readEffects(target),
                    io.github.zgxhzhr.superdbg.compat.l2hostility.L2HostilityCompat.readTraits(target),
                    io.github.zgxhzhr.superdbg.compat.l2hostility.L2HostilityCompat.readLevel(target),
                    io.github.zgxhzhr.superdbg.entity.RemovalGuard.has(target),
                    io.github.zgxhzhr.superdbg.compat.curios.CuriosCompat.readSlots(target),
                    io.github.zgxhzhr.superdbg.compat.tma.TmaBondCompat.snapshot(target),
                    EntityEditorService.readTrades(target),
                    EntityEditorService.readEntityLoot(target),
                    EntityEditorService.readTypeLoot(target),
                    io.github.zgxhzhr.superdbg.loot.VanillaLootParser.parse(target),
                    EntityEditorService.readGiftPool(target),
                    io.github.zgxhzhr.superdbg.entity.RenderNameStore.get(target),
                    io.github.zgxhzhr.superdbg.compat.playermaid.PlayerMaidCompat.snapshot(target),
                    io.github.zgxhzhr.superdbg.entity.PseudoCreativeState.isEnabled(target));
        });
        Constants.LOG.debug("玩家 {} 打开实体编辑器：目标 {}",
                player.getName().getString(), target.getName().getString());
    }
}
