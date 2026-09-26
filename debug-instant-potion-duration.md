# Debug Session: instant-potion-duration

## Status: [OPEN]

## Symptoms
瞬间治疗药水设置了 duration（非 0、非永久）后：
1. HUD 效果栏不显示时长
2. tick 持续应用逻辑未生效
3. amplifier 255 无效果

## Expected
- 有 duration 的 instant 效果进入 activeEffects
- HUD 显示时长
- 每 tick 调用 applyInstantenousEffect
- amplifier 255 有实际数值计算（非溢出为 0）

## Hypotheses
- H1: PotionItemMixin 未被加载（mixins.json 配置问题或 refmap 冲突）
- H2: RETURN 时 itemstack 已变成玻璃瓶，getMobEffects 返回空
- H3: addEffect 成功但 activeEffects 在原版 tick 中被提前移除
- H4: tick Mixin 触发但 applyInstantenousEffect 计算的 amount 仍为 0
- H5: 原版 MobEffectInstance.update 的 hiddenEffect 机制与我们 addEffect 冲突

## Evidence Collected
（待调试运行后填充）

## Fix Applied
（待证据收集后确定）

## Verification
- [ ] 启动日志确认 Mixin apply 成功
- [ ] 调试服务器收到 instrument 事件
- [ ] addEffect 后 activeEffects 中存在
- [ ] tick 被触发且计算正确
