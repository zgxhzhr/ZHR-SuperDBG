package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 守卫实体 onMove 入口保护（两层）。
 * <p>
 * 第三方清除武器绕过 {@code setPos} 的路径：反射直写 {@code position} 字段
 * 后，直接调用 {@code PersistentEntitySectionManager$Callback.onMove()}。
 * onMove 的新区块由 {@code entity.blockPosition()} 缓存现算——武器会把缓存
 * 污染到不 tick 的虚空区块（实测 section(128,-4,3072)，未超出 3.0E7，纯坐标
 * 越界判据拦不住），导致 section 归属登记错误，实体冻结成幽灵。
 * <p>
 * 第一层（主）：onMove HEAD 先调用 {@link RemovalGuard#healBlockPosCache}
 * 把被污染的 blockPosition 缓存写回实时坐标对应的正确值，随后原版 onMove
 * 按正确位置重新登记 section 归属——从根上让武器的缓存污染失效。
 * 第二层（兜底）：若连实时坐标本身也越界（|x/z|>3E7 或 y∉[-256,4096]）
 * 且调用栈含非白名单帧，直接取消本次 onMove。
 * <p>
 * 目标类是包私有内部类，编译期不可见，用字符串形式指定；entity 字段用反射读取
 * （开发环境名 entity / 生产环境 SRG 名 f_157609_，双名回退）。
 * <p>
 * <b>注意</b>：字符串 targets 的 Mixin 注解处理器无法生成 refmap 条目，
 * 方法名必须同时列出开发名（onMove）与生产 SRG 名（m_142044_）并 remap=false，
 * 否则生产环境注入失败 + require=1 直接启动崩溃。
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback", remap = false)
public abstract class CallbackOnMoveGuardMixin {

    private static final java.lang.reflect.Field ENTITY_FIELD;

    static {
        java.lang.reflect.Field f = null;
        try {
            Class<?> cbClass = Class.forName("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback");
            for (String name : new String[]{"entity", "f_157609_"}) {
                try {
                    f = cbClass.getDeclaredField(name);
                    f.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (ReflectiveOperationException e) {
            io.github.zgxhzhr.superdbg.Constants.LOG.error("[SuperDbg] CallbackOnMoveGuardMixin 反射初始化失败", e);
        }
        ENTITY_FIELD = f;
    }

    @Inject(method = {"onMove()V", "m_142044_()V"}, at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void superdbg$guardExtremeOnMove(CallbackInfo ci) {
        if (ENTITY_FIELD == null) {
            return;
        }
        Entity entity;
        try {
            entity = (Entity) ENTITY_FIELD.get(this);
        } catch (IllegalAccessException e) {
            return;
        }
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        if (!RemovalGuard.has(living)) {
            return;
        }
        if (RemovalGuard.isBypassing()) {
            return;
        }
        // 第一层：先修复被污染的 blockPosition 缓存，让原版 onMove 按正确位置登记
        RemovalGuard.healBlockPosCache(living);
        // 第二层：实时坐标本身越界（武器尚未把 position 写回）+ 非白名单调用栈 → 取消
        double x = living.getX();
        double y = living.getY();
        double z = living.getZ();
        boolean extreme = Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7 || y < -256.0 || y > 4096.0;
        if (!extreme) {
            return;
        }
        String caller = RemovalGuard.findIllegalCaller();
        if (caller != null) {
            ci.cancel();
            io.github.zgxhzhr.superdbg.Constants.LOG.warn(
                    "[SuperDbg] 拦截守卫实体极端坐标 onMove: {} pos=({},{},{}) caller={}",
                    living, x, y, z, caller);
        }
    }
}
