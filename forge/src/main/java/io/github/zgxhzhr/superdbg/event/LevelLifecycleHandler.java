package io.github.zgxhzhr.superdbg.event;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 世界生命周期事件：关卡卸载时清理 RemovalGuard 中与该维度相关的 static 状态，
 * 避免退出重进后新旧实体数据混淆（SNAPSHOTS 强引用残留、常加载票据锚到旧 level 等）。
 */
public class LevelLifecycleHandler {

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            RemovalGuard.onLevelUnload(serverLevel);
        }
    }
}
