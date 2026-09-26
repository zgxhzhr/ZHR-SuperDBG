package io.github.zgxhzhr.superdbg.client;

import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.client.gui.EntityEditorScreen;
import io.github.zgxhzhr.superdbg.client.gui.ItemEditorScreen;
import io.github.zgxhzhr.superdbg.client.gui.PotionEditorScreen;
import io.github.zgxhzhr.superdbg.init.ModMenus;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.OpenItemEditorPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 客户端初始化事件 + HUD 药水效果等级扩展渲染。
 */
@Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientInit {

    private ClientInit() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.POTION_EDITOR.get(), PotionEditorScreen::new);
            MenuScreens.register(ModMenus.ITEM_EDITOR.get(), ItemEditorScreen::new);
            MenuScreens.register(ModMenus.ENTITY_EDITOR.get(), EntityEditorScreen::new);
        });
    }

    /**
     * 物品编辑器触发条件：
     * 创造模式 + 副手持有物品 + 主手为空 + Shift + 鼠标中键。
     */
    private static boolean canOpenItemEditor(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.screen != null) return false;
        if (!player.getAbilities().instabuild) return false;
        if (!Screen.hasShiftDown()) return false;
        if (player.getOffhandItem().isEmpty()) return false;
        return player.getMainHandItem().isEmpty();
    }

    /**
     * 中键原始输入：任何瞄准状态下都能打开物品编辑器
     * （Forge 的 pickBlock 钩子只在准星指向方块/实体时触发）。
     */
    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
    public static class ItemEditorInputHandler {

        @SubscribeEvent
        public static void onMouseInput(InputEvent.MouseButton.Pre event) {
            if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                    || event.getAction() != GLFW.GLFW_PRESS) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (canOpenItemEditor(mc)) {
                NetworkHandler.CHANNEL.sendToServer(new OpenItemEditorPacket());
            }
        }

        /**
         * 取消原版中键拾取方块行为，避免主手被塞入方块物品。
         */
        @SubscribeEvent
        public static void onPickBlock(InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isPickBlock()) return;
            Minecraft mc = Minecraft.getInstance();
            if (canOpenItemEditor(mc)) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }
    }

    /**
     * 退出世界时清空客户端属性同步缓存，避免换存档串值。
     */
    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
    public static class AttributeCacheLifecycleHandler {

        @SubscribeEvent
        public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            io.github.zgxhzhr.superdbg.client.ClientAttributeEnforcer.reset();
        }
    }

    /**
     * 在 EffectRenderingInventoryScreen 渲染完后，
     * 对 amplifier >= 10 的药水效果补画等级文字（原版不显示）。
     * amplifier 0-9 原版已处理。
     */
    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
    public static class HudEffectLevelHandler {

        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (!(event.getScreen() instanceof EffectRenderingInventoryScreen<?> screen)) return;
            if (mc.player == null) return;

            GuiGraphics guiGraphics = event.getGuiGraphics();
            int leftPos = screen.getGuiLeft();
            int topPos = screen.getGuiTop();
            int imageWidth = screen.getXSize();

            // 可用宽度判断（与原版 renderEffects 一致）
            int i = leftPos + imageWidth + 2;
            int j = screen.width - i;
            if (j < 120) return; // 非紧凑模式（紧凑模式不渲染 labels）

            Collection<MobEffectInstance> effects = mc.player.getActiveEffects();
            if (effects.isEmpty()) return;

            // 排序（原版 renderLabels 用的是 filtered + sorted）
            List<MobEffectInstance> sorted = new ArrayList<>(effects);
            sorted.sort((a, b) -> {
                boolean aBenef = a.getEffect().isBeneficial();
                boolean bBenef = b.getEffect().isBeneficial();
                if (aBenef != bBenef) return aBenef ? -1 : 1;
                return Integer.compare(b.getDuration(), a.getDuration());
            });

            // 原版 renderLabels 里的 x/y 偏移
            int renderX = i + 18; // 图标宽度 18px + 偏移
            int yOffset = 33;
            int row = 0;

            for (MobEffectInstance inst : sorted) {
                int amp = inst.getAmplifier();
                if (amp < 10) {
                    // amplifier 0-9 原版已显示等级，跳过
                    row++;
                    continue;
                }

                // amplifier >= 10：原版只画了效果名（不带等级），
                // 我们只补画 " 等级" 部分，位置在原版效果名右边
                int y = topPos + row * yOffset + 6;

                // 原版画的效果名（不带等级）
                Component effectNameOnly = inst.getEffect().getDisplayName();
                // 计算原版效果名宽度，然后追加空格+等级
                int nameWidth = mc.font.width(effectNameOnly);

                Component level = Component.literal(" " + (amp + 1));
                guiGraphics.drawString(mc.font, level, renderX + 10 + nameWidth, y, 16777215, false);

                row++;
            }
        }
    }
}
