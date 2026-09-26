package io.github.zgxhzhr.superdbg.client;

import io.github.zgxhzhr.superdbg.network.OpenGameRuleEditorPacket;
import io.github.zgxhzhr.superdbg.client.gui.GameRuleEditorScreen;
import io.github.zgxhzhr.superdbg.client.gui.QuickActionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 客户端侧 GUI 入口：服务端推包时打开屏幕或刷新数据。
 */
public final class ClientGuiHandler {

    private ClientGuiHandler() {
    }

    public static void openGameRuleEditor(OpenGameRuleEditorPacket pkt) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new GameRuleEditorScreen(pkt));
    }

    /** 收到服务端下发的全量维度列表：缓存并刷新已打开的快捷指令面板 */
    public static void applyDimensionList(List<ResourceLocation> dimensions) {
        QuickActionScreen.setServerDimensions(dimensions);
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen instanceof QuickActionScreen qas) {
            qas.updateDimensions(dimensions);
        }
    }
}
