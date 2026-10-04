package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asColor;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.asList;
import static com.AlerCello86767.jython_language_runtime.core.Params.asMap;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.host.PythonMobEffect;

import net.fabricmc.fabric.api.registry.FabricPotionBrewingBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;

/**
 * 状态效果（MobEffect）与药水（Potion / 酿造）注册门面。
 *
 * <p>Python 侧只发「声明式参数包」：
 * <pre>{@code
 * PyEffects.register("overclock", {
 *     "color": "#FF6600",
 *     "category": "beneficial",
 *     "onTick": lambda entity, amplifier: ...,
 *     "onApply": lambda entity, amplifier: ...,
 *     "onRemove": lambda entity, amplifier: ...,
 *     "attributes": [{"attribute": "minecraft:movement_speed",
 *                     "amount": 0.2, "op": "multiply_total"}],
 * })
 *
 * PyEffects.registerPotion("overclock_potion", {
 *     "effects": [{"effect": "mymod:overclock", "duration": 600, "amplifier": 0}],
 *     "base": "minecraft:awkward",
 *     "ingredient": "minecraft:sugar",
 * })
 * }</pre>
 *
 * <p>所有调用必须发生在模组初始化阶段（onInitialize 窗口内）。
 */
public final class PyEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyEffects");

    private PyEffects() {
    }

    /**
     * 注册一个状态效果。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code color}：粒子/图标颜色，支持整数 RGB 或 {@code "#RRGGBB"}（{@code "#AARRGGBB"} 亦可），
     *       缺省为白色 {@code 0xFFFFFF}</li>
     *   <li>{@code category} (String)：{@code beneficial} / {@code harmful} / {@code neutral}，
     *       缺省 {@code neutral}；非法值抛 {@link IllegalArgumentException}</li>
     *   <li>{@code onTick} (函数)：每 tick 回调，签名 {@code (entity, amplifier)}；返回布尔表示
     *       是否「生效」，返回 None 时按原版默认（true）处理</li>
     *   <li>{@code onApply} (函数)：施加时回调，签名 {@code (entity, amplifier)}</li>
     *   <li>{@code onRemove} (函数)：移除时回调，签名 {@code (entity, amplifier)}</li>
     *   <li>{@code attributes} (List&lt;Map&gt)：属性修饰符列表，每项 {@code attribute}（属性 id，必填）、
     *       {@code amount}（double，默认 0）、{@code op}（见下）、{@code id}（可选修饰符 id）</li>
     * </ul>
     *
     * <p>{@code op} 取值同时接受计划文档简写与原版序列化名：
     * {@code add} / {@code add_value}、{@code multiply_base} / {@code add_multiplied_base}、
     * {@code multiply_total} / {@code add_multiplied_total}。
     */
    public static void register(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        MobEffectCategory category = resolveCategory(asString(options.get("category"), "neutral"));
        int color = options.containsKey("color") ? asColor(options.get("color")) : 0xFFFFFF;

        PythonMobEffect effect = new PythonMobEffect(category, color, extractCallbacks(options));

        // attributes：在注册前用 addAttributeModifier 声明；挂载/卸下由原版
        // addAttributeModifiers / removeAttributeModifiers 自动完成
        List<?> attributes = asList(options.get("attributes"));
        if (attributes != null) {
            int index = 0;
            for (Object raw : attributes) {
                Map<String, Object> entry = asMap(raw);
                if (entry == null) {
                    throw new IllegalArgumentException("attributes[" + index + "] must be a map");
                }
                String attributeId = asString(entry.get("attribute"), null);
                if (attributeId == null) {
                    throw new IllegalArgumentException("attributes[" + index + "] requires 'attribute'");
                }
                Holder<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.get(ModIds.parse(attributeId))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown attribute: " + attributeId));
                Identifier modifierId = ModIds.parse(asString(entry.get("id"),
                        ModIds.of(path + "/modifier_" + index).toString()));
                effect.addAttributeModifier(attribute, modifierId,
                        asDouble(entry.get("amount"), 0.0d),
                        resolveOperation(asString(entry.get("op"), "add_value")));
                index++;
            }
        }

        Registry.register(BuiltInRegistries.MOB_EFFECT, id, effect);
        LOGGER.info("Registered mob effect {} (category={}, color={})",
                id, category.name(), Integer.toHexString(color));
    }

    /**
     * 注册一个药水，并可顺带接入酿造配方。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code effects} (List&lt;Map&gt)：药水效果列表，每项 {@code effect}（状态效果 id，必填）、
     *       {@code duration}（tick，默认 600）、{@code amplifier}（等级，默认 0）</li>
     *   <li>{@code base} (String)：酿造来源药水 id（如 {@code minecraft:awkward}）</li>
     *   <li>{@code ingredient} (String)：酿造材料物品 id（如 {@code minecraft:sugar}）</li>
     * </ul>
     *
     * <p>{@code base} 与 {@code ingredient} 同时给出时，通过 Fabric 的
     * {@code FabricPotionBrewingBuilder.BUILD} 事件登记「基础药水 + 材料 → 本药水」的酿造配方；
     * 任一缺失则只注册药水、不接入酿造。
     */
    public static void registerPotion(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);

        List<MobEffectInstance> instances = new ArrayList<>();
        List<?> effects = asList(options.get("effects"));
        if (effects != null) {
            int index = 0;
            for (Object raw : effects) {
                Map<String, Object> entry = asMap(raw);
                if (entry == null) {
                    throw new IllegalArgumentException("effects[" + index + "] must be a map");
                }
                String effectId = asString(entry.get("effect"), null);
                if (effectId == null) {
                    throw new IllegalArgumentException("effects[" + index + "] requires 'effect'");
                }
                Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.get(ModIds.parse(effectId))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown mob effect: " + effectId));
                instances.add(new MobEffectInstance(holder,
                        asInt(entry.get("duration"), 600),
                        asInt(entry.get("amplifier"), 0)));
                index++;
            }
        }

        // 药水名用于翻译键（item.minecraft.potion.effect.<name>），这里直接用注册 path
        Potion potion = new Potion(path, instances.toArray(new MobEffectInstance[0]));
        Registry.register(BuiltInRegistries.POTION, id, potion);
        LOGGER.info("Registered potion {}", id);

        String base = asString(options.get("base"), null);
        String ingredient = asString(options.get("ingredient"), null);
        if (base == null || ingredient == null) {
            LOGGER.info("Registered potion {} without brewing recipe "
                    + "(provide both 'base' and 'ingredient' to add one)", id);
            return;
        }

        Identifier baseId = ModIds.parse(base);
        Identifier ingredientId = ModIds.parse(ingredient);
        // 26.1 酿造已是「事件构建」而非 Java 静态注册表：挂到 BUILD 事件，等 PotionBrewing.bootstrap 时回调
        FabricPotionBrewingBuilder.BUILD.register(builder -> {
            Holder<Potion> baseHolder = BuiltInRegistries.POTION.get(baseId).orElse(null);
            Holder<Potion> resultHolder = BuiltInRegistries.POTION.get(id).orElse(null);
            if (baseHolder == null || resultHolder == null
                    || !BuiltInRegistries.ITEM.containsKey(ingredientId)) {
                LOGGER.warn("Skip brewing recipe {} (base={}, ingredient={}): unknown id",
                        id, baseId, ingredientId);
                return;
            }
            Item ingredientItem = BuiltInRegistries.ITEM.getValue(ingredientId);
            builder.addMix(baseHolder, ingredientItem, resultHolder);
        });
        LOGGER.info("Registered brewing recipe {} + {} -> {}", baseId, ingredientId, id);
    }

    // ---------- 参数翻译 ----------

    /** 从参数包取出三个 Python 函数（值须为可调用对象，否则抛错）。 */
    private static Map<String, PyObject> extractCallbacks(Map<String, Object> options) {
        Map<String, PyObject> callbacks = new HashMap<>();
        putCallback(callbacks, "onTick", options.get("onTick"));
        putCallback(callbacks, "onApply", options.get("onApply"));
        putCallback(callbacks, "onRemove", options.get("onRemove"));
        return callbacks;
    }

    private static void putCallback(Map<String, PyObject> callbacks, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof PyObject py) {
            callbacks.put(name, py);
            return;
        }
        throw new IllegalArgumentException("'" + name + "' must be a Python function, got: " + value);
    }

    private static MobEffectCategory resolveCategory(String name) {
        switch (name) {
        case "beneficial": return MobEffectCategory.BENEFICIAL;
        case "harmful": return MobEffectCategory.HARMFUL;
        case "neutral": return MobEffectCategory.NEUTRAL;
        default:
            throw new IllegalArgumentException("Unknown mob effect category: " + name);
        }
    }

    private static AttributeModifier.Operation resolveOperation(String name) {
        switch (name) {
        case "add":
        case "add_value": return AttributeModifier.Operation.ADD_VALUE;
        case "multiply_base":
        case "add_multiplied_base": return AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
        case "multiply_total":
        case "add_multiplied_total": return AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        default:
            throw new IllegalArgumentException("Unknown attribute operation: " + name);
        }
    }
}
