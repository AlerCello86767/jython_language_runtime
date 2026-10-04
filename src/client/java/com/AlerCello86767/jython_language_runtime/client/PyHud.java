package com.AlerCello86767.jython_language_runtime.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.Params;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * HUD 叠加层门面（client 侧）。
 *
 * <p>Python 侧只需给「id + 锚点 + 一个 draw 函数」，draw 函数用 {@link UiDraw} 建造指令并返回：
 *
 * <pre>
 * def _draw():
 *     d = UiDraw.begin()
 *     d.fill(4, 4, 60, 10, "#222222")
 *     d.bar(4, 4, 60, 10, _ratio(), "#C0392B", "#222222")
 *     d.text("message.jython_language_runtime.energy", 6, 6, "#FFFFFF")
 *     return d
 *
 * PyHud.register("energy_meter", {
 *     "anchor": "after", "target": "minecraft:hotbar", "draw": _draw, "tick": True,
 * })
 * </pre>
 *
 * <p><b>频率模型</b>：{@code tick: True} 时 draw 每个客户端 tick 被调一次（20 TPS），
 * 没有该选项则只在注册时与 {@link #invalidate} 时调用。
 * 而每帧只回放已建好的指令列表，**不跨语言**——这就是「20 TPS 参与、60 FPS 渲染」。
 */
public final class PyHud {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyHud");
    private static final Map<Identifier, Entry> ENTRIES = new LinkedHashMap<>();

    private static boolean tickHookInstalled;

    private PyHud() {
    }

    /** 每个 HUD 元素：Python 的 draw 函数 + 上一次构建出的指令列表。 */
    private static final class Entry {
        private final Identifier id;
        private final PyObject draw;
        private final boolean ticking;
        private volatile UiDraw rendered;

        Entry(Identifier id, PyObject draw, boolean ticking) {
            this.id = id;
            this.draw = draw;
            this.ticking = ticking;
        }
    }

    /**
     * 注册一个 HUD 元素。
     *
     * <p>{@code options}：
     * <ul>
     *   <li>{@code draw} —— 必填，函数，返回 {@link UiDraw}（用 {@code UiDraw.begin()} 构造）</li>
     *   <li>{@code anchor} —— {@code first} / {@code last}（默认） / {@code before} / {@code after}</li>
     *   <li>{@code target} —— anchor 为 before/after 时的锚点 id，取值见 {@code VanillaHudElements}
     *       （如 {@code minecraft:hotbar}）</li>
     *   <li>{@code tick} —— 是否每客户端 tick 重建指令列表，默认 false</li>
     * </ul>
     */
    public static void register(String id, Map<String, Object> options) {
        Identifier ident = ModIds.parse(id);
        Object rawDraw = options.get("draw");
        if (!(rawDraw instanceof PyObject draw)) {
            throw new IllegalArgumentException("HUD '" + id + "' requires a 'draw' function");
        }
        boolean ticking = Params.asBoolean(options.get("tick"), false);

        Entry entry = new Entry(ident, draw, ticking);
        entry.rendered = build(ident, draw);
        ENTRIES.put(ident, entry);

        HudElement element = (graphics, deltaTracker) -> {
            UiDraw current = entry.rendered;
            if (current != null) {
                current.replay(graphics);
            }
        };

        String anchor = Params.asString(options.get("anchor"), "last");
        switch (anchor) {
        case "first" -> HudElementRegistry.addFirst(ident, element);
        case "last" -> HudElementRegistry.addLast(ident, element);
        case "before" -> HudElementRegistry.attachElementBefore(
                ModIds.parse(Params.requireString(options.get("target"), "target")), ident, element);
        case "after" -> HudElementRegistry.attachElementAfter(
                ModIds.parse(Params.requireString(options.get("target"), "target")), ident, element);
        default -> throw new IllegalArgumentException(
                "Unknown HUD anchor '" + anchor + "' (expected first/last/before/after)");
        }

        if (ticking) {
            ensureTickHook();
        }
        LOGGER.info("Registered HUD element {} (anchor={}, tick={}, ops={})",
                ident, anchor, ticking, entry.rendered.size());
    }

    /**
     * 重建指定 HUD 元素的指令列表（重新调用 Python 的 draw 函数）。
     *
     * <p>给 {@code tick: False} 的元素用来做「数据变化时才重建」。
     */
    public static void invalidate(String id) {
        Identifier ident = ModIds.parse(id);
        Entry entry = ENTRIES.get(ident);
        if (entry == null) {
            throw new IllegalArgumentException("HUD element not registered: " + ident);
        }
        entry.rendered = build(ident, entry.draw);
    }

    /** 首次出现 tick 型 HUD 时才挂客户端 tick 钩子，避免无谓的每 tick 开销。 */
    private static void ensureTickHook() {
        if (tickHookInstalled) {
            return;
        }
        tickHookInstalled = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (Entry entry : ENTRIES.values()) {
                if (entry.ticking) {
                    entry.rendered = build(entry.id, entry.draw);
                }
            }
        });
    }

    private static UiDraw build(Identifier id, PyObject draw) {
        PyObject result = draw.__call__();
        Object converted = result == null ? null : result.__tojava__(UiDraw.class);
        if (!(converted instanceof UiDraw ui)) {
            throw new IllegalArgumentException(
                    "HUD '" + id + "' draw must return UiDraw (build it with UiDraw.begin())");
        }
        return ui;
    }
}
