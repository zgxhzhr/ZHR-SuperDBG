package io.github.zgxhzhr.superdbg.client.gui;

import io.github.zgxhzhr.superdbg.item.EditableAttribute;
import io.github.zgxhzhr.superdbg.item.ItemEditorData;
import io.github.zgxhzhr.superdbg.item.ItemEditorService;
import io.github.zgxhzhr.superdbg.menu.ItemEditorMenu;
import io.github.zgxhzhr.superdbg.network.NetworkHandler;
import io.github.zgxhzhr.superdbg.network.SubmitItemEditorPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 通用物品编辑器屏幕。
 * <p>
 * 三个页签：
 * <ul>
 *   <li>基础：重命名、无法破坏、调试斧（仅金斧可见）</li>
 *   <li>属性：全部已注册属性（含模组属性）的数值输入框 + 生效槽位循环按钮，分页滚轮浏览</li>
 *   <li>附魔：全部注册附魔的等级编辑（任意物品可附加任意附魔，留空=移除）</li>
 * </ul>
 * 属性使用精确数值输入而非滑块：滑块拖动会把不在步进网格上的默认值
 * （如钻石剑攻速 -2.4）吸附成别的值，且难以输入精确数值。
 * 点击"确认"时全量原子提交。
 */
public class ItemEditorScreen extends AbstractContainerScreen<ItemEditorMenu> {

    private static final int ENCH_PAGE_SIZE = 9;
    private static final int ATTR_PAGE_SIZE = 8;

    /** 槽位选项与显示顺序（1.20.1 只有六个实体装备槽） */
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** 页签 */
    private enum EditorTab {
        BASIC("基础"), ATTRIBUTES("属性"), ENCHANT("附魔");
        final String title;
        EditorTab(String title) {
            this.title = title;
        }
    }

    private final List<Enchantment> allEnchantments = new ArrayList<>();
    private final List<EditableAttribute> allAttributes = new ArrayList<>();

    private EditBox nameBox;
    private Checkbox unbreakableBox;
    private Checkbox debugBox;
    private final List<AttrRow> attrRows = new ArrayList<>();
    private final List<EditBox> enchantBoxes = new ArrayList<>();
    private CycleButton<EditorTab> tabButton;

    private final List<AbstractWidget> baseWidgets = new ArrayList<>();
    private final List<AbstractWidget> attrWidgets = new ArrayList<>();
    private final List<AbstractWidget> enchantWidgets = new ArrayList<>();

    private EditorTab tab = EditorTab.BASIC;
    private int attrPage = 0;
    private int enchPage = 0;
    /** 是否金斧（决定调试框显隐，页签切换后也必须保持） */
    private boolean goldenAxe = false;

