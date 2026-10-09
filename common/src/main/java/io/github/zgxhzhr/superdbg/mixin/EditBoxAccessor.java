package io.github.zgxhzhr.superdbg.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.BiFunction;

/**
 * {@link EditBox} 的字段直访。
 *
 * <ul>
 *   <li>{@code maxLength}：原版命令方块用 {@code setMaxLength(32500)} 放开输入长度，但这个方法的
 *       参数可能被别的模组注入改写（常见做法是把参数钳到几百个字符），于是长命令一贴进去就被截断。
 *       字段访问器不经过那个方法，因此不受这类改写影响，是「还原原版上限」最稳的路子。</li>
 *   <li>{@code formatter}：只读用。输入框的文字着色全靠它，模组要在此基础上包一层时，得先把它取出来。</li>
 * </ul>
 */
@Mixin(EditBox.class)
public interface EditBoxAccessor {

    @Accessor("maxLength")
    void superdbg$setMaxLengthRaw(int maxLength);

    @Accessor("formatter")
    BiFunction<String, Integer, FormattedCharSequence> superdbg$getFormatter();
}
