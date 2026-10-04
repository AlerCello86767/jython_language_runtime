package com.AlerCello86767.jython_language_runtime.client.datagen;

import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.Identifier;

/**
 * 伤害类型 datagen 门面：把声明式参数包翻译成 {@code data/<ns>/damage_type/<path>.json}。
 *
 * <p>26.1.2 实地核实（javap 字段 + 原版数据包 JSON 对照）后的**真实**字段：
 * <ul>
 *   <li>{@code message_id} (String，必填)：死亡消息 id，对应翻译键
 *       {@code death.attack.<message_id>}；缺省取注册 path</li>
 *   <li>{@code scaling} (String，必填)：{@code never} / {@code when_caused_by_living_non_player}
 *       / {@code always}，缺省 {@code when_caused_by_living_non_player}</li>
 *   <li>{@code exhaustion} (float，必填)：每点伤害造成的饥饿消耗，缺省 {@code 0.0}</li>
 *   <li>{@code effects} (String，可选)：{@code hurt} / {@code thorns} / {@code drowning}
 *       / {@code burning} / {@code poking} / {@code freezing}，缺省不写（等价 {@code hurt}）</li>
 *   <li>{@code death_message_type} (String，可选)：{@code default} / {@code fall_variants}
 *       / {@code intentional_game_design}，缺省不写（等价 {@code default}）</li>
 * </ul>
 *
 * <p>注意 JSON 里是 snake_case（{@code message_id} 等），Python 侧用 camelCase 参数名。
 *
 * <pre>{@code
 * PyDatagen.damageTypes(pack, lambda d: d.define("mymod:overheat", {
 *     "messageId": "overheat",
 *     "exhaustion": 0.1,
 *     "scaling": "when_caused_by_living_non_player",
 *     "effects": "burning",
 *     "deathMessageType": "default",
 * }))
 * }</pre>
 */
public final class PyDamageTypeGen {
    private final PyJsonWriter writer;
    private final String namespace;

    PyDamageTypeGen(PyJsonWriter writer, String namespace) {
        this.writer = writer;
        this.namespace = namespace;
    }

    /** 定义并写出一个伤害类型。{@code options} 为空时全部字段取默认值。 */
    public void define(String id, Map<String, Object> options) {
        Map<String, Object> opts = options == null ? Map.of() : options;
        Identifier key = DatagenIds.of(id, namespace);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("message_id", asString(opts.get("messageId"), key.getPath()));
        json.put("exhaustion", asDouble(opts.get("exhaustion"), 0.0d));
        json.put("scaling", resolveScaling(asString(opts.get("scaling"), "when_caused_by_living_non_player")));
        String effects = asString(opts.get("effects"), null);
        if (effects != null) {
            json.put("effects", resolveEffects(effects));
        }
        String deathMessageType = asString(opts.get("deathMessageType"), null);
        if (deathMessageType != null) {
            json.put("death_message_type", resolveDeathMessageType(deathMessageType));
        }
        writer.write(id, json);
    }

    private static String resolveScaling(String name) {
        switch (name) {
        case "never":
        case "when_caused_by_living_non_player":
        case "always":
            return name;
        default:
            throw new IllegalArgumentException("Unknown damage scaling: " + name
                    + " (expected never/when_caused_by_living_non_player/always)");
        }
    }

    private static String resolveEffects(String name) {
        switch (name) {
        case "hurt":
        case "thorns":
        case "drowning":
        case "burning":
        case "poking":
        case "freezing":
            return name;
        default:
            throw new IllegalArgumentException("Unknown damage effects: " + name
                    + " (expected hurt/thorns/drowning/burning/poking/freezing)");
        }
    }

    private static String resolveDeathMessageType(String name) {
        switch (name) {
        case "default":
        case "fall_variants":
        case "intentional_game_design":
            return name;
        default:
            throw new IllegalArgumentException("Unknown death message type: " + name
                    + " (expected default/fall_variants/intentional_game_design)");
        }
    }
}
