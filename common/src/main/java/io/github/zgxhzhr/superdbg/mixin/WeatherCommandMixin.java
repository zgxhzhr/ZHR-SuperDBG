package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.WeatherCommand;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 {@code /weather thunder} 从 EclipticSeasons（节气模组）的劫持中抢回原版行为。
 *
 * <p>现象：整合包里执行 {@code /weather thunder} 毫无反应，而 {@code clear} 与 {@code rain} 正常。</p>
 *
 * <p>成因（来自对 EclipticSeasons-1.20.1-forge-0.10-pre10-2-all.jar 的字节码核对）：
 * 其 {@code com.teamtea.eclipticseasons.mixin.common.command.MixinWeatherCommand} 对原版
 * {@code WeatherCommand.setClear} / {@code setRain} 都先判断 {@code EclipticUtil.useSolarWeather()}，
 * 该配置为 false 时放行进原版，唯独 {@code setThunder} 没有任何判断，直接写自己的天气管理器后
 * {@code setReturnValue(0)}，使原版的 {@code ServerLevel#setWeatherParameters(0, …​, true, true)}
 * 永不执行；而它自己的天气系统在 {@code UseSolarWeather = false}（当前配置）下并不生效，
 * 于是雷雨永远切不过去。</p>
 *
 * <p>对策：这里以高于对方（默认 priority 1000）的优先级在 HEAD 先按原版语义把天气真正写进世界并
 * 取消原方法，之后对方再取消一次也不影响结果，反馈消息也照原版发出。注入的是同一个方法，逻辑与
 * 原版 {@code setThunder} 完全一致，因此未安装 EclipticSeasons 时行为不变。</p>
 */
@Mixin(value = WeatherCommand.class, priority = 2000)
public abstract class WeatherCommandMixin {

    @Inject(
            method = "setThunder(Lnet/minecraft/commands/CommandSourceStack;I)I",
            at = @At("HEAD"),
            cancellable = true)
    private static void superdbg$setThunderWithoutHijack(CommandSourceStack source, int time,
                                                        CallbackInfoReturnable<Integer> cir) {
        ServerLevel level = source.getLevel();
        int duration = time == -1 ? ServerLevel.THUNDER_DURATION.sample(level.getRandom()) : time;
        level.setWeatherParameters(0, duration, true, true);
        source.sendSuccess(() -> Component.translatable("commands.weather.set.thunder"), true);
        cir.setReturnValue(time);
    }
}
