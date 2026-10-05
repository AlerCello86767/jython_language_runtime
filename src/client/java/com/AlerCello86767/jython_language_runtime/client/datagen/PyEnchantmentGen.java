package com.AlerCello86767.jython_language_runtime.client.datagen;

import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.asList;
import static com.AlerCello86767.jython_language_runtime.core.Params.asMap;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.Identifier;

/**
 * 附魔 datagen 门面：把声明式参数包翻译成 {@code data/<ns>/enchantment/<path>.json}。
 *
 * <p>26.1 附魔是**纯数据驱动**的（没有 Java 注册），因此这里只做「字段名 + 默认值 + 内置效果组件」
 * 的翻译，不接触任何原版 Builder/Codec。26.1.2 实地核实（javap {@code Enchantment} 记录字段 +
 * 原版数据包 JSON 对照）后的**真实**顶层字段：
 * <ul>
 *   <li>{@code description}（组件对象，必填）：{@code {"translate": ...}} 或 {@code {"text": ...}}；
 *       缺省用 {@code {"translate": "enchantment.<ns>.<path>"}}</li>
 *   <li>{@code supported_items}（物品/标签 id，必填）：{@code "#minecraft:enchantable/mining"} 或
 *       {@code "minecraft:diamond_pickaxe"}</li>
 *   <li>{@code primary_items}（可选）：同上形态</li>
 *   <li>{@code weight}（int，必填）：附魔台权重，缺省 10</li>
 *   <li>{@code max_level}（int，必填）：最大等级，缺省 1</li>
 *   <li>{@code min_cost} / {@code max_cost}（必填）：{@code {"base": n, "per_level_above_first": m}}
 *       或直接给整数（等价 m=0）；缺省 {@code {1,10}} / {@code {21,10}}</li>
 *   <li>{@code anvil_cost}（int，必填）：铁砧惩罚，缺省 4</li>
 *   <li>{@code slots}（EquipmentSlotGroup 名列表，必填）：any/mainhand/offhand/hand/feet/legs/chest/
 *       head/armor/body/saddle；缺省 {@code ["any"]}</li>
 *   <li>{@code exclusive_set}（可选）：互斥附魔标签 id（如 {@code "#minecraft:exclusive_set/damage"}）</li>
 *   <li>{@code effects}（可选）：效果组件表，键是组件 id（可省 {@code minecraft:} 前缀），值是原始 JSON 列表</li>
 * </ul>
 *
 * <p><b>内置效果组件</b>（与 {@code effects} 合并，后写覆盖同名键）：
 * <ul>
 *   <li>{@code damage}：数字或 {@code {base, perLevel}} → {@code minecraft:damage} 的
 *       {@code minecraft:add} 值效果（参考原版 sharpness）</li>
 *   <li>{@code postAttack}：{@code {affected, enchanted, effect, chance|requirements}} →
 *       {@code minecraft:post_attack}（参考原版 thorns）。{@code chance} 收数字或
 *       {@code {base, perLevel}}，会自动包成 {@code minecraft:enchantment_level} 值效果；
 *       要换成别的值效果就直接给带 {@code type} 的映射</li>
 *   <li>{@code attributes}：{@code [{attribute, amount, id, operation, slot?}]} →
 *       {@code minecraft:attributes}（参考原版 depth_strider）</li>
 *   <li>{@code projectileSpawned}：{@code {effect, requirements?}} 或它的列表 →
 *       {@code minecraft:projectile_spawned}（参考原版 flame）</li>
 * </ul>
 *
 * <p>值效果里的「随等级增长」统一写成 {@code {"type": "minecraft:linear", "base": b,
 * "per_level_above_first": p}}（原版 {@code LevelBasedValue} 的 linear 形态）。
 *
 * <pre>{@code
 * PyDatagen.enchantments(pack, lambda e: e.define("mymod:smelting_touch", {
 *     "maxLevel": 1,
 *     "items": "#minecraft:enchantable/mining",
 *     "weight": 2,
 *     "slots": ["mainhand"],
 *     "description": {"translate": "enchantment.mymod.smelting_touch"},
 *     "damage": {"base": 1.0, "perLevel": 0.5},
 * }))
 * }</pre>
 */
