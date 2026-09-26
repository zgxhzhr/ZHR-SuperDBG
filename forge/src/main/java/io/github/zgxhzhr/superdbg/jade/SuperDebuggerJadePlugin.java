package io.github.zgxhzhr.superdbg.jade;

import net.minecraft.world.entity.LivingEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * 超级调试器的 Jade 插件入口。
 * <p>
 * 通过 {@link WailaPlugin} 注解被 Jade 自动发现，
 * 在 {@link #registerClient} 中注册实体信息 Provider。
 * 参考 L2Hostility 的 {@code JadeCompat} 实现方式。
 */
@WailaPlugin
public class SuperDebuggerJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(new RemovalGuardProvider(), LivingEntity.class);
    }
}
