package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.OpenEditorPacket;
import io.github.zgxhzhr.superdbg.potion.PotionEditors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 玩家交互处理：药水编辑器触发 + 守卫女仆收容拦截。
 */
public class PlayerInteractHandler {

    /** TLM 女仆实体类（懒加载：避免在 mod 构造阶段过早 Class.forName 触发 TLM 静态初始化，
     *  干扰其它 mod（如 aquamirae/fragmentum）的注册时序导致启动崩溃）。 */
    private static Class<?> maidClassCache;
    private static boolean maidClassResolved = false;

    /** 获取当前发起收容交互的玩家（供 RemovalGuardMixin 使用）。 */
    @Deprecated
    public static Player getCaptureBlocker() {
        return RemovalGuard.getCaptureBlocker();
    }

    private static boolean isMaid(Entity target) {
        if (!maidClassResolved) {
            maidClassResolved = true;
            try {
                maidClassCache = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
            } catch (ClassNotFoundException ignored) {
                maidClassCache = null;
            }
        }
        return maidClassCache != null && maidClassCache.isInstance(target);
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        tryCancelAndRequest(event);
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        tryCancelAndRequest(event);
    }

    private void tryCancelAndRequest(PlayerInteractEvent event) {
        // 仅处理副手交互
        if (event.getHand() != InteractionHand.OFF_HAND) {
            return;
        }

        Player player = event.getEntity();
        if (!player.isCreative()) {
            return;
        }
        if (!player.isShiftKeyDown()) {
            return;
        }

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offhand = player.getOffhandItem();
        if (!mainHand.isEmpty()) {
            return;
        }
        if (!PotionEditors.canEdit(offhand)) {
            return;
        }

        // 满足条件，取消原交互（客户端+服务端均取消，防止喝药）
        event.setCanceled(true);

        // 客户端发送 C2S 包请求打开菜单
        if (event.getLevel().isClientSide()) {
            NetworkHandler.CHANNEL.sendToServer(new OpenEditorPacket());
        }
    }

    /**
     * 守卫女仆收容拦截：玩家用 TLM 魂符（空魂符/初始魂符）或相机右键守卫女仆时取消收容，
     * 提示先关闭防移除。收容与防移除语义冲突（收容要删实体，防移除不让删），在入口拦截避免
     * 客户端幽灵/透明/默认模型（同 UUID 冲突）。
     */
    private void checkGuardedCapture(PlayerInteractEvent event, Entity target, ItemStack stack) {
        try {
            boolean maid = target != null && isMaid(target);
            boolean guarded = target instanceof LivingEntity le && RemovalGuard.has(le);
            ResourceLocation itemKey = stack.isEmpty() ? null
                    : ForgeRegistries.ITEMS.getKey(stack.getItem());
            io.github.zgxhzhr.superdbg.Constants.LOG.info("[SuperDbg] 收容拦截检查 side={} target={} isMaid={} has={} item={}",
                    event.getLevel().isClientSide() ? "client" : "server",
                    target != null ? target.getClass().getName() : "null",
                    maid, guarded, itemKey);
            if (target == null || !maid || !guarded) {
                return;
            }
            // 守卫女仆 + TLM 物品 → 先登记当前玩家（供 Mixin discard 拦截时精确提示）
            if (itemKey != null && "touhou_little_maid".equals(itemKey.getNamespace())) {
                String path = itemKey.getPath();
                if (path.equals("smart_slab_empty") || path.equals("smart_slab_init") || path.equals("camera")) {
                    Player player = event.getEntity();
                    RemovalGuard.setCaptureBlocker(player);
                    try {
                        if (!event.isCanceled()) {
                            event.setCanceled(true);
                            if (!event.getLevel().isClientSide() && player instanceof ServerPlayer sp) {
                                sp.sendSystemMessage(Component.literal("无法收容：该女仆已开启防移除，请先在实体编辑器中关闭防移除再收容").withStyle(ChatFormatting.RED));
                            }
                        }
                    } finally {
                        // 不立即清理——相机绕过事件时，Mixin discard 在同线程后续调用，需要保留引用
                        // 由 Mixin 拦截后清理
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] 守卫女仆收容拦截异常，放行本次交互", t);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onInteractGuardedMaidSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        checkGuardedCapture(event, event.getTarget(), event.getItemStack());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onInteractGuardedMaid(PlayerInteractEvent.EntityInteract event) {
        checkGuardedCapture(event, event.getTarget(), event.getItemStack());
    }
}