public final class PyEnchantmentGen {
    private final PyJsonWriter writer;
    private final String namespace;

    PyEnchantmentGen(PyJsonWriter writer, String namespace) {
        this.writer = writer;
        this.namespace = namespace;
    }

    /** 定义并写出一个附魔；除 items / supportedItems 外全部字段都有默认值。 */
    public void define(String id, Map<String, Object> options) {
        Map<String, Object> opts = options == null ? Map.of() : options;
        Identifier key = DatagenIds.of(id, namespace);

        String items = asString(opts.get("items"), null);
        if (items == null) {
            items = asString(opts.get("supportedItems"), null);
        }
        if (items == null) {
            throw new IllegalArgumentException("Enchantment '" + key + "' requires 'items'"
                    + " (supported item or tag id, e.g. '#minecraft:enchantable/mining')");
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("description", buildDescription(opts.get("description"), key));
        json.put("supported_items", items);
        String primaryItems = asString(opts.get("primaryItems"), null);
        if (primaryItems != null) {
            json.put("primary_items", primaryItems);
        }
        json.put("weight", asInt(opts.get("weight"), 10));
        json.put("max_level", asInt(opts.get("maxLevel"), 1));
        json.put("min_cost", buildCost(opts.get("minCost"), 1, 10));
        json.put("max_cost", buildCost(opts.get("maxCost"), 21, 10));
        json.put("anvil_cost", asInt(opts.get("anvilCost"), 4));
        json.put("slots", buildSlots(opts.get("slots")));
        String exclusiveSet = asString(opts.get("exclusiveSet"), null);
        if (exclusiveSet != null) {
            json.put("exclusive_set", exclusiveSet);
        }
        Map<String, Object> effects = buildEffects(opts);
        if (!effects.isEmpty()) {
            json.put("effects", effects);
        }
        writer.write(id, json);
    }

    // ---------- 顶层字段 ----------

    /** description：Map 原样当组件；String 当翻译键；缺省用 {@code enchantment.<ns>.<path>}。 */
    private static Map<String, Object> buildDescription(Object value, Identifier key) {
        Map<String, Object> component = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                component.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return component;
        }
        String translate = asString(value, null);
        component.put("translate", translate != null
                ? translate
                : "enchantment." + key.getNamespace() + "." + key.getPath());
        return component;
    }

    /** min_cost / max_cost：整数 → {base, 0}；Map → {base, perLevel}；缺省用传入的默认值。 */
    private static Map<String, Object> buildCost(Object value, int defaultBase, int defaultPerLevel) {
        Map<String, Object> cost = new LinkedHashMap<>();
        if (value instanceof Number number) {
            cost.put("base", number.intValue());
            cost.put("per_level_above_first", 0);
            return cost;
        }
        Map<String, Object> map = asMap(value);
        if (map == null) {
            cost.put("base", defaultBase);
            cost.put("per_level_above_first", defaultPerLevel);
            return cost;
        }
        cost.put("base", asInt(map.get("base"), defaultBase));
        cost.put("per_level_above_first",
                asInt(map.get("perLevel"), asInt(map.get("per_level_above_first"), 0)));
        return cost;
    }

    private static List<Object> buildSlots(Object value) {
        List<Object> slots = new ArrayList<>();
        if (value == null) {
            slots.add("any");
            return slots;
        }
        if (value instanceof String single) {
            slots.add(resolveSlot(single));
            return slots;
        }
        List<?> list = asList(value);
        if (list == null) {
            throw new IllegalArgumentException("'slots' must be a string or a list of strings, got: " + value);
        }
        for (Object raw : list) {
            if (!(raw instanceof String name)) {
                throw new IllegalArgumentException("slots entries must be strings, got: " + raw);
            }
            slots.add(resolveSlot(name));
        }
        if (slots.isEmpty()) {
            slots.add("any");
        }
        return slots;
    }

