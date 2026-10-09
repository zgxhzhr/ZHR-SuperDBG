package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.menu.PotionEditorMenu;
import io.github.zgxhzhr.superdbg.network.AddPotionEffectPacket;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.UpdatePotionEffectPacket;
import io.github.zgxhzhr.superdbg.potion.PotionEffectData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 药水编辑器屏幕。
 * <p>
 * 风格参照原版背包的颜色（灰色面板、深色文字），但不使用格子纹理，
 * 以自定义绘制的面板呈现。每行展示一个药水效果，可编辑等级与时长。
 * 底部有确认按钮用于关闭面板。
 */
public class PotionEditorScreen extends AbstractContainerScreen<PotionEditorMenu> {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_START_Y = 22;
    /** 新增效果时的默认持续时长（tick），10 秒；等级默认为 0（1 级）。 */
    private static final int DEFAULT_NEW_DURATION = 200;

    private final List<EditBox> amplifierBoxes = new ArrayList<>();
    private final List<EditBox> durationBoxes = new ArrayList<>();
    private final List<Button> permanentButtons = new ArrayList<>();

    private EditBox lastFocusedBox = null;

    public PotionEditorScreen(PotionEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = Math.max(110, LIST_START_Y + 14 + menu.getEffects().size() * ROW_HEIGHT + 32);
        this.titleLabelY = 6;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        // 效果数量会因「添加效果」而变化，每次 init（含从效果选择器返回）都按当前数量重算面板高度
        this.imageHeight = Math.max(110, LIST_START_Y + 14 + menu.getEffects().size() * ROW_HEIGHT + 32);
        this.inventoryLabelY = this.imageHeight - 94;
        super.init();
        amplifierBoxes.clear();
        durationBoxes.clear();
        permanentButtons.clear();
        lastFocusedBox = null;

        List<PotionEffectData> effects = menu.getEffects();
        for (int i = 0; i < effects.size(); i++) {
            PotionEffectData data = effects.get(i);
            int rowY = topPos + LIST_START_Y + i * ROW_HEIGHT;
            final int index = i;

            // 等级输入框（显示 1-based，内部 0-based）
            EditBox ampBox = new EditBox(font, leftPos + 90, rowY + 2, 32, 16,
                    Component.translatable("superdbg.gui.amplifier"));
            // MobEffectInstanceMixin 已放开 byte 序列化瓶颈，支持 0-Integer.MAX_VALUE
            ampBox.setMaxLength(10);
            ampBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,10}"));
            ampBox.setValue(String.valueOf(data.amplifier()));
            ampBox.setResponder(value -> onAmplifierChanged(index, value));
            addRenderableWidget(ampBox);
            amplifierBoxes.add(ampBox);

            // 时长输入框（tick）
            EditBox durBox = new EditBox(font, leftPos + 120, rowY + 2, 36, 16,
                    Component.translatable("superdbg.gui.duration"));
            durBox.setMaxLength(9);
            durBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,9}"));
            if (data.isInfinite()) {
                durBox.setValue("\u221e");
                durBox.setEditable(false);
            } else {
                durBox.setValue(String.valueOf(data.duration()));
            }
            durBox.setResponder(value -> onDurationChanged(index, value));
            addRenderableWidget(durBox);
            durationBoxes.add(durBox);

