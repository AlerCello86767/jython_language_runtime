package com.AlerCello86767.jython_language_runtime.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.PyForwarder;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * 按键绑定门面（client 侧；对应 Fabric 的 fabric-key-mapping 模块，旧名 key-binding）。
 *
 * <p>Python 侧先注册按键（给默认 GLFW 键码），再挂按下回调：
 *
 * <pre>
 * from org.lwjgl.glfw import GLFW
 *
 * PyKeyMappings.register("ruby_action", GLFW.GLFW_KEY_TAB)
 *
 * def _on_press(client):
 *     pass
 *
 * PyKeyMappings.onPress("ruby_action", _on_press)
 * </pre>
 *
 * <p><b>触发模型</b>：注册与回调都在 client 初始化窗口声明；实际触发在 END_CLIENT_TICK
 * 里 {@code consumeClick()} 轮询（一次按键一次回调，不连发），回调在客户端主线程，
 * 参数为 {@link net.minecraft.client.Minecraft}。
 *
 * <p>按键名走翻译键 {@code key.jython_language_runtime.<path>}，需要在 lang 文件给词条。
 */
public final class PyKeyMappings {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyKeyMappings");

    private record Binding(KeyMapping mapping, List<PyObject> pressCallbacks) {
    }

    private static final Map<String, Binding> BINDINGS = new LinkedHashMap<>();
    private static boolean pollHookInstalled;

    private PyKeyMappings() {
    }

    /** 注册一个按键绑定，默认分类 GAMEPLAY。 */
    public static void register(String path, int defaultKey) {
        register(path, defaultKey, "gameplay");
    }

    /**
     * 注册一个按键绑定。
     *
     * @param path        按键短名（[a-z0-9_]+），翻译键为 key.jython_language_runtime.&lt;path&gt;
     * @param defaultKey  默认 GLFW 键码（如 GLFW.GLFW_KEY_TAB）
     * @param category    按键分类：movement/misc/multiplayer/gameplay（默认）/
     *                    inventory/creative/spectator/debug
     */
    public static void register(String path, int defaultKey, String category) {
        // 翻译键按当前命名空间走：key.<modid>.<path>
        String namespace = ModIds.of(path).getNamespace();
        KeyMapping mapping = new KeyMapping(
                "key." + namespace + "." + path,
                InputConstants.Type.KEYSYM,
                defaultKey,
                categoryFromString(path, category));
        KeyMappingHelper.registerKeyMapping(mapping);
        BINDINGS.put(path, new Binding(mapping, new ArrayList<>()));
        ensurePollHook();
        LOGGER.info("Registered key mapping key.{}.{} (default GLFW code {}, category={})",
                namespace, path, defaultKey, category);
    }

    /** 为已注册的按键挂「按下」回调；同一按键可挂多个。 */
    public static void onPress(String path, PyObject callback) {
        Binding binding = BINDINGS.get(path);
        if (binding == null) {
            throw new IllegalArgumentException("Key mapping not registered: " + path
                    + " (call PyKeyMappings.register first)");
        }
        binding.pressCallbacks().add(callback);
    }

    /** 首个按键注册时才挂一次轮询 tick。 */
    private static void ensurePollHook() {
        if (pollHookInstalled) {
            return;
        }
        pollHookInstalled = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (Binding binding : BINDINGS.values()) {
                while (binding.mapping().consumeClick()) {
                    for (PyObject callback : binding.pressCallbacks()) {
                        PyForwarder.invoke(callback, client);
                    }
                }
            }
        });
    }

    private static KeyMapping.Category categoryFromString(String path, String category) {
        return switch (category) {
            case "movement" -> KeyMapping.Category.MOVEMENT;
            case "misc" -> KeyMapping.Category.MISC;
            case "multiplayer" -> KeyMapping.Category.MULTIPLAYER;
            case "gameplay" -> KeyMapping.Category.GAMEPLAY;
            case "inventory" -> KeyMapping.Category.INVENTORY;
            case "creative" -> KeyMapping.Category.CREATIVE;
            case "spectator" -> KeyMapping.Category.SPECTATOR;
            case "debug" -> KeyMapping.Category.DEBUG;
            default -> throw new IllegalArgumentException(
                    "Unknown key category '" + category + "' for " + path);
        };
    }
}
