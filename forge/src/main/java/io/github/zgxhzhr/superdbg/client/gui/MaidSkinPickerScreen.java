package io.github.zgxhzhr.superdbg.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 车万女仆（Touhou Little Maid，作者 TartaricAcid，MIT 协议开源）女仆模型的可视化选择界面。
 *
 * <p>仿车万女仆本体 {@code MaidModelGui} 的样式：11 列 × 5 行网格，每格渲染一个
 * 对应模型 id 的女仆旋转预览，点击即选中。车万女仆本体的选择界面必须绑定一个
 * 真实女仆实体、且选中结果经网络包写回服务端实体，无法在客户端拿到选中值，
 * 因此这里不直接打开其界面，而是在调试器侧自绘（模型列表经反射读取自
 * {@code CustomPackLoader.MAID_MODELS}，预览渲染复用车万女仆的实体渲染管线）。</p>
 *
 * <p>渲染用反射创建/缓存一个 {@code EntityMaid} 实例（生产 jar 中其类名、构造器
 * 与方法名均保留，未混淆），每次渲染前反射调用 {@code setModelId(String)}；
 * 选中结果通过回调交给调用方（客户端内存，随调试器快照提交走原链路）。</p>
 */
public class MaidSkinPickerScreen extends Screen {

    /** 网格列数（与车万女仆 MaidModelGui 一致：11 列 × 5 行，一页最多 55 个） */
    private static final int GRID_COLS = 11;
    private static final int GRID_ROWS = 5;
    private static final int PER_PAGE = GRID_COLS * GRID_ROWS;
    /** 格子尺寸（按钮在下，女仆预览画在按钮上方） */
    private static final int CELL_WIDTH = 46;
    private static final int CELL_HEIGHT = 58;
    /** 女仆预览渲染缩放 */
    private static final int PREVIEW_SCALE = 12;

    /** 预览女仆缓存（反射创建的车万女仆 EntityMaid 实例，跨帧复用，当前关卡变化时重建） */
    @Nullable
    private static Object cachedMaid;
    @Nullable
    private static Level cachedMaidLevel;
    /** 反射句柄：EntityMaid#setModelId(String)，生产 jar 中方法名保留 */
    @Nullable
    private static Method maidSetModelId;
    /** 反射句柄：EntityCacheUtil#clearMaidDataResidue(EntityMaid, boolean)，生产 jar 中方法名保留 */
    @Nullable
    private static Method maidClearResidueMethod;

    /** 全部可选模型 id（排序保证顺序稳定；车万女仆的模型集合本身无序） */
    private final List<String> allModelIds;
    /** 进入界面前的当前选中值（用于高亮） */
    private final String currentId;
    /** 选中回调（模型 id 字符串） */
    private final Consumer<String> onPick;
    /** 当前页的格子按钮（与 allModelIds 的切片一一对应） */
    private final List<Button> cellButtons = new ArrayList<>();
    private int page = 0;

