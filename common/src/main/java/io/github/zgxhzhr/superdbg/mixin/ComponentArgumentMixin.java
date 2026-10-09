package io.github.zgxhzhr.superdbg.mixin;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.zgxhzhr.superdbg.command.TellRawScheduler;
import io.github.zgxhzhr.superdbg.command.TellRawStyleParser;
import io.github.zgxhzhr.superdbg.command.TellRawStyledMessage;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * tellraw 文本格式化扩展的注入点。
 *
 * <p>做法是接管原版 {@link ComponentArgument#parse} 的解析过程，而不是替换命令树里
 * 该参数的类型。原因是：服务端登录时要把整棵命令树序列化成
 * {@code ClientboundCommandsPacket} 发给客户端，序列化时必须能在
 * {@code ArgumentTypeInfos} 里查到每个参数类型的注册项；若换成未注册的自定义
 * ArgumentType，这里会抛 {@code IllegalArgumentException: Unrecognized argument type}，
 * 导致 {@code Couldn't place player in world}、玩家被踢（客户端显示「无效的玩家数据」）。
 * 保持原版类型后，命令同步完全不受影响。</p>
 *
 * <p>只有在输入确实使用了扩展语法时才接管（见
 * {@link TellRawStyleParser#looksStyled}），其余输入一律交回原版逻辑，
 * 因此纯 JSON、单个引号串、裸词以及其它命令的文本组件参数都不受影响。</p>
 *
 * <p>多段消息的后续段不在这里发送：本方法只返回第一段的组件交给原版 {@code /tellraw} 立即发出，
 * 整份段列表暂存到 {@link TellRawScheduler}，等命令真正执行时由
 * {@code EntityArgumentMixin} 取走并按各段延迟补发。</p>
 */
@Mixin(ComponentArgument.class)
public abstract class ComponentArgumentMixin {

    @Inject(
            // 必须带描述符：该类同时存在覆写方法与 javac 生成的桥接方法 parse(StringReader)Object，
            // 只写方法名会命中两个目标而在运行时判定为歧义
            method = "parse(Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"),
            cancellable = true)
    private void superdbg$parseStyledMessage(StringReader reader, CallbackInfoReturnable<Component> cir)
            throws CommandSyntaxException {
        String raw = reader.getRemaining();
        if (!TellRawStyleParser.looksStyled(raw)) {
            // 非扩展写法：清掉可能残留的解析结果，避免上一次多段命令的后续段被误发给这次的目标
            TellRawScheduler.hold(null);
            return;
        }
        // 扩展语法由本模组独吞整段剩余输入：原版在这种写法下必定报「多余参数」错，
        // 因此消费掉全部剩余内容不会遮蔽任何原本合法的解析
        reader.setCursor(reader.getTotalLength());
        TellRawStyledMessage message = TellRawStyleParser.parseMessage(raw);
        TellRawScheduler.hold(message);
        cir.setReturnValue(message.first());
    }
}
