package io.github.zgxhzhr.superdbg.mixin;

import com.mojang.brigadier.ParseResults;
import io.github.zgxhzhr.superdbg.Constants;
import io.github.zgxhzhr.superdbg.command.CommandChainParser;
import io.github.zgxhzhr.superdbg.command.CommandChainScheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 命令链：一行里用 {@code \间隔N秒} 串起来的多条命令，按间隔依次执行。
 *
 * <p>例：{@code /tellraw @a "1" \间隔3.2秒 /summon zombie \间隔1.2秒 /tellraw @a "2"}
 * —— 第一条立即执行，之后每条在上一条的基础上再等待指定秒数。</p>
 *
 * <p>注入 {@code Commands#performCommand}：这是原版所有命令执行的唯一汇聚点——
 * 聊天栏由 {@code ServerGamePacketListenerImpl#performChatCommand} 直接调用它，
 * 命令方块（含命令方块矿车、告示牌）、控制台与 RCON 则先调
 * {@code performPrefixedCommand} 再委托给它。因此只挂这一个入口即可覆盖全指令链路，
 * 且拿到的是拆分前的原始命令串。拆分后的每条命令仍走原方法执行，
 * 权限、来源与执行位置完全不变。</p>
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(
            method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)I",
            at = @At("HEAD"),
            cancellable = true
    )
    private void superdbg$runCommandChain(ParseResults<CommandSourceStack> parseResults, String command,
                                          CallbackInfoReturnable<Integer> cir) {
        // 未使用分隔标记、或拆分后只有一条：交回原版逻辑原样执行
        List<CommandChainParser.Segment> segments =
                CommandChainScheduler.plan((Commands) (Object) this, command);
        if (segments == null) {
            return;
        }
        CommandSourceStack source = parseResults.getContext().getSource();
        // 低频的手动操作，留一条日志便于确认服务端确实接管了命令链
        Constants.LOG.info("[SuperDbg] 命令链接管：共 {} 段，各段延迟(刻)={}",
                segments.size(), segments.stream().map(CommandChainParser.Segment::delayTicks).toList());
        cir.setReturnValue(CommandChainScheduler.run(source, segments));
    }
}
