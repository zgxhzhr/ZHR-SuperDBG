package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.client.MultilineCommandEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给命令方块界面加一个「多行编辑」入口。
 *
 * <p>原版命令方块的输入框是单行 {@code EditBox}（宽 300、高 20），还绑着
 * {@code CommandSuggestions} 命令补全与用法提示。直接把它换成多行控件会一并丢掉补全，
 * 因此这里只追加一个按钮，把多行编辑放进一个独立的
 * {@link MultilineCommandEditScreen}：原输入框与补全照旧，长命令另有一块大地方写。</p>
 *
 * <p>命令方块与命令方块矿车的界面共用 {@link AbstractCommandBlockEditScreen}，注入这一层
 * 两个界面都能用上。</p>
 *
 * <p>回填的顺序很关键：重新显示命令方块界面会走一遍 {@code init}，把输入框控件整个换成新的，
 * 所以必须先把界面切回去、再对新控件 {@code setValue}；反过来做会被 {@code init} 覆盖掉。
 * 顺带一提，{@code setValue} 会触发界面早就绑好的 responder，命令补全的状态因此自动刷新。</p>
 */
@Mixin(AbstractCommandBlockEditScreen.class)
public abstract class AbstractCommandBlockEditScreenMixin extends Screen {

    /** 按钮尺寸与纵向位置：贴在命令输入框正下方、左对齐，不与下方的上一输出区重叠。 */
    private static final int BUTTON_WIDTH = 110;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BUTTON_Y = 74;

    /** 原版命令输入框左端相对界面中心的偏移：从中心往左 150 格，按钮与它对齐。 */
    private static final int COMMAND_EDIT_LEFT_FROM_CENTER = -150;

    /**
     * 原版给命令输入框设的长度上限。
     *
     * <p>原版 {@code init} 里本来就是 {@code setMaxLength(32500)}，但这个调用可能被别的模组注入改写，
     * 把参数钳到几百个字符，于是长命令一贴进去就被截断。这里在 {@code init} 收尾时直接写明字段值，
     * 把上限拉回原版水平；不针对任何具体模组，凡是改写该调用的都一并绕开。</p>
     */
    private static final int COMMAND_MAX_LENGTH = 32500;

    /**
     * 只为满足 Java 的语法要求而写的占位构造器。
     *
     * <p>本类声明了与目标类相同的父类 {@link Screen}，这样 {@code addRenderableWidget}、{@code width}
     * 这些从父类继承来的受保护成员才能直接编译通过（Mixin 只把本类自己声明的方法合并进目标类，继承来的
     * 方法不会被误当作注入内容）。而 Java 要求子类构造器必须调用父类构造器，故这里补一个；Mixin 在应用
     * 时会忽略它，不会加到目标类上。</p>
     */
    protected AbstractCommandBlockEditScreenMixin() {
        super(Component.empty());
    }

    @Shadow
    protected EditBox commandEdit;

    /** 上一输出框，与原版同为 {@code setMaxLength(32500)}，同样会被改写，一起还原。 */
    @Shadow
    protected EditBox previousEdit;

    /** 在原界面控件都建好之后追加「多行编辑…」按钮，并把输入框长度上限拉回原版水平。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void superdbg$addMultilineButton(CallbackInfo ci) {
        // 原版在 init 里用 setMaxLength(32500) 放开长度，这一步可能被别的模组改写参数，这里按字段直写兜底
        ((EditBoxAccessor) this.commandEdit).superdbg$setMaxLengthRaw(COMMAND_MAX_LENGTH);
        ((EditBoxAccessor) this.previousEdit).superdbg$setMaxLengthRaw(COMMAND_MAX_LENGTH);

        int left = this.width / 2 + COMMAND_EDIT_LEFT_FROM_CENTER;
        this.addRenderableWidget(Button.builder(Component.literal("多行编辑…"),
                        clicked -> this.superdbg$openMultilineEditor())
                .bounds(left, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    /** 带着当前命令打开多行编辑界面。 */
    @Unique
    private void superdbg$openMultilineEditor() {
        Minecraft.getInstance().setScreen(
                new MultilineCommandEditScreen(this.commandEdit.getValue(), this::superdbg$applyMultilineResult));
    }

    /** 收到多行编辑界面的最终文本：切回命令方块界面，再把内容填进新建出来的输入框。 */
    @Unique
    private void superdbg$applyMultilineResult(String merged) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen((Screen) (Object) this);

        // 命令方块界面的 init 末尾会把「完成」「脉冲」「红石控制」这批控件先置为不可用，等服务端同步
        // （收到方块实体数据包后的 updateGui）才恢复。从多行界面返回时服务端不会再发一次数据，界面就
        // 一直停在不可用状态——「完成」点不动、命令也存不进去。这里按方块当前的本地状态刷一遍控件。
        // 命令方块矿车界面没有这套禁用逻辑、且在 init 里自己填好了命令，因此不需要处理。
        if ((Object) this instanceof CommandBlockEditScreen commandBlockScreen) {
            commandBlockScreen.updateGui();
        }

        // 必须放在刷新之后：updateGui 会把命令改回方块里的旧值，这里再用合并结果覆盖它
        this.commandEdit.setValue(merged);
    }
}