    public MaidSkinPickerScreen(List<String> modelIds, String currentId, Consumer<String> onPick) {
        super(Component.literal("选择魂符展示模型"));
        List<String> ids = new ArrayList<>(modelIds);
        Collections.sort(ids);
        this.allModelIds = ids;
        this.currentId = currentId == null ? "" : currentId;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        cellButtons.clear();
        int maxPage = Math.max(0, (allModelIds.size() - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, maxPage));
        int startX = this.width / 2 - (GRID_COLS * CELL_WIDTH) / 2;
        int startY = this.height / 2 - (GRID_ROWS * CELL_HEIGHT) / 2 - 10;
        int from = page * PER_PAGE;
        int to = Math.min(from + PER_PAGE, allModelIds.size());
        for (int i = from; i < to; i++) {
            String id = allModelIds.get(i);
            int row = (i - from) / GRID_COLS;
            int col = (i - from) % GRID_COLS;
            int x = startX + col * CELL_WIDTH;
            int y = startY + row * CELL_HEIGHT;
            Button button = Button.builder(
                            Component.literal(font.plainSubstrByWidth(id, CELL_WIDTH - 8)), b -> {
                                io.github.zgxhzhr.superdbg.Constants.LOG.info(
                                        "[SuperDbg] MaidSkinPickerScreen 点击选择模型: modelId={}", id);
                                onPick.accept(id);
                                this.onClose();
                            })
                    .bounds(x, y, CELL_WIDTH - 2, 16).build();
            button.setTooltip(Tooltip.create(Component.literal(id)));
            this.addRenderableWidget(button);
            cellButtons.add(button);
        }
        // 翻页按钮（滚轮亦可翻页）
        int prevY = startY + GRID_ROWS * CELL_HEIGHT + 12;
        this.addRenderableWidget(Button.builder(Component.literal("上一页"), b -> {
            page = Math.max(0, page - 1);
            this.init();
        }).bounds(this.width / 2 - 100, prevY, 80, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("下一页"), b -> {
            page = Math.min(maxPage, page + 1);
            this.init();
        }).bounds(this.width / 2 + 20, prevY, 80, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font,
                Component.literal("选择魂符展示的女仆模型（车万女仆（Touhou Little Maid，作者 TartaricAcid，MIT 协议开源））"),
                this.width / 2, 24, 0xFFFFFF);
        int pageCount = Math.max(1, (allModelIds.size() + PER_PAGE - 1) / PER_PAGE);
        graphics.drawCenteredString(font,
                Component.literal("第 " + (page + 1) + " / " + pageCount + " 页（滚轮或按钮翻页，点击选中）"),
                this.width / 2, 40, 0xAAAAAA);
        if (allModelIds.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("没有可用的女仆模型"), this.width / 2, this.height / 2, 0xFF5555);
            return;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        // 每个格子渲染对应模型的女仆预览，并高亮当前选中
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Object maid = getMaidForRender(level);
        if (maid == null) {
            return;
        }
        int from = page * PER_PAGE;
        for (int i = 0; i < cellButtons.size(); i++) {
            String id = allModelIds.get(from + i);
            Button button = cellButtons.get(i);
            int cx = button.getX() + (CELL_WIDTH - 2) / 2;
            int cy = button.getY() - 10;
            // 格子预览复用同一缓存女仆实体：切换模型前先清除模型/动画渲染残留
            // （仿车万女仆 MaidModelGui#drawEntity），否则所有格子可能都显示上一格/首格模型，
            // 导致预览与点击的 id 错位
            clearMaidDataResidue(maid);
            setMaidModelId(maid, id);
            // 与车万女仆魂符预览一致的旋转姿势：绕 Z 轴翻转 + 固定侧偏角
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            pose.mul(new Quaternionf().rotateY(25.0F));
            InventoryScreen.renderEntityInInventory(graphics, cx, cy, PREVIEW_SCALE, pose, null, (LivingEntity) maid);
            // 当前选中模型的按钮描边高亮
            if (id.equals(currentId)) {
                graphics.fill(button.getX() - 1, button.getY() - 1,
                        button.getX() + CELL_WIDTH - 1, button.getY(), 0xFF55FF55);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxPage = Math.max(0, (allModelIds.size() - 1) / PER_PAGE);
        if (delta > 0) {
            page = Math.max(0, page - 1);
        } else {
            page = Math.min(maxPage, page + 1);
        }
        this.init();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 取（必要时创建）预览用的车万女仆 EntityMaid 实例；当前关卡变化时重建。
     * 生产 jar 中类名、构造器与方法名均保留（车万女仆自有类不参与原版混淆）。
     */
    @Nullable
    private static Object getMaidForRender(Level level) {
        try {
            if (cachedMaid != null && cachedMaidLevel == level && cachedMaid instanceof LivingEntity) {
                return cachedMaid;
            }
            Class<?> maidClass = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
            Constructor<?> ctor = maidClass.getConstructor(Level.class);
            Object maid = ctor.newInstance(level);
            if (maidSetModelId == null) {
                try {
                    maidSetModelId = maidClass.getMethod("setModelId", String.class);
                } catch (NoSuchMethodException e) {
                    // 方法名兜底：按「单 String 参数、void 返回」签名查找
                    for (Method method : maidClass.getMethods()) {
                        if (method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == String.class
                                && method.getReturnType() == void.class) {
                            maidSetModelId = method;
                            break;
                        }
                    }
                }
            }
            if (maidClearResidueMethod == null) {
                try {
                    Class<?> cacheUtil = Class.forName("com.github.tartaricacid.touhoulittlemaid.util.EntityCacheUtil");
                    try {
                        maidClearResidueMethod = cacheUtil.getMethod("clearMaidDataResidue", maidClass, boolean.class);
                    } catch (NoSuchMethodException e) {
                        // 方法名兜底：静态、两参（第二参 boolean）、void 返回
                        for (Method method : cacheUtil.getMethods()) {
                            if (Modifier.isStatic(method.getModifiers())
                                    && method.getParameterCount() == 2
                                    && method.getParameterTypes()[1] == boolean.class
                                    && method.getReturnType() == void.class) {
                                maidClearResidueMethod = method;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            cachedMaid = maid;
            cachedMaidLevel = level;
            return maid;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射调用 EntityMaid#setModelId(String) 切换预览模型；失败静默忽略。 */
    private static void setMaidModelId(Object maid, String modelId) {
        try {
            if (maidSetModelId != null) {
                maidSetModelId.invoke(maid, modelId);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 反射调用 EntityCacheUtil#clearMaidDataResidue(maid, false) 清除渲染残留；失败静默忽略。 */
    private static void clearMaidDataResidue(Object maid) {
        try {
            if (maidClearResidueMethod != null) {
                maidClearResidueMethod.invoke(null, maid, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
