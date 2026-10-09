package io.github.zgxhzhr.superdbg;

import io.github.zgxhzhr.superdbg.event.TellRawDelayHandler;
import net.fabricmc.api.ModInitializer;

public class SuperDebuggerMod implements ModInitializer {

    @Override
    public void onInitialize() {
        Constants.LOG.info("Loading {} Fabric module", Constants.MOD_NAME);
        CommonClass.init();
        // 多段 tellraw 的延迟段要靠每 tick 推进一次待发队列才会发出去
        TellRawDelayHandler.register();
    }
}
