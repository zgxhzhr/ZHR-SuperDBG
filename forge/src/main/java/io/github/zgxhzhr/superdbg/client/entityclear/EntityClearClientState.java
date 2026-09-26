package io.github.zgxhzhr.superdbg.client.entityclear;

import io.github.zgxhzhr.superdbg.entityclear.EntityClearConfig;
import io.github.zgxhzhr.superdbg.client.gui.EntityClearConfirmScreen;
import io.github.zgxhzhr.superdbg.client.gui.EntityClearScreen;
import io.github.zgxhzhr.superdbg.client.gui.EntityTypeSelectScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Map;

/**
 * 清除器客户端共享状态：保存最近一次服务端扫描结果（各类型可清除数量），
 * 以及"预览"按钮触发的扫描在结果到达后要打开确认界面的待办标记。
 */
public final class EntityClearClientState {

    /** 最近一次扫描对应的配置快照（确认界面按它汇总并执行） */
    private static EntityClearConfig scannedConfig;
    /** 最近一次扫描：通过保护筛选后各类型可清除数量 */
    private static Map<ResourceLocation, Integer> counts = Collections.emptyMap();
    private static int protectedCount;
    /** 预览按钮置位：下一份扫描结果到达后打开确认界面 */
    private static boolean awaitingConfirm;

    private EntityClearClientState() {
    }

    public static Map<ResourceLocation, Integer> counts() {
        return counts;
    }

    public static int protectedCount() {
        return protectedCount;
    }

    public static EntityClearConfig scannedConfig() {
        return scannedConfig;
    }

    /** 发起一次扫描；preview=true 时结果到达后打开确认界面 */
    public static void requestScan(EntityClearConfig config, boolean preview) {
        scannedConfig = config.copy();
        awaitingConfirm = preview;
        io.github.zgxhzhr.superdbg.network.NetworkHandler.CHANNEL.sendToServer(
                new io.github.zgxhzhr.superdbg.network.EntityClearPacket(false, scannedConfig));
    }

    /** S2C 结果入口（已在主线程排队执行） */
    public static void acceptResult(boolean executed, int protectedSkipped,
                                   Map<ResourceLocation, Integer> result) {
        counts = result;
        protectedCount = protectedSkipped;
        Minecraft mc = Minecraft.getInstance();
        if (executed) {
            int total = result.values().stream().mapToInt(Integer::intValue).sum();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "§a已清除 §e" + total + "§a 个实体（另保护跳过 §e"
                                + protectedSkipped + "§a 个）"), false);
            }
            if (mc.screen instanceof EntityClearConfirmScreen) {
                mc.setScreen(new EntityClearScreen());
            }
            return;
        }
        if (awaitingConfirm) {
            awaitingConfirm = false;
            mc.setScreen(new EntityClearConfirmScreen());
            return;
        }
        // 后台刷新数量：当前界面即时反映
        if (mc.screen instanceof EntityClearScreen main) {
            main.onCountsUpdated();
        } else if (mc.screen instanceof EntityTypeSelectScreen select) {
            select.onCountsUpdated();
        }
    }
}
