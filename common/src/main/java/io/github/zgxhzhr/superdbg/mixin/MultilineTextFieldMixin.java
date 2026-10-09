package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.command.CommandTextComposer;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让多行输入框按自己保管的上限截断文本，不再走
 * {@code StringUtil.truncateStringIfNecessary}。
 *
 * <p>上限本来就由 {@link MultilineTextField} 自己持有，但原版的截断动作是调用上面那个工具方法
 * 完成的，而这个调用点可能被别的模组包装，把传入的长度换成更小的值（例如几百字），于是多行
 * 编辑里贴长命令同样会被截断。这里改为在本方法内部按 {@code characterLimit} 直接算，不再经过
 * 那个可能被改写的调用，与上限值本身由谁设定无关，因此对任何用同样手段的模组都有效。</p>
 */
@Mixin(MultilineTextField.class)
public abstract class MultilineTextFieldMixin {

    /** 多行输入框自己保管的字符上限，默认无上限（{@link Integer#MAX_VALUE}）。 */
    @Shadow
    private int characterLimit;

    @Shadow
    public abstract String value();

    /** 整段替换（{@code setValue}）时按上限截断。 */
    @Inject(method = "truncateFullText", at = @At("HEAD"), cancellable = true)
    private void superdbg$truncateFullText(String text, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(CommandTextComposer.truncate(text, this.characterLimit));
    }

    /** 插入（输入、粘贴）时按「上限减去已有长度」的剩余额度截断。 */
    @Inject(method = "truncateInsertionText", at = @At("HEAD"), cancellable = true)
    private void superdbg$truncateInsertionText(String text, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(CommandTextComposer.truncate(text, this.characterLimit - this.value().length()));
    }
}
