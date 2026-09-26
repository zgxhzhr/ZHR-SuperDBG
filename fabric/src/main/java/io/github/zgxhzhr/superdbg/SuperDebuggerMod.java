package io.github.zgxhzhr.superdbg;

import net.fabricmc.api.ModInitializer;

public class SuperDebuggerMod implements ModInitializer {

    @Override
    public void onInitialize() {
        Constants.LOG.info("Loading {} Fabric module", Constants.MOD_NAME);
        CommonClass.init();
    }
}
