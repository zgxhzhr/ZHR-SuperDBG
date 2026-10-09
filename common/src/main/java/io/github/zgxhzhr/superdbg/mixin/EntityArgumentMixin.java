package io.github.zgxhzhr.superdbg.mixin;

import com.mojang.brigadier.context.CommandContext;
import io.github.zgxhzhr.superdbg.command.TellRawScheduler;
import io.github.zgxhzhr.superdbg.command.TellRawStyledMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * 多段 tellraw 后续段的投递点。
 *
 * <p>{@link io.github.zgxhzhr.superdbg.mixin.ComponentArgumentMixin} 只把第一段交给原版
 * {@code /tellraw} 立即发送，剩下的段要靠服务端按各自延迟补发。原版的发送循环写在
 * {@code TellRawCommand#register} 的 lambda 里，没有可注入的方法；但循环之前必定调用
 * {@link EntityArgument#getPlayers} 取一次目标玩家，因此在这里拿目标列表是可行的：
 * 该调用在整条 tellraw 命令里只发生一次，命中点明确。</p>
 *
 * <p>三重校验用来防止补全阶段或别的命令误触发投递：参数名必须是 {@code targets}、暂存区里
 * 必须真的有一次多段解析结果、且当前命令的根命令必须是 {@code tellraw}。
 * 第三条是必要的——{@code /title <targets> title <title>} 的第一个参数名同样是
 * {@code targets}，而标题没有「多条依次显示」的语义，不能把它也当成多段聊天处理。</p>
 */
@Mixin(EntityArgument.class)
public abstract class EntityArgumentMixin {

    @Inject(method = "getPlayers", at = @At("RETURN"))
    private static void superdbg$deliverDelayedTellRaw(
            CommandContext<CommandSourceStack> context, String name,
            CallbackInfoReturnable<Collection<ServerPlayer>> cir) {
        if (!"targets".equals(name)) {
            return;
        }
        TellRawStyledMessage message = TellRawScheduler.take();
        if (message == null || !message.isMultiPart()) {
            return;
        }
        if (!isTellRawCommand(context)) {
            return;
        }
        TellRawScheduler.deliver(cir.getReturnValue(), message);
    }

    /** 判断当前命令的根命令是否为 {@code tellraw}，并兼容 {@code /execute ... run tellraw ...}。 */
    private static boolean isTellRawCommand(CommandContext<CommandSourceStack> context) {
        String[] tokens = context.getInput().trim().split("\\s+");
        if (tokens.length == 0) {
            return false;
        }
        int start = 0;
        if (isCommandNamed(tokens[0], "execute")) {
            // execute 会先执行子命令再对外层求值，真正的 tellraw 在 run 之后
            for (int i = 1; i < tokens.length; i++) {
                if ("run".equals(tokens[i])) {
                    start = i + 1;
                    break;
                }
            }
        }
        return start < tokens.length && isCommandNamed(tokens[start], "tellraw");
    }

    /** 比较命令词是否等于给定名字，忽略前导斜杠与 {@code minecraft:} 命名空间。 */
    private static boolean isCommandNamed(String token, String expected) {
        String name = token.startsWith("/") ? token.substring(1) : token;
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return expected.equals(name);
    }
}
