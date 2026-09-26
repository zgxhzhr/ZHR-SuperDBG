package io.github.zgxhzhr.superdbg.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;

/**
 * 实体编辑器的客户端辅助判定（仅客户端侧调用）。
 */
public final class EntityEditorClient {

    private EntityEditorClient() {
    }

    /**
     * 准星是否未对准任何东西（实体与方块都算对准）。
     * 用于区分"右键空气"与"右键无交互方块"——后者必须保持原版行为。
     */
    public static boolean isLookingAtNothing() {
        Minecraft mc = Minecraft.getInstance();
        return mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS;
    }
}
