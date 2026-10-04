package com.AlerCello86767.jython_language_runtime.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.Params;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 自定义界面门面（client 侧）。
 *
 * <pre>
 * class RubyInfoScreen(object):
 *     def init(self, builder):
 *         builder.button("close", 8, 8, 60, 20, "message.jython_language_runtime.screen.close")
 *
 *     def draw(self, mouseX, mouseY, partialTick):
 *         d = UiDraw.begin()
 *         d.textCentered("message.jython_language_runtime.screen.title", 100, 8, "#FFFFFFFF")
 *         return d
 *
 *     def onButton(self, id):
 *         if id == "close":
 *             PyScreens.close()
 *
 * PyScreens.register("info", {
 *     "screen": RubyInfoScreen,      # 类，每次打开实例化一份
 *     "title": "message.jython_language_runtime.screen.title",
 * })
 *
 * PyScreens.open("info")             # 打开
 * </pre>
 *
 * <p>只能在客户端调用（碰 {@code Minecraft.setScreen}）。标题走翻译键——Python 侧不写中文。
 */
public final class PyScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyScreens");
    private static final Map<Identifier, Entry> REGISTRY = new LinkedHashMap<>();

    private PyScreens() {
    }

    private record Entry(PyObject screenClass, String titleKey) {
    }

    /**
     * 注册一个界面。
     *
     * <p>{@code options}：{@code screen}（必填，Python 类）、{@code title}（必填，翻译键）。
     */
    public static void register(String id, Map<String, Object> options) {
        Identifier ident = ModIds.parse(id);
        Object raw = options.get("screen");
        if (!(raw instanceof PyObject screenClass)) {
            throw new IllegalArgumentException("Screen '" + id + "' requires a 'screen' class");
        }
        String titleKey = Params.requireString(options.get("title"), "title");
        REGISTRY.put(ident, new Entry(screenClass, titleKey));
        LOGGER.info("Registered screen {} (title={})", ident, titleKey);
    }

    /** 打开已注册的界面：每次打开都实例化一个新的 Python 对象，并记住来源界面。 */
    public static void open(String id) {
        Identifier ident = ModIds.parse(id);
        Entry entry = REGISTRY.get(ident);
        if (entry == null) {
            throw new IllegalArgumentException("Screen not registered: " + ident);
        }
        Minecraft client = Minecraft.getInstance();
        PythonScreen screen = new PythonScreen(
                Component.translatable(entry.titleKey()), entry.screenClass().__call__());
        screen.setParent(client.screen);
        client.setScreen(screen);
    }

    /**
     * 关闭当前界面并回到打开它之前那个界面。
     *
     * <p>从标题界面 / 暂停菜单点进来的界面必须用这个——原版没有界面栈，
     * {@link #close()} 会 {@code setScreen(null)}，把玩家丢到「没有界面也没有世界」的状态。
     */
    public static void back() {
        Minecraft client = Minecraft.getInstance();
        Screen target = client.screen instanceof PythonScreen pythonScreen ? pythonScreen.parent() : null;
        client.setScreen(target);
    }

    /** 关闭当前界面（回到游戏，不保留来源界面）。 */
    public static void close() {
        Minecraft.getInstance().setScreen(null);
    }
}
