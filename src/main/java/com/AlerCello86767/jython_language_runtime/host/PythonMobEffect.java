package com.AlerCello86767.jython_language_runtime.host;

import java.util.Map;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 状态效果转发宿主：把原版 {@link MobEffect} 的钩子转发给 Python 传进来的函数。
 *
 * <p>与其它宿主不同，本类的「行为」不是 Python 类而是**若干独立函数**
 * （{@code onTick} / {@code onApply} / {@code onRemove}）。因此这里把它们收拢进一张
 * 名字表，再用 {@link PyHandles} 包装：句柄在构造期一次性 {@code preload}，
 * 热路径（每 tick）只剩一次 map 查找，不会反复做 {@code __findattr__}。
 *
 * <p>Python 未实现某钩子时，{@link PyHandles} 命中「不存在」哨兵并直接回退原版行为。
 *
 * <p>26.1.2 实地核实（javap）后的钩子清单：
 * <ul>
 *   <li>{@code applyEffectTick(ServerLevel, LivingEntity, int)} → 每 tick，{@code onTick(entity, amplifier)}</li>
 *   <li>{@code shouldApplyEffectTickThisTick(int, int)} → 原版默认返回 false（即「不 tick」），
 *       实现了 {@code onTick} 时必须覆写为 true，否则每 tick 回调根本不会触发</li>
 *   <li>{@code onEffectAdded(LivingEntity, int)} → 施加时，{@code onApply(entity, amplifier)}</li>
 *   <li>{@code onEffectRemoved(MobEffectInstance, LivingEntity)} → 移除时，{@code onRemove(entity, amplifier)}；
 *       这是 Fabric（fabric-entity-events-v1 的 {@code FabricMobEffect}）注入的钩子，
 *       原版 {@code MobEffect} 本版本**没有** onEffectRemoved / onEffectExpired 之类的移除回调，
 *       原版的 {@code onMobRemoved(...)} 语义是「实体被移出世界」而非「效果被移除」，故不用它</li>
 *   <li>属性修饰符的挂载/卸下由原版 {@code addAttributeModifiers}/{@code removeAttributeModifiers}
 *       统一处理，宿主只需在构造前用 {@code addAttributeModifier(...)} 声明即可，无需再转发</li>
 * </ul>
 */
public class PythonMobEffect extends MobEffect {
    /** H1：构造期解析并缓存全部 Python 函数句柄。 */
    private final PyHandles handles;

    public PythonMobEffect(MobEffectCategory category, int color, Map<String, PyObject> callbacks) {
        super(category, color);
        this.handles = new PyHandles(new CallbackTable(callbacks));
        handles.preload("onTick", "onApply", "onRemove");
    }

    /** Python 是否实现了每 tick 回调（供外层决定是否声明周期属性修饰符之外的行为）。 */
    public boolean hasTickCallback() {
        return handles.has("onTick");
    }

    /**
     * 原版判定「本 tick 是否执行 applyEffectTick」。默认实现返回 false，自定义效果必须覆写，
     * 否则 {@code onTick} 永远不会被调用。
     */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return handles.has("onTick") || super.shouldApplyEffectTickThisTick(duration, amplifier);
    }

    /** 每 tick：Python 返回布尔则作为原版返回值，返回 None / 未实现则回退原版（默认 true）。 */
    @Override
    public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
        Boolean result = handles.forward("onTick", Boolean.class, entity, amplifier);
        return result != null ? result : super.applyEffectTick(level, entity, amplifier);
    }

    /** 施加时（原版钩子，经 MobEffectInstance.onEffectAdded 触发）。 */
    @Override
    public void onEffectAdded(LivingEntity entity, int amplifier) {
        super.onEffectAdded(entity, amplifier);
        handles.call("onApply", entity, amplifier);
    }

    /** 移除时（Fabric 注入钩子，原版无对应方法）。 */
    @Override
    public void onEffectRemoved(MobEffectInstance instance, LivingEntity entity) {
        handles.call("onRemove", entity, instance.getAmplifier());
    }

    /**
     * 让 {@link PyHandles} 能在「一组独立函数」上按名解析：重写 {@link PyObject#__findattr_ex__}，
     * 把名字直接映射到对应函数对象（不存在返回 null，由 PyHandles 缓存成「不存在」哨兵）。
     */
    private static final class CallbackTable extends PyObject {
        private final Map<String, PyObject> functions;

        CallbackTable(Map<String, PyObject> functions) {
            this.functions = functions;
        }

        @Override
        public PyObject __findattr_ex__(String name) {
            return functions.get(name);
        }
    }
}
