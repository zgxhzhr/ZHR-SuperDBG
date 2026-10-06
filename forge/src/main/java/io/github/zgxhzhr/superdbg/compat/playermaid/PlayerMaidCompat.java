package io.github.zgxhzhr.superdbg.compat.playermaid;

import io.github.zgxhzhr.superdbg.entity.EntityEditorData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * 「人是狐」（playermaid）玩家调试软兼容层。
 * <p>
 * 零编译依赖：playermaid 的 API 全部通过反射调用，未安装时本类除
 * {@link #LOADED} 检测外不会被实际调用，实体编辑器不显示人是狐页签。
 * <p>
 * 反射说明：playermaid 随模组 jar 发布，其自有 API 类名/方法名在开发与生产
 * 环境保持一致（重混淆仅作用于原版 Minecraft 的方法名），因此可直接按
 * Mojmap 签名获取 {@code FoxMaidApi} 的静态方法。
 */
public final class PlayerMaidCompat {

    /** 人是狐模组 id */
    public static final String MODID = "playermaid";

    private static final String API_CLASS = "io.github.zgxhzhr.playermaid.api.FoxMaidApi";

    /** 好感度点数上限（与 playermaid Constants.MAX_FAVORABILITY 一致） */
    public static final int MAX_FAVORABILITY = 384;

    /** 人是狐是否已加载且 API 类可解析。所有调用方必须先过此守卫。 */
    public static final boolean LOADED = detect();

    private static Method isActive;
    private static Method setActive;
    private static Method getRenderName;
    private static Method setRenderName;
    private static Method getOwnerName;
    private static Method setOwnerName;
    private static Method getFavorability;
    private static Method setFavorability;
    private static Method getSchedule;
    private static Method setSchedule;
    private static Method isInvulnerable;
    private static Method setInvulnerable;
    private static Method getSlabModelId;
    private static Method setSlabModelId;

    private PlayerMaidCompat() {
    }

    private static boolean detect() {
        try {
            if (!ModList.get().isLoaded(MODID)) {
                return false;
            }
            Class<?> api = Class.forName(API_CLASS);
            isActive = api.getMethod("isActive", ServerPlayer.class);
            setActive = api.getMethod("setActive", ServerPlayer.class, boolean.class);
            getRenderName = api.getMethod("getRenderName", ServerPlayer.class);
            setRenderName = api.getMethod("setRenderName", ServerPlayer.class, String.class);
            getOwnerName = api.getMethod("getOwnerName", ServerPlayer.class);
            setOwnerName = api.getMethod("setOwnerName", ServerPlayer.class, String.class);
            getFavorability = api.getMethod("getFavorability", ServerPlayer.class);
            setFavorability = api.getMethod("setFavorability", ServerPlayer.class, int.class);
            getSchedule = api.getMethod("getSchedule", ServerPlayer.class);
            setSchedule = api.getMethod("setSchedule", ServerPlayer.class, String.class);
            isInvulnerable = api.getMethod("isInvulnerable", ServerPlayer.class);
            setInvulnerable = api.getMethod("setInvulnerable", ServerPlayer.class, boolean.class);
            getSlabModelId = api.getMethod("getSlabModelId", ServerPlayer.class);
            setSlabModelId = api.getMethod("setSlabModelId", ServerPlayer.class, String.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读取玩家的人是狐调试快照。
     * 非玩家实体或人是狐未加载时返回 {@code null}（界面不显示该页签）。
     */
    public static EntityEditorData.FoxMaidSnapshot snapshot(LivingEntity target) {
        if (!LOADED || !(target instanceof ServerPlayer player)) {
            return null;
        }
        try {
            boolean active = (boolean) isActive.invoke(null, player);
            String renderName = (String) getRenderName.invoke(null, player);
            String ownerName = (String) getOwnerName.invoke(null, player);
            int favorability = (int) getFavorability.invoke(null, player);
            String schedule = (String) getSchedule.invoke(null, player);
            boolean invulnerable = (boolean) isInvulnerable.invoke(null, player);
            String slabModelId = (String) getSlabModelId.invoke(null, player);
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] PlayerMaidCompat.snapshot: getSlabModelId={}", slabModelId);
            return new EntityEditorData.FoxMaidSnapshot(true, active,
                    emptyToNull(renderName), emptyToNull(ownerName),
                    favorability, schedule, invulnerable, emptyToNull(slabModelId));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 全量写回人是狐调试修改。
     * <p>
     * 空串/空白字符串一律归一化为 null（清除语义）；好感度钳制 0-384；
     * 日程名非法时 playermaid 侧自行回退白天。
     */
    public static void apply(LivingEntity target, EntityEditorData.FoxMaidSnapshot snap) {
        if (!LOADED || snap == null || !snap.present()
                || !(target instanceof ServerPlayer player)) {
            return;
        }
        try {
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] PlayerMaidCompat.apply: snap.slabModelId={}", snap.slabModelId());
            setActive.invoke(null, player, snap.active());
            setRenderName.invoke(null, player, emptyToNull(snap.renderName()));
            setOwnerName.invoke(null, player, emptyToNull(snap.ownerName()));
            int favorability = Math.max(0, Math.min(MAX_FAVORABILITY, snap.favorability()));
            setFavorability.invoke(null, player, favorability);
            setSchedule.invoke(null, player, snap.schedule());
            setInvulnerable.invoke(null, player, snap.invulnerable());
            setSlabModelId.invoke(null, player, emptyToNull(snap.slabModelId()));
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] PlayerMaidCompat.apply: setSlabModelId 反射调用成功 value={}", snap.slabModelId());
        } catch (Exception e) {
            // 反射写回失败不影响其余调试项的提交；打印异常便于排查
            io.github.zgxhzhr.superdbg.Constants.LOG.warn("[SuperDbg] PlayerMaidCompat.apply: 反射写回异常", e);
        }
    }

    /**
     * 单独写回魂符展示模型 id（点选后立即持久化，不经全量快照）。
     * 空白归一化为 null（清除语义）；未安装人是狐或反射失败时静默并打印日志。
     */
    public static void setSlabModelId(ServerPlayer player, @Nullable String modelId) {
        if (!LOADED) {
            return;
        }
        try {
            setSlabModelId.invoke(null, player, emptyToNull(modelId));
            io.github.zgxhzhr.superdbg.Constants.LOG.info(
                    "[SuperDbg] PlayerMaidCompat.setSlabModelId: 反射调用成功 value={}", modelId);
        } catch (Exception e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] PlayerMaidCompat.setSlabModelId: 反射调用异常", e);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