    public ItemEditorScreen(ItemEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 240;
        this.imageHeight = 246;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        baseWidgets.clear();
        attrWidgets.clear();
        enchantWidgets.clear();
        attrRows.clear();
        enchantBoxes.clear();
        allEnchantments.clear();
        allAttributes.clear();
        BuiltInRegistries.ENCHANTMENT.iterator().forEachRemaining(allEnchantments::add);
        // 动态枚举全部属性（含模组属性）
        allAttributes.addAll(EditableAttribute.all());

        ItemEditorData data = menu.getData();
        goldenAxe = minecraft.player.getOffhandItem().is(Items.GOLDEN_AXE);

        // ---- 页签切换 ----
        tabButton = CycleButton.<EditorTab>builder(t -> Component.literal(t.title))
                .withValues(EditorTab.values())
                .withInitialValue(EditorTab.BASIC)
                .create(leftPos + 158, topPos + 3, 70, 16,
                        Component.literal("页签"), (button, t) -> setTab(t));
        addRenderableWidget(tabButton);

        // ---- 基础页：名称 ----
        nameBox = new EditBox(font, leftPos + 12, topPos + 22, 216, 18,
                Component.literal("名称"));
        nameBox.setMaxLength(ItemEditorService.MAX_NAME_LENGTH);
        nameBox.setValue(data.customName);
        addRenderableWidget(nameBox);
        baseWidgets.add(nameBox);

        // ---- 基础页：标志位 ----
        unbreakableBox = new Checkbox(leftPos + 12, topPos + 44, 100, 18,
                Component.literal("无法破坏"), data.unbreakable);
        addRenderableWidget(unbreakableBox);
        baseWidgets.add(unbreakableBox);

        debugBox = new Checkbox(leftPos + 124, topPos + 44, 104, 18,
                Component.literal("调试状态"), data.debug);
        // 显隐统一由 setTab 控制（非金斧永远隐藏）
        addRenderableWidget(debugBox);
        baseWidgets.add(debugBox);

        // ---- 属性页：数值框 + 槽位循环按钮（全部注册属性，分页显示）----
        for (int i = 0; i < allAttributes.size(); i++) {
            EditableAttribute ea = allAttributes.get(i);
            ResourceLocation rl = BuiltInRegistries.ATTRIBUTE.getKey(ea.attribute());
            int rowY = topPos + 30 + (i % ATTR_PAGE_SIZE) * 20;
            double current = data.attributes.getOrDefault(rl, 0.0D);
            EquipmentSlot slot = data.attributeSlots.getOrDefault(rl, EquipmentSlot.MAINHAND);

            AttrBox box = new AttrBox(font, leftPos + 102, rowY, 52, 16, ea, current);
            // 悬停显示完整属性 id，便于辨识模组属性
            box.setTooltip(Tooltip.create(Component.literal(rl.toString())));
            CycleButton<EquipmentSlot> slotBtn = CycleButton
                    .<EquipmentSlot>builder(g -> Component.literal(slotName(g)))
                    .withValues(SLOTS)
                    .withInitialValue(slot)
                    .create(leftPos + 156, rowY, 76, 16, Component.literal("生效槽位"));
            addRenderableWidget(box);
            addRenderableWidget(slotBtn);
            attrRows.add(new AttrRow(ea, rl, box, slotBtn));
            attrWidgets.add(box);
            attrWidgets.add(slotBtn);
        }

        // ---- 附魔页：每个附魔一个等级框 ----
        for (int i = 0; i < allEnchantments.size(); i++) {
            Enchantment ench = allEnchantments.get(i);
            ResourceLocation rl = BuiltInRegistries.ENCHANTMENT.getKey(ench);
            EditBox box = new EditBox(font, leftPos + 172, topPos + 31 + (i % ENCH_PAGE_SIZE) * 20,
                    56, 16, Component.literal("等级"));
            // Mixin 已放开 EnchantmentHelper 序列化两端瓶颈，支持 0-Integer.MAX_VALUE
            box.setMaxLength(10);
            box.setFilter(s -> s.isEmpty() || s.matches("\\d{1,10}"));
            Integer level = data.enchantments.get(rl);
            if (level != null && level > 0) {
                box.setValue(String.valueOf(level));
            }
            addRenderableWidget(box);
            enchantBoxes.add(box);
            enchantWidgets.add(box);
        }

        // ---- 底部按钮 ----
        addRenderableWidget(Button.builder(Component.literal("确认"), b -> submit())
                .bounds(leftPos + 56, topPos + 222, 60, 18)
                .build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose())
                .bounds(leftPos + 124, topPos + 222, 60, 18)
                .build());

