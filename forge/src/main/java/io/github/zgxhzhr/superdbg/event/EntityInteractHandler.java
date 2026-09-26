package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.OpenEntityEditorPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 实体编辑器交互触发。
 * <p>
 * 触发条件：创造模式 + 主手持调试斧（金斧 + SuperDbgDebug 标记（兼容旧 TiaoshiDebug））。
 * <ul>
 *   <li>右键实体（玩家/生物）：打开该实体面板。<b>必须同时拦截
 *       {@link PlayerInteractEvent.EntityInteractSpecific} 和
 *       {@link PlayerInteractEvent.EntityInteract}</b>——客户端
 *       {@code Minecraft.startUseItem} 右键实体时先发 INTERACT_AT 包、先触发
 *       EntityInteractSpecific，只有它不是 SUCCESS 才会继续触发 EntityInteract。
 *       铁魔法等模组的实体重写 {@code interactAt} 直接开界面，会把第二条链路短路，
 *       只监听 EntityInteract 拦不住；</li>
 *   <li>Shift+右键空气：打开快捷指令面板（维度传送/清除实体/调时间）；</li>
 *   <li>右键空气（不对准实体/方块）：打开自己面板；</li>
 *   <li>右键方块：不触发，保持原版行为。</li>
 * </ul>
 * 全部使用 HIGHEST 优先级 + SUCCESS 取消结果，先于普通模组执行，
 * 既阻止服务端交互逻辑，也阻止客户端预测打开交易/容器界面。
 */
@Mod.EventBusSubscriber(modid = io.github.zgxhzhr.superdbg.Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EntityInteractHandler {

    private EntityInteractHandler() {
    }

    /**
     * HIGHEST：先于一切普通（NORMAL）模组监听器执行并取消事件，
     * 保证调试斧交互不会被其他模组的实体界面（交易/容器等）截胡。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        intercept(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        intercept(event, event.getTarget());
    }

    /** 两个实体交互事件的公共处理：取消交互并打开实体编辑面板 */
    private static void intercept(PlayerInteractEvent event, Entity target) {
        if (!isTriggerable(event)) {
            return;
        }
        // 显式 SUCCESS：默认取消结果是 PASS，Specific 返回 PASS 时客户端仍会继续
        // 尝试 EntityInteract/物品交互，服务端也会继续调用实体交互方法
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide()) {
            NetworkHandler.CHANNEL.sendToServer(new OpenEntityEditorPacket(target.getId()));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!isTriggerable(event)) {
            return;
        }
        // 命中方块（即使方块无交互）时 RightClickItem 也会触发，
        // 只有准星完全落空才是"右键空气"
        if (event.getLevel().isClientSide()) {
            if (!io.github.zgxhzhr.superdbg.client.EntityEditorClient.isLookingAtNothing()) {
                return;
            }
            if (event.getEntity().isShiftKeyDown()) {
                // Shift+右键空气：快捷指令面板（纯客户端界面，动作走独立 C2S 包）
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                io.github.zgxhzhr.superdbg.client.gui.QuickActionScreen.open();
                return;
            }
            NetworkHandler.CHANNEL.sendToServer(new OpenEntityEditorPacket(-1));
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static boolean isTriggerable(PlayerInteractEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return false;
        }
        Player player = event.getEntity();
        if (!player.isCreative()) {
            return false;
        }
        ItemStack mainHand = player.getMainHandItem();
        return ItemEditorService.isDebugAxe(mainHand);
    }
}
