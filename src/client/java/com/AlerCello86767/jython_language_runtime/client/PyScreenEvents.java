package com.AlerCello86767.jython_language_runtime.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.Params;
import com.AlerCello86767.jython_language_runtime.core.PyForwarder;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

/**
 * 原版界面门面（client 侧）：往标题界面 / 暂停菜单这类**已有界面**上加按钮。
 *
 * <pre>
 * PyScreenEvents.addButton("title", {
 *     "id": "about",
 *     "anchor": "bottom_right",     # 见下方锚点列表
 *     "x": 8, "y": 8,               # 相对锚点的内缩量
 *     "width": 100, "height": 20,
 *     "text": "message.jython_language_runtime.about.button",
 *     "onPress": _open_about,       # fn(client, screen)
 * })
 * </pre>
 *
 * <p><b>锚点</b>：{@code absolute}（x/y 直接当坐标）/ {@code top_left} / {@code top_right} /
 * {@code bottom_left} / {@code bottom_right} / {@code center}。除 {@code absolute} 外，
 * {@code x/y} 都是相对该锚点的内缩量——**窗口尺寸会变，所以优先用锚点**。
 *
 * <p>{@code screen} 可用简名 {@code title} / {@code pause} / {@code inventory} / {@code options}，
 * 或任意界面类的全限定名（如 {@code net.minecraft.client.gui.screens.ConnectScreen}）。
 *
 * <p><b>实现说明</b>：挂在 {@code ScreenEvents.AFTER_INIT} 上，每次界面 {@code init()} 都会重加一遍——
 * 这是对的，因为原版 {@code init()} 会先清空控件列表（窗口缩放也走这条路），所以不会叠加。
 * 控件通过 {@code Screens.getWidgets(screen)} 加入，Fabric 那个列表是对
 * {@code renderables/narratables/children} 的视图，加进去会真的渲染并响应点击。
 */
public final class PyScreenEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyScreenEvents");

    /** 一条「往某个界面加按钮」的声明。 */
    private record Rule(String id, String anchor, int dx, int dy, int width, int height,
                        String textKey, PyObject onPress) {
    }

    private static final Map<Class<?>, List<Rule>> RULES = new LinkedHashMap<>();
    private static boolean installed;

    private PyScreenEvents() {
    }

    /** 往指定界面加一个按钮；{@code options} 见类注释。只能在客户端初始化期调用。 */
    public static void addButton(String screen, Map<String, Object> options) {
        Class<?> type = screenClass(screen);
        Rule rule = new Rule(
                Params.requireString(options.get("id"), "id"),
                Params.asString(options.get("anchor"), "absolute"),
                Params.asInt(options.get("x"), 0),
                Params.asInt(options.get("y"), 0),
                Params.asInt(options.get("width"), 100),
                Params.asInt(options.get("height"), 20),
                Params.requireString(options.get("text"), "text"),
                callback(options.get("onPress")));
        RULES.computeIfAbsent(type, key -> new ArrayList<>()).add(rule);
        install();
        LOGGER.info("Registered screen button {} on {} (anchor={}, {}x{} at {},{}, text={})",
                rule.id(), type.getSimpleName(), rule.anchor(), rule.width(), rule.height(),
                rule.dx(), rule.dy(), rule.textKey());
    }

    /** 只在第一次注册时装一次总监听。 */
    private static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            for (Map.Entry<Class<?>, List<Rule>> entry : RULES.entrySet()) {
                if (!entry.getKey().isInstance(screen)) {
                    continue;
                }
                for (Rule rule : entry.getValue()) {
                    int[] pos = position(rule, width, height);
                    Button button = Button.builder(Component.translatable(rule.textKey()),
                                    pressed -> PyForwarder.invoke(rule.onPress(), client, screen))
                            .bounds(pos[0], pos[1], rule.width(), rule.height())
                            .build();
                    Screens.getWidgets(screen).add(button);
                    LOGGER.info("Added screen button {} to {} at ({},{}) on {}x{}",
                            rule.id(), screen.getClass().getSimpleName(),
                            pos[0], pos[1], width, height);
                }
            }
        });
    }

    /** 把「锚点 + 内缩」换算成绝对坐标；尺寸只在界面初始化时才拿得到，所以在这里算。 */
    private static int[] position(Rule rule, int screenWidth, int screenHeight) {
        int w = rule.width();
        int h = rule.height();
        return switch (rule.anchor()) {
            case "absolute", "top_left" -> new int[] {rule.dx(), rule.dy()};
            case "top_right" -> new int[] {screenWidth - w - rule.dx(), rule.dy()};
            case "bottom_left" -> new int[] {rule.dx(), screenHeight - h - rule.dy()};
            case "bottom_right" -> new int[] {screenWidth - w - rule.dx(), screenHeight - h - rule.dy()};
            case "center" -> new int[] {(screenWidth - w) / 2, (screenHeight - h) / 2 + rule.dy()};
            default -> throw new IllegalArgumentException("未知锚点: " + rule.anchor()
                    + "（可用 absolute/top_left/top_right/bottom_left/bottom_right/center）");
        };
    }

    private static PyObject callback(Object raw) {
        if (raw instanceof PyObject pyObject) {
            return pyObject;
        }
        throw new IllegalArgumentException("Missing or invalid 'onPress' callback: " + raw);
    }

    private static Class<?> screenClass(String screen) {
        return switch (screen) {
            case "title" -> TitleScreen.class;
            case "pause" -> PauseScreen.class;
            case "inventory" -> InventoryScreen.class;
            case "options" -> OptionsScreen.class;
            default -> {
                try {
                    yield Class.forName(screen);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("未知界面: " + screen
                            + "（可用 title/pause/inventory/options，或界面类全限定名）");
                }
            }
        };
    }
}