        setTab(EditorTab.BASIC);
    }

    private void setTab(EditorTab t) {
        this.tab = t;
        for (var w : baseWidgets) {
            w.visible = t == EditorTab.BASIC;
        }
        for (var w : attrWidgets) {
            w.visible = t == EditorTab.ATTRIBUTES;
        }
        for (var w : enchantWidgets) {
            w.visible = t == EditorTab.ENCHANT;
        }
        // 修复：从其他页切回基础页时，必须显式按金斧判断设回 false，
        // 否则非金斧的调试框会因 baseWidgets 批量置可见而残留显示
        debugBox.visible = t == EditorTab.BASIC && goldenAxe;
        updateAttrPageVisibility();
        updateEnchPageVisibility();
        setFocused(null);
    }

    private int maxAttrPage() {
        return Math.max(0, (attrRows.size() - 1) / ATTR_PAGE_SIZE);
    }

    private int maxEnchPage() {
        return Math.max(0, (allEnchantments.size() - 1) / ENCH_PAGE_SIZE);
    }

    private void updateAttrPageVisibility() {
        for (int i = 0; i < attrRows.size(); i++) {
            boolean visible = tab == EditorTab.ATTRIBUTES && i / ATTR_PAGE_SIZE == attrPage;
            attrRows.get(i).box.visible = visible;
            attrRows.get(i).slotButton.visible = visible;
        }
    }

    private void updateEnchPageVisibility() {
        for (int i = 0; i < enchantBoxes.size(); i++) {
            enchantBoxes.get(i).visible = tab == EditorTab.ENCHANT
                    && i / ENCH_PAGE_SIZE == enchPage;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tab == EditorTab.ATTRIBUTES) {
            int next = attrPage - (delta > 0 ? 1 : -1);
            next = Math.max(0, Math.min(maxAttrPage(), next));
            if (next != attrPage) {
                attrPage = next;
                updateAttrPageVisibility();
                setFocused(null);
            }
            return true;
        }
        if (tab == EditorTab.ENCHANT) {
            int next = enchPage - (delta > 0 ? 1 : -1);
            next = Math.max(0, Math.min(maxEnchPage(), next));
            if (next != enchPage) {
                enchPage = next;
                updateEnchPageVisibility();
                setFocused(null);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 收集界面数据并全量提交。
     */
    private void submit() {
        ItemEditorData data = menu.getData();
        data.customName = nameBox.getValue();
        data.unbreakable = unbreakableBox.selected();
        data.debug = debugBox.selected();

        data.enchantments.clear();
        for (int i = 0; i < allEnchantments.size(); i++) {
            String raw = enchantBoxes.get(i).getValue().trim();
            if (raw.isEmpty()) continue;
            try {
                int level = Integer.parseInt(raw);
                level = Math.max(1, Math.min(ItemEditorService.MAX_ENCHANT_LEVEL, level));
                data.enchantments.put(
                        BuiltInRegistries.ENCHANTMENT.getKey(allEnchantments.get(i)), level);
            } catch (NumberFormatException ignored) {
                // 非法输入直接忽略该项
            }
        }

        data.attributes.clear();
        data.attributeSlots.clear();
        for (AttrRow row : attrRows) {
            data.attributes.put(row.id, row.box.value());
            data.attributeSlots.put(row.id, row.slotButton.getValue());
        }

        NetworkHandler.CHANNEL.sendToServer(new SubmitItemEditorPacket(
                data.customName,
                data.unbreakable,
                data.debug,
                new LinkedHashMap<>(data.enchantments),
                new LinkedHashMap<>(data.attributes),
                new LinkedHashMap<>(data.attributeSlots)
        ));
        onClose();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        // 与药水编辑器一致的灰色斜面面板
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF555555);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFFFFFFFF);
        graphics.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, 0xFFC6C6C6);
        // 底部分隔线
        graphics.fill(x + 4, y + 216, x + imageWidth - 4, y + 217, 0xFF555555);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0x404040, false);

        if (tab == EditorTab.BASIC) {
            graphics.drawString(font, Component.literal("名称"), 12, 13, 0x404040, false);
            graphics.drawString(font, Component.literal("属性数值与生效槽位：见「属性」页"), 12, 70, 0x606060, false);
            graphics.drawString(font, Component.literal("附魔等级：见「附魔」页"), 12, 84, 0x606060, false);
            return;
        }

        if (tab == EditorTab.ATTRIBUTES) {
            graphics.drawString(font, Component.literal("属性"), 12, 20, 0x404040, false);
            graphics.drawString(font, Component.literal("数值"), 102, 20, 0x404040, false);
            graphics.drawString(font, Component.literal("生效槽位"), 156, 20, 0x404040, false);
            int start = attrPage * ATTR_PAGE_SIZE;
            int end = Math.min(attrRows.size(), start + ATTR_PAGE_SIZE);
            for (int i = start; i < end; i++) {
                int rowY = 30 + (i % ATTR_PAGE_SIZE) * 20;
                String text = Component.translatable(
                        attrRows.get(i).def.attribute().getDescriptionId()).getString();
                if (font.width(text) > 86) {
                    text = font.plainSubstrByWidth(text, 80) + "…";
                }
                graphics.drawString(font, text, 12, rowY + 4, 0x404040, false);
            }
            graphics.drawString(font,
                    Component.literal((attrPage + 1) + "/" + (maxAttrPage() + 1) + "（滚轮翻页）"),
                    12, 213, 0x404040, false);
            return;
        }

        // 附魔页
        graphics.drawString(font,
                Component.literal("附魔等级（留空=移除，1-" + ItemEditorService.MAX_ENCHANT_LEVEL + "）"),
                12, 14, 0x404040, false);

        int start = enchPage * ENCH_PAGE_SIZE;
        int end = Math.min(allEnchantments.size(), start + ENCH_PAGE_SIZE);
        for (int i = start; i < end; i++) {
            int rowY = 31 + (i % ENCH_PAGE_SIZE) * 20;
            Enchantment ench = allEnchantments.get(i);
            String text = Component.translatable(ench.getDescriptionId()).getString();
            // 超长名称裁剪
            if (font.width(text) > 150) {
                text = font.plainSubstrByWidth(text, 144) + "…";
            }
            graphics.drawString(font, text, 12, rowY + 4, 0x404040, false);
        }

        graphics.drawString(font,
                Component.literal((enchPage + 1) + "/" + (maxEnchPage() + 1) + "（滚轮翻页）"),
                12, 213, 0x404040, false);
    }

    /** 槽位中文短名（循环按钮显示用）。 */
    private static String slotName(EquipmentSlot g) {
        return switch (g) {
            case MAINHAND -> "主手";
            case OFFHAND -> "副手";
            case HEAD -> "头盔";
            case CHEST -> "胸甲";
            case LEGS -> "护腿";
            case FEET -> "靴子";
        };
    }

    /** 一行属性编辑控件：数值框 + 槽位循环按钮。 */
    private record AttrRow(EditableAttribute def, ResourceLocation id,
                           AttrBox box, CycleButton<EquipmentSlot> slotButton) {
    }

    /**
     * 属性输入框：直接输入精确数值，不做步进吸附（避免 -2.4 这类默认值被改动）。
     * 留空或非法输入按 0 处理，提交时服务端会再次 clamp。
     */
    private static class AttrBox extends EditBox {

        private final EditableAttribute def;

        AttrBox(Font font, int x, int y, int w, int h, EditableAttribute def, double current) {
            super(font, x, y, w, h, Component.translatable(def.attribute().getDescriptionId()));
            this.def = def;
            setMaxLength(18);
            setFilter(s -> s.isEmpty() || s.equals("-")
                    || s.matches("-?\\d{1,10}(\\.\\d{0,6})?"));
            setValue(formatAttr(current));
        }

        double value() {
            String raw = getValue().trim();
            if (raw.isEmpty() || raw.equals("-")) {
                return 0.0D;
            }
            try {
                return def.normalize(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                return 0.0D;
            }
        }
    }

    /**
     * 格式化属性显示：最多 6 位小数并去掉尾随零（6.0 → "6"，-2.4 → "-2.4"）。
     * 非法浮点（NaN/Infinity）按 0 显示，避免 BigDecimal.valueOf 抛异常打断 init。
     */
    private static String formatAttr(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        BigDecimal bd = BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return bd.toPlainString();
    }
}
