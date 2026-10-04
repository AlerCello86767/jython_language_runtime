package com.AlerCello86767.jython_language_runtime.client;

import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.PyMenus;
import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.host.PythonContainerMenu;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.Identifier;

/**
 * 容器界面绑定门面（client 侧）。
 *
 * <pre>
 * PyMenuScreens.bind("machine", {"screen": RubyMachineScreen})
 * </pre>
 *
 * <p>容器界面必须**双端各写一句**：{@code PyMenus.register} 在 main 入口（注册 MenuType），
 * 本方法在 client 入口（把 MenuType 绑到界面类）。只注册不绑定，打开时客户端会报找不到 screen。
 */
public final class PyMenuScreens {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyMenuScreens");

    private PyMenuScreens() {
    }

    /** 绑定界面到已注册的菜单类型。{@code options}：{@code screen}（必填，Python 类）。 */
    public static void bind(String id, Map<String, Object> options) {
        Identifier ident = ModIds.parse(id);
        Object raw = options.get("screen");
        if (!(raw instanceof PyObject screenClass)) {
            throw new IllegalArgumentException("Menu screen '" + id + "' requires a 'screen' class");
        }
        MenuScreens.<PythonContainerMenu, PythonMenuScreen>register(PyMenus.type(ident),
                (menu, inventory, title) -> new PythonMenuScreen(menu, inventory, title, screenClass.__call__()));
        LOGGER.info("Bound menu screen {}", ident);
    }
}