            // 永久按钮
            boolean isInf = data.isInfinite();
            Button permBtn = Button.builder(
                            Component.literal(isInf ? "\u221e" : "\u6c38"),
                            b -> togglePermanent(index))
                    .bounds(leftPos + 158, rowY + 2, 14, 16)
                    .build();
            addRenderableWidget(permBtn);
            permanentButtons.add(permBtn);
        }

        // 添加效果按钮
        addRenderableWidget(Button.builder(
                        Component.translatable("superdbg.gui.add_effect"),
                        b -> openEffectPicker())
                .bounds(leftPos + 8, topPos + imageHeight - 26, 62, 20)
                .build());

        // 确认按钮
        addRenderableWidget(Button.builder(
                        Component.translatable("superdbg.gui.confirm"),
                        b -> onClose())
                .bounds(leftPos + imageWidth - 70, topPos + imageHeight - 26, 62, 20)
                .build());
    }

    /**
     * 打开药水效果选择器；选中后本地追加一条并请求服务端写入。
     * <p>
     * 面板高度随效果数量变化，回到本界面时 {@code setScreen(this)} 会触发
     * {@link #init()} 重新布局。
     */
    private void openEffectPicker() {
        MobEffectPickerScreen.open(effect -> {
            menu.addEffectLocal(effect, PotionEffectData.MIN_AMPLIFIER, DEFAULT_NEW_DURATION);
            NetworkHandler.CHANNEL.sendToServer(
                    new AddPotionEffectPacket(effect, PotionEffectData.MIN_AMPLIFIER, DEFAULT_NEW_DURATION));
            Minecraft.getInstance().setScreen(this);
        });
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 自定义灰色面板背景（类似原版背包颜色，但不用格子纹理）
        int x = leftPos;
        int y = topPos;
        int w = imageWidth;
        int h = imageHeight;
        // 外层深色边框
        graphics.fill(x, y, x + w, y + h, 0xFF555555);
        // 内层白色边框（斜面效果）
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
        // 灰色背景
        graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFFC6C6C6);
        // 效果列表区域分隔线
        int listBottom = LIST_START_Y + menu.getEffects().size() * ROW_HEIGHT + 4;
        graphics.fill(x + 4, y + listBottom, x + w - 4, y + listBottom + 1, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题
        graphics.drawString(font, title, 8, 6, 0x404040, false);

        List<PotionEffectData> effects = menu.getEffects();
        if (effects.isEmpty()) {
            graphics.drawString(font, Component.translatable("superdbg.gui.no_effect"),
                    8, LIST_START_Y + 5, 0x707070, false);
            return;
        }
        for (int i = 0; i < effects.size(); i++) {
            PotionEffectData data = effects.get(i);
            int rowY = LIST_START_Y + i * ROW_HEIGHT;

            // 效果图标
            MobEffect effect = data.effect();
            TextureAtlasSprite icon = minecraft.getMobEffectTextures().get(effect);
            graphics.blit(8, rowY + 2, 0, 18, 18, icon);

            // 效果名称
            Component name = Component.translatable(effect.getDescriptionId());
            graphics.drawString(font, name, 30, rowY + 5, 0x404040, false);

            // 等级/时长标签
            graphics.drawString(font, Component.translatable("superdbg.gui.level"), 80, rowY + 5, 0x404040, false);
            graphics.drawString(font, Component.translatable("superdbg.gui.time"), 116, rowY + 5, 0x404040, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 检测输入框失焦并提交
        EditBox focused = findFocusedBox();
        if (focused != null && focused != lastFocusedBox) {
            submitBox(lastFocusedBox);
        }
        lastFocusedBox = focused;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        // 关闭面板时提交最后一个聚焦框的数据
        submitBox(lastFocusedBox);
        super.removed();
    }

    private EditBox findFocusedBox() {
        for (EditBox box : amplifierBoxes) {
            if (box.isFocused()) return box;
        }
        for (EditBox box : durationBoxes) {
            if (box.isFocused()) return box;
        }
        return null;
    }

    private void submitBox(EditBox box) {
        if (box == null) return;
        int index = amplifierBoxes.indexOf(box);
        if (index >= 0) {
            sendAmplifierUpdate(index, box.getValue());
            return;
        }
        index = durationBoxes.indexOf(box);
        if (index >= 0) {
            sendDurationUpdate(index, box.getValue());
        }
    }

    private void onAmplifierChanged(int index, String value) {
        if (!value.isEmpty()) {
            try {
                int v = Integer.parseInt(value);
                if (v > PotionEffectData.MAX_AMPLIFIER) {
                    amplifierBoxes.get(index).setValue(String.valueOf(PotionEffectData.MAX_AMPLIFIER));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }


    private void onDurationChanged(int index, String value) {
        if (menu.getEffects().get(index).isInfinite()) return;
    }

    private void sendAmplifierUpdate(int index, String value) {
        if (value.isEmpty()) return;
        try {
            int amp = Integer.parseInt(value);
            if (amp < 0) amp = 0;
            if (amp > PotionEffectData.MAX_AMPLIFIER) amp = PotionEffectData.MAX_AMPLIFIER;
            PotionEffectData old = menu.getEffects().get(index);
            menu.updateEffectLocal(index, amp, old.duration());
            NetworkHandler.CHANNEL.sendToServer(new UpdatePotionEffectPacket(index, amp, old.duration()));
        } catch (NumberFormatException e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.debug("无效等级输入：{}", value);
        }
    }

    private void sendDurationUpdate(int index, String value) {
        if (value.isEmpty() || "\u221e".equals(value)) return;
        try {
            int dur = Integer.parseInt(value);
            if (dur < 0) dur = 0;
            PotionEffectData old = menu.getEffects().get(index);
            menu.updateEffectLocal(index, old.amplifier(), dur);
            NetworkHandler.CHANNEL.sendToServer(new UpdatePotionEffectPacket(index, old.amplifier(), dur));
        } catch (NumberFormatException e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.debug("无效时长输入：{}", value);
        }
    }

    private void togglePermanent(int index) {
        PotionEffectData old = menu.getEffects().get(index);
        int newDuration = old.isInfinite() ? 200 : PotionEffectData.INFINITE_DURATION;
        EditBox durBox = durationBoxes.get(index);
        Button permBtn = permanentButtons.get(index);

        menu.updateEffectLocal(index, old.amplifier(), newDuration);
        if (newDuration == PotionEffectData.INFINITE_DURATION) {
            durBox.setValue("\u221e");
            durBox.setEditable(false);
            permBtn.setMessage(Component.literal("\u221e"));
        } else {
            durBox.setValue(String.valueOf(newDuration));
            durBox.setEditable(true);
            permBtn.setMessage(Component.literal("\u6c38"));
        }
        NetworkHandler.CHANNEL.sendToServer(
                new UpdatePotionEffectPacket(index, old.amplifier(), newDuration));
    }
}
