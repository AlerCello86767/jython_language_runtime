package com.AlerCello86767.jython_language_runtime.host;

import java.util.Map;

import org.python.core.Py;
import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect;
import net.minecraft.world.phys.Vec3;

/**
 * 自定义附魔效果组件宿主（L4.5 可选部分）：把 Python 函数包装成一个**可被数据包引用的**
 * {@link EnchantmentEntityEffect} 类型。
 *
 * <p>注册后即可在附魔 JSON 里以 {@code {"type": "mymod:auto_smelt"}} 引用（datagen 侧用
 * {@code PyDatagen.enchantments} 的 {@code effects} 原始透传或 {@code postAttack.effect} 写这个 type）：
 *
 * <pre>{@code
 * from com.AlerCello86767.jython_language_runtime.host import PythonEnchantmentEffect
 *
 * PythonEnchantmentEffect.register("auto_smelt", {
 *     "onApply": lambda level, enchantment_level, entity, x, y, z: ...,
 * })
 * }</pre>
 *
 * <p>26.1.2 实地核实（javap）：
 * <ul>
 *   <li>{@code BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE} 是
 *       {@code Registry<MapCodec<? extends EnchantmentEntityEffect>>}——它是**静态注册表**，
 *       可以在模组初始化窗口内注册，键即 JSON 里的 {@code "type"}</li>
 *   <li>{@code EnchantmentEntityEffect} 只需实现 {@code apply(ServerLevel, int, EnchantedItemInUse,
 *       Entity, Vec3)} 与 {@code codec()}；{@code onChangedBlock} 有默认实现</li>
 *   <li>本效果无数据字段，故 {@code codec()} 用 {@link MapCodec#unit(Object)} 返回注册时那个同一实例，
 *       序列化只写 {@code {"type": ...}}，反序列化复用该实例（单例）</li>
 * </ul>
 *
 * <p><b>已知缺口 / 风险</b>：本效果只在服务端 {@code apply} 时回调 Python，且 ABI 与运行时里的
 * 「对象型宿主」不同——它没有 Python 类实例，只有一个函数。当前回归范围（compileJava /
 * runDatagen）不加载数据包，故该路径**未做运行时验证**；若目标版本改为校验 dispatch 的 MapCodec
 * 必须来自注册表且带字段，可退化为使用原版 {@code minecraft:run_function} + 数据驱动 loot function，
 * 或把 {@code EnchantedItemInUse} 一并传给 Python 做更细粒度的处理。
 */
public final class PythonEnchantmentEffect implements EnchantmentEntityEffect {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyEnchantmentEffect");

    /** Python 回调：{@code (level, enchantmentLevel, entity, x, y, z)}。 */
    private final PyObject callback;
    /** 本实例的序列化器；注册进注册表的正是它，{@link #codec()} 返回同一个对象。 */
    private final MapCodec<PythonEnchantmentEffect> codec;

    private PythonEnchantmentEffect(PyObject callback) {
        this.callback = callback;
        this.codec = MapCodec.unit(this);
    }

    /**
     * 注册一个自定义附魔效果类型。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code onApply}（Python 函数，必填）：签名 {@code (level, enchantmentLevel, entity, x, y, z)}；
     *       {@code level} 是 {@code ServerLevel}，坐标为 double</li>
     * </ul>
     *
     * <p>只能在模组初始化窗口内调用（注册表写入）。
     */
    public static void register(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        Object onApply = options == null ? null : options.get("onApply");
        if (!(onApply instanceof PyObject py)) {
            throw new IllegalArgumentException("Python enchantment effect '" + id
                    + "' requires an 'onApply' Python function");
        }
        PythonEnchantmentEffect effect = new PythonEnchantmentEffect(py);
        Registry.register(BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE, id, effect.codec);
        LOGGER.info("Registered enchantment entity effect {}", id);
    }

    @Override
    public void apply(ServerLevel level, int enchantmentLevel, EnchantedItemInUse item,
                      Entity entity, Vec3 origin) {
        callback.__call__(new PyObject[] {
                Py.java2py(level),
                Py.java2py(enchantmentLevel),
                Py.java2py(entity),
                Py.java2py(origin.x),
                Py.java2py(origin.y),
                Py.java2py(origin.z),
        });
    }

    @Override
    public MapCodec<? extends EnchantmentEntityEffect> codec() {
        return codec;
    }
}
