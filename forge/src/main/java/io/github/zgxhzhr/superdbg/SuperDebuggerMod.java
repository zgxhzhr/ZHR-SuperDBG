package io.github.zgxhzhr.superdbg;

import io.github.zgxhzhr.superdbg.compat.GameRuleRegistry;
import io.github.zgxhzhr.superdbg.init.ModMenus;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.event.LevelLifecycleHandler;
import io.github.zgxhzhr.superdbg.event.PlayerInteractHandler;
import io.github.zgxhzhr.superdbg.event.PseudoCreativeHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 超级调试器模组 Forge 入口。
 */
@Mod(Constants.MOD_ID)
public class SuperDebuggerMod {

    public SuperDebuggerMod() {
        Constants.LOG.info("Loading {} Forge module", Constants.MOD_NAME);

        CommonClass.init();

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModMenus.MENU_TYPES.register(modBus);

        NetworkHandler.register();

        MinecraftForge.EVENT_BUS.register(new PlayerInteractHandler());
        MinecraftForge.EVENT_BUS.register(new LevelLifecycleHandler());
        MinecraftForge.EVENT_BUS.register(new PseudoCreativeHandler());

        // 服务端启动时遍历注册全部 gamerule（含模组自定义），构建 id→Key 映射表
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> GameRuleRegistry.build());
    }
}
