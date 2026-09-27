package io.github.zgxhzhr.superdbg.mixin;

import io.github.zgxhzhr.superdbg.compat.YuyuCompat;
import io.github.zgxhzhr.superdbg.entity.RemovalGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LivingEntity 兼容层：全部使用 {@code @Inject} 而非 {@code @Overwrite}，
 * 保证与其它模组（Fabric 事件、死亡事件等）对同一方法的注入共存。
 * <p>
 * 两类逻辑：
 * <ul>
 *   <li><strong>泛用功能</strong>（所有整合包生效）：防移除（kill 拦截、同步标记）</li>
 *   <li><strong>yuyu 还原</strong>（仅 {@link YuyuCompat#LOADED} 时生效，即通天之路）：
 *       绕过 yuyu_weapon 的 ASM 补丁（伪造血量、死亡守卫、血量上限钳制）</li>
 * </ul>
 * 非 yuyu 环境且非编辑器操作时，本 Mixin 对血量/死亡路径零干预，
 * 原版与其它模组的逻辑（含对 die() 的注入，如 fabric-entity-events-v1）完整保留。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class LivingEntityMixin {

    @Shadow
    private static EntityDataAccessor<Float> DATA_HEALTH_ID;

    @Shadow
    public boolean dead;

    @Shadow
    protected abstract void dropAllDeathLoot(DamageSource source);

    /**
     * 防移除标记的 SyncedEntityData。
     * 必须在静态字段初始化时调用 defineId（类加载时），
     * 这样所有 defineId 调用都在 define() 之前完成，避免与原版/其他模组的字段 ID 冲突。
     */
    @Unique
    private static final EntityDataAccessor<Boolean> superdbg$DATA_REMOVAL_GUARD =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);

    /** 防移除：注册同步标记（泛用，所有包生效）。 */
    @Inject(method = "defineSynchedData()V", at = @At("TAIL"))
    private void superdbg$defineRemovalGuardData(CallbackInfo ci) {
        ((LivingEntity) (Object) this).getEntityData().define(superdbg$DATA_REMOVAL_GUARD, false);
    }

    /**
     * 防移除：从存档加载后恢复同步状态（泛用，所有包生效）。
     * <p>
     * 实体从 NBT 重建时 SyncedEntityData 会重置为 define 默认值（false），
     * 而 persistentData（ForgeData）此时已读取完成。若不恢复：
     * 服务端 {@link RemovalGuard#has} 回退 persistentData 仍判定为 true（逻辑还在），
     * 但客户端只能读 SyncedEntityData，Jade 的防移除字样永久消失。
     * <p>
     * 此处把持久化标记写回 SyncedEntityData，早于实体加入世界的初始同步，
     * 客户端收到的第一个数据包即携带正确状态。跨维度传送的 NBT 读写路径同样覆盖。
     */
    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void superdbg$restoreRemovalGuard(CompoundTag tag, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        Level level = self.level();
        if (level == null || level.isClientSide) {
            return;
        }
        if (RemovalGuard.has(self)) {
            // 世界可见层已存在另一只同 UUID 实体（本体残影/旧档副本）→ 副本不套守卫，
            // 避免同 uuid 占位导致"放出的女仆消失"
            if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                net.minecraft.world.entity.Entity existing = sl.getEntity(self.getUUID());
                if (existing != null && existing != self) {
                    RemovalGuard.set(self, false);
                    return;
                }
            }
            // 从存档还原：先恢复被伪造前的真 UUID（存档期间对外是假 UUID），再重新套守卫
            RemovalGuard.restoreRealUuid(self);
            RemovalGuard.set(self, true);
            // 重进时实体脚的 Y 坐标常与方块边界对齐（NBT 存的是整数方块坐标），
            // 加载后会卡进脚下方块一格。上抬一格让重力自然落位到方块顶部。
            // 仅在"从存档恢复"路径执行，不影响编辑器手动开防移除的场景。
            if (self instanceof net.minecraft.server.level.ServerPlayer) {
                // ServerPlayer.teleportTo 内部走 this.connection.teleport(...)，
                // 登录加载 NBT 阶段 connection 尚未建立（null）→ NPE →
                // "Couldn't place player in world" 连接丢失。
                // 玩家改用纯位置写入（与原版 Entity.load 读 Pos 同机制，不发数据包），
                // 登录流程随后会用当前位置向客户端同步出生坐标。
                self.moveTo(self.getX(), self.getY() + 1.0, self.getZ(),
                        self.getYRot(), self.getXRot());
            } else {
                self.teleportTo(self.getX(), self.getY() + 1.0, self.getZ());
            }
        }
    }

    /**
     * 防移除：拦截 /kill 指令（泛用，所有包生效）。
     * <p>
     * LivingEntity.kill() 内部是 hurt(genericKill, MAX_VALUE)，走正常死亡流程，
     * 不会被 RemovalGuardMixin 的 setRemoved 拦截，必须在此拦截。
     */
    @Inject(method = "kill()V", at = @At("HEAD"), cancellable = true)
    private void superdbg$guardKill(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (RemovalGuard.has(self) && !RemovalGuard.isBypassing()) {
            ci.cancel();
            RemovalGuard.logIntercepted(self, "击杀（kill()）", RemovalGuard.findIllegalCaller());
        }
    }

    /**
     * 守卫实体的伤害防护（泛用，所有包生效）：genericKill/虚空免疫 + 秒杀伤害免疫
     * （单次伤害 > 最大生命 2 倍 → 0 伤害；一般高伤害限幅最大生命 15%）。
     * 事件式，不干预守卫实体的日常 AI/动画/移动。
     */
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$guardHurt(net.minecraft.world.damagesource.DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self) || RemovalGuard.isBypassing()) {
            return;
        }
        if (source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)
                || source.is(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD)) {
            cir.setReturnValue(false);
            RemovalGuard.logIntercepted(self, "用 genericKill/虚空伤害清除", RemovalGuard.findIllegalCaller());
            return;
        }
        float hp = self.getHealth();
        float maxHp = self.getMaxHealth();
        if (maxHp > 0.0F && amount > maxHp) {
            if (amount > maxHp * 2.0F) {
                // 秒杀级伤害（清除工具恒定 21 亿，恒大于上限 2 倍）：完全免疫，打不动
                cir.setReturnValue(false);
                RemovalGuard.logIntercepted(self, "秒杀级伤害攻击（伤害值=" + amount + "）", RemovalGuard.findIllegalCaller());
            } else {
                // 一般高伤害（不超过上限 2 倍，如玩家暴击）：限幅为最大生命 15%，可被磨死
                float actual = maxHp * 0.15F;
                float newHp = Math.max(1.0F, hp - actual);
                // 内部写血绕过守卫（否则栈帧含外部攻击者时会被 guardSetHealth 误拦）
                RemovalGuard.runWithoutGuard(() -> self.setHealth(newHp));
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * 守卫实体的死亡保护：被"强行召唤死亡"（清除物品的 Run die() method，
     * 血量尚未归零时直接调 die）→ 取消，不扣血不进死亡流程。
     * 正常伤害死亡（血量 ≤0 后）走原版流程（DYING 放行清理）。
     */
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$guardDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self) || RemovalGuard.isBypassing()) {
            return;
        }
        if (self.getHealth() > 0.0F) {
            ci.cancel(); // 异常伪死亡，取消
            RemovalGuard.logIntercepted(self,
                    "强行召死 die()（血量未归零 hp=" + self.getHealth() + "）", RemovalGuard.findIllegalCaller());
            return;
        }
        // 血量为 0 也可能是清除工具先 setHealth(0) 再 die() 伪装的"正常死亡"：
        // 原版死亡 die() 由 hurt() 内部调用，栈帧全白名单；栈帧含外部模组类 → 拦截
        String caller = RemovalGuard.findIllegalCaller();
        if (caller != null) {
            ci.cancel();
            RemovalGuard.logIntercepted(self,
                    "调 die() 伪装死亡（hp=" + self.getHealth() + "）", caller);
        }
    }

    /**
     * 防移除：拦截外部模组直接压低守卫实体血量（清除工具 setHealth(0) 伪装死亡的绕过手法）。
     * 原版 hurt 流程内部调 setHealth，栈帧全白名单 → 放行；外部模组直接调用 → 取消。
     * 只拦"压低"，回血/再生放行。
     */
    @Inject(method = "setHealth(F)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$guardSetHealth(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self) || RemovalGuard.isBypassing()) {
            return;
        }
        if (Boolean.TRUE.equals(RemovalGuard.DYING.get())) {
            return; // 死亡流程内部的血量归零放行
        }
        if (health >= self.getHealth()) {
            return; // 回血/不变放行
        }
        String caller = RemovalGuard.findIllegalCaller();
        if (caller != null) {
            ci.cancel();
            RemovalGuard.logIntercepted(self,
                    "压低血量（" + self.getHealth() + " → " + health + "）", caller);
        }
    }

    /**
     * yuyu 还原：返回 SynchedEntityData 中存储的真实血量，绕过 yuyu 的 fakefull 伪造。
     * 非 yuyu 环境不干预（等价原版读取路径）。
     * 客户端跟服务端同步血量包，不走此还原逻辑——避免在渲染线程改写 EntityData。
     */
    @Inject(method = "getHealth()F", at = @At("HEAD"), cancellable = true)
    private void superdbg$returnRealHealth(CallbackInfoReturnable<Float> cir) {
        if (!YuyuCompat.LOADED) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        cir.setReturnValue(self.getEntityData().get(DATA_HEALTH_ID));
    }

    /**
     * yuyu 还原：按真实血量判定是否死亡，绕过 yuyu 的永活补丁。
     * 客户端不接管——客户端死亡状态由服务端同步。
     */
    @Inject(method = "isDeadOrDying()Z", at = @At("HEAD"), cancellable = true)
    private void superdbg$realDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        if (!YuyuCompat.LOADED) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        cir.setReturnValue(self.getHealth() <= 0.0F);
    }

    /**
     * yuyu 还原：绕过 yuyu 对 setHealth 的拦截，直接写入 SynchedEntityData。
     * 附带 clamp 到 [0, getMaxHealth()]（恢复原版钳制语义）和 int 溢出保护。
     * 非 yuyu 环境不干预（保留原版 clamp 到 maxHealth 的行为）。
     * 客户端不接管——客户端 setHealth 由服务端同步包触发，保留原版逻辑。
     */
    @Inject(method = "setHealth(F)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$setHealthDirect(float health, CallbackInfo ci) {
        if (!YuyuCompat.LOADED) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        // 恢复原版 clamp 语义：健康值不得超过最大生命，也不得为负
        float maxHp = self.getMaxHealth();
        if (health > maxHp) {
            health = maxHp;
        }
        if (health < 0.0F) {
            health = 0.0F;
        } else if (health > Integer.MAX_VALUE) {
            health = Integer.MAX_VALUE;
        }
        // force=true 确保即使值未变也标记 dirty（同步到客户端）
        self.getEntityData().set(DATA_HEALTH_ID, health, true);
        ci.cancel();
    }

    /**
     * 死亡接管（条件化，不再使用 @Overwrite）。
     * <p>
     * 接管条件（满足其一）：
     * <ul>
     *   <li>{@link RemovalGuard#isBypassing()}：实体编辑器的移除/清血量操作，
     *       需绕过<strong>任何模组</strong>的死亡守卫（泛用）</li>
     * </ul>
     * <p>
     * <strong>玩家不走接管</strong>：原版 die() 内部负责触发死亡画面、respawn 流程，
     * 拦截后玩家会卡在 dead=true 但没死亡界面的状态（isAlive() 返回 false 导致后续
     * 编辑器提交被拒）。yuyu 环境下只需要绕过 setHealth 的 ASM patch 让血量真正归 0，
     * die() 原版会被 health≤0 触发后正常执行玩家死亡流程。
     */
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$takeoverDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        // 客户端死亡状态由服务端同步包驱动，原版 die() 内部依赖 level.getServer()，
        // 客户端返回 null 会 NPE（清除类武器在客户端 Render thread 调 Player.attack → hurt → die 触发）
        if (self.level().isClientSide) {
            return;
        }
        // 玩家不走接管：原版 die() 负责触发 respawn/death screen
        if (self instanceof Player) {
            return;
        }
        // 非玩家：只在编辑器 BYPASS 时接管，绕过所有模组死亡守卫
        if (!RemovalGuard.isBypassing()) {
            return;
        }
        if (this.dead) {
            ci.cancel();
            return;
        }
        // 标记"正常死亡中"，使 RemovalGuard 放行 die() 内部触发的 setRemoved
        RemovalGuard.DYING.set(true);
        try {
            // 血量归零（服务端侧）
            self.getEntityData().set(DATA_HEALTH_ID, 0.0F, true);
            // 设置死亡标志
            this.dead = true;
            // 掉落物品与经验（dropAllDeathLoot 内部会调用 dropExperience）
            dropAllDeathLoot(source);
            // 非玩家直接从世界移除
            self.discard();
        } finally {
            RemovalGuard.DYING.set(false);
        }
        ci.cancel();
    }
}