    // ---------- 效果组件 ----------

    private static Map<String, Object> buildEffects(Map<String, Object> opts) {
        Map<String, Object> effects = new LinkedHashMap<>();

        // 原始透传：组件 id 可省 minecraft: 前缀
        Map<String, Object> raw = asMap(opts.get("effects"));
        if (raw != null) {
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                effects.put(componentId(entry.getKey()), entry.getValue());
            }
        }

        if (opts.containsKey("damage")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("effect", valueEffect(opts.get("damage")));
            effects.put("minecraft:damage", List.of(entry));
        }
        if (opts.containsKey("postAttack")) {
            effects.put("minecraft:post_attack", List.of(buildPostAttack(asMap(opts.get("postAttack")))));
        }
        if (opts.containsKey("attributes")) {
            effects.put("minecraft:attributes", buildAttributes(asList(opts.get("attributes"))));
        }
        if (opts.containsKey("projectileSpawned")) {
            effects.put("minecraft:projectile_spawned", buildConditionalEffects(opts.get("projectileSpawned")));
        }
        return effects;
    }

    /** {@code minecraft:add} 值效果：{@code {"effect": {"type": "minecraft:add", "value": <levelBased>}}}。 */
    private static Map<String, Object> valueEffect(Object amount) {
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("type", "minecraft:add");
        effect.put("value", levelBased(amount));
        return effect;
    }

    /** 数字 / {@code {base, perLevel}} → {@code {"type": "minecraft:linear", base, per_level_above_first}}。 */
    private static Map<String, Object> levelBased(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "minecraft:linear");
        if (value instanceof Number number) {
            out.put("base", number.doubleValue());
            out.put("per_level_above_first", 0.0d);
            return out;
        }
        Map<String, Object> map = asMap(value);
        if (map == null) {
            // 缺省（None）按 0 处理
            out.put("base", 0.0d);
            out.put("per_level_above_first", 0.0d);
            return out;
        }
        out.put("base", asDouble(map.get("base"), 0.0d));
        out.put("per_level_above_first",
                asDouble(map.get("perLevel"), asDouble(map.get("per_level_above_first"), 0.0d)));
        return out;
    }

    /** {@code minecraft:post_attack} 的一条 TargetedConditionalEffect（参考原版 thorns）。 */
    private static Map<String, Object> buildPostAttack(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("'postAttack' must be a map");
        }
        Map<String, Object> effect = asMap(map.get("effect"));
        if (effect == null) {
            throw new IllegalArgumentException("'postAttack' requires 'effect' (an entity effect JSON map,"
                    + " e.g. {'type': 'minecraft:damage_entity', 'damage_type': 'minecraft:thorns',"
                    + " 'min_damage': 1.0, 'max_damage': 5.0})");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("affected", resolveTarget(asString(map.get("affected"), "attacker")));
        entry.put("enchanted", resolveTarget(asString(map.get("enchanted"), "victim")));
        entry.put("effect", effect);
        Object requirements = map.get("requirements");
        if (requirements instanceof Map) {
            entry.put("requirements", requirements);
        } else if (map.containsKey("chance")) {
            Map<String, Object> built = new LinkedHashMap<>();
            built.put("chance", chanceEffect(map.get("chance")));
            built.put("condition", "minecraft:random_chance");
            entry.put("requirements", built);
        }
        return entry;
    }

    /**
     * {@code requirements.chance} → **幸运值效果**（EnchantmentValueEffect），不是裸的
     * {@code LevelBasedValue}。
     *
     * <p>原版 thorns 的写法是 {@code {"type": "minecraft:enchantment_level",
     * "amount": {"type": "minecraft:linear", "base": 0.15, "per_level_above_first": 0.15}}}：
     * 直接写 {@code {"type": "minecraft:linear"}} 会被判为未知的效果类型而整份附魔解析失败。
     *
     * <p>数字 / {@code {base, perLevel}} 自动套上 {@code minecraft:enchantment_level}；
     * 已经是带 {@code type} 的映射则原样透传（想用别的值效果时）。
     */
    private static Map<String, Object> chanceEffect(Object value) {
        Map<String, Object> map = asMap(value);
        if (map != null && map.containsKey("type")) {
            return map;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "minecraft:enchantment_level");
        out.put("amount", levelBased(value));
        return out;
    }

    /** {@code minecraft:attributes} 的 EnchantmentAttributeEffect 列表（参考原版 depth_strider）。 */
    private static List<Object> buildAttributes(List<?> list) {
        if (list == null) {
            throw new IllegalArgumentException("'attributes' must be a list");
        }
        List<Object> out = new ArrayList<>();
        int index = 0;
        for (Object raw : list) {
            Map<String, Object> map = asMap(raw);
            if (map == null) {
                throw new IllegalArgumentException("attributes[" + index + "] must be a map");
            }
            String attribute = asString(map.get("attribute"), null);
            if (attribute == null) {
                throw new IllegalArgumentException("attributes[" + index + "] requires 'attribute'");
            }
            String modifierId = asString(map.get("id"), null);
            if (modifierId == null) {
                throw new IllegalArgumentException("attributes[" + index + "] requires 'id' (a modifier id,"
                        + " e.g. 'mymod:enchantment.temper')");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("amount", levelBased(map.get("amount")));
            entry.put("attribute", attribute);
            entry.put("id", modifierId);
            entry.put("operation", resolveOperation(asString(map.get("operation"), "add_value")));
            String slot = asString(map.get("slot"), null);
            if (slot != null) {
                entry.put("slot", resolveSlot(slot));
            }
            out.add(entry);
            index++;
        }
        return out;
    }

    /** {@code minecraft:projectile_spawned}：单个 {effect, requirements?} 或它的列表。 */
    private static List<Object> buildConditionalEffects(Object value) {
        List<?> entries = value instanceof List ? asList(value) : List.of(value);
        List<Object> out = new ArrayList<>();
        int index = 0;
        for (Object raw : entries) {
            Map<String, Object> map = asMap(raw);
            if (map == null) {
                throw new IllegalArgumentException("projectileSpawned[" + index + "] must be a map");
            }
            Map<String, Object> effect = asMap(map.get("effect"));
            if (effect == null) {
                throw new IllegalArgumentException("projectileSpawned[" + index + "] requires 'effect'");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("effect", effect);
            Object requirements = map.get("requirements");
            if (requirements instanceof Map) {
                entry.put("requirements", requirements);
            }
            out.add(entry);
            index++;
        }
        return out;
    }

    // ---------- 枚举校验 ----------

    private static String componentId(String key) {
        return key.indexOf(':') < 0 ? "minecraft:" + key : key;
    }

    private static String resolveSlot(String name) {
        switch (name) {
        case "any":
        case "mainhand":
        case "offhand":
        case "hand":
        case "feet":
        case "legs":
        case "chest":
        case "head":
        case "armor":
        case "body":
        case "saddle":
            return name;
        default:
            throw new IllegalArgumentException("Unknown equipment slot group: " + name);
        }
    }

    private static String resolveTarget(String name) {
        switch (name) {
        case "attacker":
        case "victim":
        case "damaging_entity":
            return name;
        default:
            throw new IllegalArgumentException("Unknown enchantment target: " + name
                    + " (expected attacker/victim/damaging_entity)");
        }
    }

    private static String resolveOperation(String name) {
        switch (name) {
        case "add_value":
        case "add_multiplied_base":
        case "add_multiplied_total":
            return name;
        default:
            throw new IllegalArgumentException("Unknown attribute operation: " + name
                    + " (expected add_value/add_multiplied_base/add_multiplied_total)");
        }
    }
}
