package com.AlerCello86767.jython_language_runtime.client;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyForwarder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * 客户端 tick 门面（client 侧）。
 *
 * <p>Python 侧只需丢一个函数，每个客户端 tick（20 TPS，客户端主线程）被调一次：
 *
 * <pre>
 * def _tick(client):
 *     pass
 *
 * PyClientEvents.onClientTick(_tick)
 * </pre>
 *
 * <p>注册必须在 client 入口初始化窗口完成；参数为 {@link Minecraft} 客户端实例。
 */
public final class PyClientEvents {
    private PyClientEvents() {
    }

    /** 注册客户端每 tick 回调（挂 END_CLIENT_TICK）。 */
    public static void onClientTick(PyObject callback) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> PyForwarder.invoke(callback, client));
    }
}
