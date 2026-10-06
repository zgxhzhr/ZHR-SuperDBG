package io.github.zgxhzhr.superdbg.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * 玩家自定义渲染名的服务端存储。
 * <p>
 * 直接写入实体自身的持久化数据（Forge 的 {@code getPersistentData()}），随玩家存档保存，
 * 不依赖任何第三方模组。渲染名仅替换头顶悬浮名牌与 Jade 标题，
 * 玩家的真实名字、Tab 列表、聊天与死亡消息均不受影响。
 */
public final class RenderNameStore {

    /** 存储键；带模组命名空间，避免与其他模组写入的持久化数据冲突。 */
    private static final String KEY = "superdbg:render_name";

    private RenderNameStore() {
    }

    /**
     * 读取渲染名；未设置或内容为空白时返回 {@code null}。
     */
    @Nullable
    public static String get(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(KEY)) {
            return null;
        }
        String value = data.getString(KEY);
        return value.isBlank() ? null : value;
    }

    /**
     * 写入渲染名；{@code null} 或空白表示清除。
     */
    public static void set(Entity entity, @Nullable String renderName) {
        CompoundTag data = entity.getPersistentData();
        if (renderName == null || renderName.isBlank()) {
            data.remove(KEY);
        } else {
            data.putString(KEY, renderName.trim());
        }
    }
}
