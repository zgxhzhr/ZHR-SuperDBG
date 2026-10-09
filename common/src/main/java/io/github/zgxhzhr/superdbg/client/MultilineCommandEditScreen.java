package io.github.zgxhzhr.superdbg.client;

import io.github.zgxhzhr.superdbg.command.CommandTextComposer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 命令方块的多行命令编辑界面。
 *
 * <p>原版命令方块的输入框是一个单行 {@code EditBox}，写 tellraw 这种长命令时只能在一条
 * 水平线上左右滚动，既看不清也不好改。本界面把它放大成一块多行编辑区：写完按「完成」，
 * 内容会被 {@link CommandTextComposer#joinLines} 合并回一条单行命令交回命令方块界面。</p>
 *
 * <p>界面底部实时显示合并后的结果，所见即所得——不必等到提交才发现空格串错了位置。
 * 打开时如果原来是一条没换行的长命令，会先由
 * {@link CommandTextComposer#wrapBySegments} 按段折好行，省去手工断行的功夫。</p>
 *
 * <p>本界面不自己切屏：完成与取消都把最终文本交给构造时传入的回调，由调用方决定写回哪个
 * 输入框。原因是命令方块界面重新显示时会重建它自己的输入框控件，回填必须发生在那之后，
 * 顺序由调用方掌握最稳妥。</p>
 */
public class MultilineCommandEditScreen extends Screen {

    /** 与原版命令输入框一致的长度上限。 */
    private static final int MAX_COMMAND_LENGTH = 32500;

    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int LABEL_COLOR = 0xA0A0A0;
    private static final int TEXT_COLOR = 0xE0E0E0;

    private static final Component HINT_TEXT = Component.literal("每行写一段；完成时按顺序用空格拼成一条命令");
    private static final Component PLACEHOLDER_TEXT = Component.literal("在这里写命令，回车换行");
    private static final Component PREVIEW_LABEL = Component.literal("合并预览：");
    private static final Component EMPTY_PREVIEW = Component.literal("（空）");

    private final String originalCommand;
    private final Consumer<String> onFinish;

    private MultiLineEditBox editor;
    private String preview;

    /**
     * @param initialCommand 打开时显示的现有命令
     * @param onFinish       拿到最终文本的回调：完成时是合并后的单行命令，取消时是原始命令
     */
    public MultilineCommandEditScreen(String initialCommand, Consumer<String> onFinish) {
        super(Component.literal("多行命令编辑"));
        this.originalCommand = initialCommand == null ? "" : initialCommand;
        this.onFinish = onFinish;
        this.preview = CommandTextComposer.joinLines(this.originalCommand);
    }

    @Override
    protected void init() {
        int editorTop = 50;
        int editorHeight = Math.max(36, this.height - 78 - editorTop);
        this.editor = this.addRenderableWidget(new MultiLineEditBox(this.font, 20, editorTop,
                this.width - 40, editorHeight, PLACEHOLDER_TEXT, this.getTitle()));
        this.editor.setCharacterLimit(MAX_COMMAND_LENGTH);
        // 先接监听再灌初值：折行后的文本会立刻算出一份合并预览
        this.editor.setValueListener(value -> this.preview = CommandTextComposer.joinLines(value));
        this.editor.setValue(CommandTextComposer.wrapBySegments(this.originalCommand));
        this.setInitialFocus(this.editor);

        int buttonY = this.height - 30;
        int buttonWidth = Math.min(150, (this.width - 80) / 2);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.finishEditing())
                .bounds(this.width / 2 - buttonWidth - 4, buttonY, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.cancelEditing())
                .bounds(this.width / 2 + 4, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        if (this.editor != null) {
            // 光标闪烁与滚动位置都靠它推进
            this.editor.tick();
        }
    }

    /** 「完成」：把多行内容合并成一条命令交出去。 */
    private void finishEditing() {
        this.onFinish.accept(CommandTextComposer.joinLines(this.editor.getValue()));
    }

    /** 「取消」与 ESC：原样交回打开前的命令，界面上不留改动。 */
    private void cancelEditing() {
        this.onFinish.accept(this.originalCommand);
    }

    @Override
    public void onClose() {
        this.cancelEditing();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 14, TITLE_COLOR);
        guiGraphics.drawString(this.font, HINT_TEXT, 20, 30, LABEL_COLOR);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int previewY = this.height - 66;
        guiGraphics.drawString(this.font, PREVIEW_LABEL, 20, previewY, LABEL_COLOR);
        guiGraphics.drawString(this.font, previewLine(), 20 + this.font.width(PREVIEW_LABEL), previewY, TEXT_COLOR);
    }

    /** 预览行：太长就按可用宽度截断并加省略号，绝不与按钮抢地方。 */
    private String previewLine() {
        if (this.preview.isEmpty()) {
            return EMPTY_PREVIEW.getString();
        }
        int available = this.width - 40 - this.font.width(PREVIEW_LABEL);
        String shown = this.font.plainSubstrByWidth(this.preview, available);
        if (shown.length() < this.preview.length()) {
            shown = shown + "…";
        }
        return shown;
    }
}
