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
     * 守卫实体的伤害防护（泛用，所有包生效）：完全免疫一切伤害。
     * <p>
     * 开启防移除的实体不允许收到任何伤害——包括普通战斗伤害。直接返回 false，
     * 不进入受击/击退/无敌帧/扣血任何流程，因此也不会有减血、死亡或异常状态结算。
     * 调试器主动处置（编辑器伤害测试、强制移除）走 BYPASS/DYING 旁路，不受影响。
     */
    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$guardHurt(net.minecraft.world.damagesource.DamageSource source, float amount,
                                   CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self) || RemovalGuard.isBypassing()
                || Boolean.TRUE.equals(RemovalGuard.DYING.get())) {
            return;
        }
        RemovalGuard.markProtected(self);
        cir.setReturnValue(false);
        RemovalGuard.logInterceptedThrottled(self,
                "免疫伤害（来源=" + source.getMsgId() + "，伤害值=" + amount + "）",
                RemovalGuard.findIllegalCaller());
    }

    /**
     * 守卫实体的实际扣血层防护（泛用，所有包生效）。
     * <p>
     * {@code actuallyHurt} 是所有"真正生效的伤害"的唯一必经入口。部分模组
     * （含个别覆写 hurt 的 Boss 与 GoetyRevelation 的尾杀实现）直接调用
     * actuallyHurt 绕过 {@code hurt}，只守 hurt 会漏掉这些路径。
     * 此处独立设防：守卫实体一律免疫（见 {@link RemovalGuard#blockActuallyHurt}）。
     * <p>
     * 注意：{@code Player.actuallyHurt} 覆写且不调用 super，玩家的守卫在
     * {@link PlayerTickOverrideMixin} 中单独挂载，二者互不重叠、不会重复计数。
     */
    @Inject(method = "actuallyHurt(Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("HEAD"), cancellable = true)
    private void superdbg$guardActuallyHurt(net.minecraft.world.damagesource.DamageSource source,
                                            float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (RemovalGuard.blockActuallyHurt(self, source, amount)) {
            ci.cancel();
        }
    }

    /**
     * 守卫实体的死亡保护：一律取消死亡。
     * <p>
     * 开启防移除的实体不允许死亡：无论血量是否归零、无论调用栈是否可信，一律取消 die()，
     * 并在血量低于基准时恢复到基准值，避免停留在"血量归零却未死亡"的不一致状态。
     * 调试器主动处置（编辑器移除、强制移除补刀）走 BYPASS/DYING 旁路放行。
     * 玩家不走 {@code LivingEntity.die}（{@code ServerPlayer} 覆写了 die 且不调用 super），
     * 由 {@link ServerPlayerMixin} 单独兜。
     */
    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$guardDie(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self) || RemovalGuard.isBypassing()
                || Boolean.TRUE.equals(RemovalGuard.DYING.get())) {
            return;
        }
        ci.cancel();
        RemovalGuard.restoreHealthBaseline(self);
        RemovalGuard.logInterceptedThrottled(self,
                "死亡被拦截（hp=" + self.getHealth() + "）", RemovalGuard.findIllegalCaller());
    }

    /**
     * 防移除：拦截一切降低守卫实体血量的写入（除调试器设置外）。
     * <p>
     * 原版 hurt 流程内部的减血也会被本层拦下——但伤害入口已被 hurt/actuallyHurt 层
     * 完全免疫，正常路径根本走不到这里；本层主要用于挡下外部模组直接调 setHealth(0)
     * 伪装死亡的绕过手法。调试器自身的写血在 BYPASS/DYING 下放行，
     * 并把血量基准同步到新值，避免下一 tick 被判定为非法降血回滚。
     */
    @Inject(method = "setHealth(F)V", at = @At("HEAD"), cancellable = true)
    private void superdbg$guardSetHealth(float health, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide) {
            return;
        }
        if (!RemovalGuard.has(self)) {
            return;
        }
        if (RemovalGuard.isBypassing() || Boolean.TRUE.equals(RemovalGuard.DYING.get())) {
            // 调试器主动写血：放行，并把基准同步到新值
            RemovalGuard.syncHealthBaseline(self, health);
            return;
        }
        if (health >= self.getHealth()) {
            return; // 回血/不变放行
        }
        RemovalGuard.markProtected(self);
        ci.cancel();
        // 同一实体高频拉锯时日志限流（真正生效的是 tick 级血量回滚）
        RemovalGuard.logInterceptedThrottled(self,
                "压低血量（" + self.getHealth() + " → " + health + "）",
                RemovalGuard.findIllegalCaller());
    }

    /**
     * 血量同步数据写入后的即时拉回（禁止降血）。
     * <p>
     * 少数实现用 MethodHandle/VarHandle 直接改写血量 DataItem 的 value 字段，
     * 绕过 setHealth 与 SynchedEntityData.set 的一切守卫，但写入后仍会调用
     * {@code Entity#onSyncedDataUpdated(accessor)}（原版写入路径同样会调用）。
     * 在该回调里核对：只要血量低于基准（且不是调试器设置的降血）就立即改回基准。
     * （完全绕过同步写入路径的实现由 {@link #superdbg$guardHealthRollback} 兜底。）
     */
    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V",
            at = @At("HEAD"))
    private void superdbg$rescueOnHealthWrite(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        if (accessor != DATA_HEALTH_ID) {
            return;
        }
        RemovalGuard.enforceHealthFloor((LivingEntity) (Object) this);
    }

    /**
     * 血量回滚守卫（每 tick，禁止降血主入口）。
     * <p>
     * 少数实现（如 GoetyRevelation 的尾杀）用 MethodHandle/VarHandle 直接改写血量
     * DataItem 的 value 字段，既绕过 {@code setHealth}，也不经过
     * {@code SynchedEntityData.set}（因此不触发 {@link #superdbg$rescueOnHealthWrite}）。
     * 这里以"上一刻血量"为基准做 tick 级核对：任何下降一律回滚到基准值
     * （只有调试器植入的伤害会被登记并保留），使守卫实体（含玩家，玩家 tick 会调用本方法）
     * 不会被任何外部来源降血或杀死。
     */
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void superdbg$guardHealthRollback(CallbackInfo ci) {
        RemovalGuard.guardTick((LivingEntity) (Object) this);
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
