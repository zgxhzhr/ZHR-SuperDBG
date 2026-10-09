package io.github.zgxhzhr.superdbg.mixin;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import io.github.zgxhzhr.superdbg.command.CommandChainParser;
import io.github.zgxhzhr.superdbg.command.TellRawStyleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 客户端命令输入框（聊天栏与命令方块等共用）的两处适配。
 *
 * <ul>
 *   <li><b>用法提示改写</b>：把 tellraw 的 {@code message}、title 的 {@code title} 参数在输入框下方
 *       显示的灰色用法提示换成扩展语法的标记清单。那条提示由 {@code CommandSuggestions#fillNodeUsage}
 *       生成，它把 {@code CommandDispatcher#getSmartUsage} 返回的「节点 → 用法文本」逐条画出来，
 *       文本内容正是 {@code ArgumentCommandNode#getUsageText()} 的 {@code <参数名>}；Brigadier 没有
 *       可自定义用法文案的入口，因此在 {@code fillNodeUsage} 拿到这张表之后、开始绘制之前把文本换掉。
 *       之所以改这里而不是注入 Brigadier 的 {@code getUsageText}：Brigadier 是启动早期就被类加载器
 *       读取的基础库，Mixin 对它注入曾静默失效；{@code CommandSuggestions} 是纯客户端 GUI 类，
 *       加载时机晚且明确，注入可靠。</li>
 *   <li><b>命令链不再误报</b>：{@code \间隔N秒} 命令链是服务端在命令解析之前接管的语法，
 *       客户端命令树里并没有它，因此原版会把整串判为「未知或不完整的命令」并在输入框下方画一行红字。
 *       这行红字只是本地校验提示、不影响命令方块与服务端的实际执行，但会让人误以为语法没被识别。
 *       这里在本模组的输入框侧按同一套切分规则识别命令链，命中时清掉那行红字。</li>
 * </ul>
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    @Shadow
    @Final
    Minecraft minecraft;

    @Shadow
    @Final
    EditBox input;

    @Shadow
    @Final
    private List<FormattedCharSequence> commandUsage;

    /**
     * 改写 {@code fillNodeUsage} 里那张「节点 → 用法文本」表，把需要接管的文本参数换成标记清单。
     * 表中的键是命令树节点、值是原版算好的用法文本，对末尾的文本参数而言就是 {@code <参数名>}。
     */
    @ModifyVariable(method = "fillNodeUsage", at = @At("STORE"))
    private Map<CommandNode<SharedSuggestionProvider>, String> superdbg$styledTextArgumentUsage(
            Map<CommandNode<SharedSuggestionProvider>, String> usages) {
        for (Map.Entry<CommandNode<SharedSuggestionProvider>, String> entry : usages.entrySet()) {
            if (entry.getKey() instanceof ArgumentCommandNode<?, ?> node
                    && node.getType() instanceof ComponentArgument
                    && TellRawStyleRegistry.isStyledTextArgument(node.getName())) {
                entry.setValue(TellRawStyleRegistry.usageHint(node.getName()));
            }
        }
        return usages;
    }

    /**
     * 输入是命令链时清掉「未知或不完整的命令」红字。
     *
     * <p>清理放在 {@code updateUsageInfo}（由补全 future 完成后回调）的末尾，而不是
     * {@code updateCommandInfo} 的末尾：后者只是发起异步补全，真正把红字填进 {@code commandUsage}
     * 的是前者，注入点必须在它之后才有效。</p>
     */
    @Inject(method = "updateUsageInfo", at = @At("TAIL"))
    private void superdbg$hideCommandChainError(CallbackInfo ci) {
        if (superdbg$isCommandChain(this.input.getValue())) {
            this.commandUsage.clear();
        }
    }

    /**
     * 命令链输入时不让输入框里的文字被染红。
     *
     * <p>原版把输入框文字交给 {@code formatChat} 按「解析到的字面量 / 参数 / 未解析尾段」分段上色，
     * 未解析的尾段一律用 {@code UNPARSED_STYLE}（红色）；命令链整串都不在客户端命令树里，于是整行
     * 都被判为未解析而变红。这里在 {@code CommandSuggestions} 构造收尾处把输入框的格式化器包一层，
     * 命中命令链时直接返回无样式文本（等价于原版没有 {@code currentParse} 时的兜底分支）。</p>
     *
     * <p>必须包格式化器而不是在 {@code formatChat} 里判断：{@link EditBox} 渲染时按光标把文本切成
     * 前后两半，传给格式化器的是<b>可见片段</b>而非完整输入，长命令滚动后该片段里根本不含
     * {@code \间隔N秒}，判定必然落空。包一层就能用 {@code EditBox#getValue()} 拿到完整输入来判定，
     * 返回时仍用调用方给的那段文本，只是不改颜色。</p>
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void superdbg$plainTextForCommandChain(CallbackInfo ci) {
        EditBox box = this.input;
        BiFunction<String, Integer, FormattedCharSequence> original =
                ((EditBoxAccessor) box).superdbg$getFormatter();
        box.setFormatter((text, offset) -> superdbg$isCommandChain(box.getValue())
                ? FormattedCharSequence.forward(text, Style.EMPTY)
                : original.apply(text, offset));
    }

    /** 按服务端同一套规则判定输入串是否为命令链（服务端收到的是去掉首个 {@code /} 的串）。 */
    private boolean superdbg$isCommandChain(String text) {
        if (!CommandChainParser.containsDelayMarker(text)) {
            return false;
        }
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (this.minecraft.player == null || this.minecraft.player.connection == null) {
            return false;
        }
        Set<String> roots = new HashSet<>();
        for (CommandNode<SharedSuggestionProvider> node
                : this.minecraft.player.connection.getCommands().getRoot().getChildren()) {
            roots.add(node.getName());
        }
        List<CommandChainParser.Segment> segments = CommandChainParser.split(text, roots::contains);
        return segments.size() > 1
                || (segments.size() == 1 && segments.get(0).delayTicks() > 0);
    }
}
