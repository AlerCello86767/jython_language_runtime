package com.AlerCello86767.jython_language_runtime.client;

import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.AlerCello86767.jython_language_runtime.PyNetworking;
import com.AlerCello86767.jython_language_runtime.core.PyForwarder;
import com.AlerCello86767.jython_language_runtime.net.PyPayload;

/**
 * 网络通信门面（P11）——客户端侧。
 *
 * <p>只能在客户端入口（onInitializeClient）调用：这里碰的是客户端网络类。
 * 频道本身（payload 类型）已在公共侧的 {@code PyNetworking.declareS2C} / {@code onC2S} 里声明过，
 * 因为类型必须在双端注册。
 *
 * <p>与公共侧同理，Fabric 的客户端 global receiver 已在 render thread 回调，无需额外调度。
 */
public final class ClientNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/ClientNetworking");

    private ClientNetworking() {
    }

    /** 挂上 S2C 频道的客户端接收函数 {@code handler(player, data)}。 */
    public static void onS2C(String channel, PyObject handler) {
        CustomPacketPayload.Type<PyPayload> type = PyNetworking.requireS2C(channel);
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                PyForwarder.invoke(handler, context.player(), PyPayload.toMap(payload.data())));
        LOGGER.info("Registered S2C receiver for {}", channel);
    }

    /** 客户端 → 服务端发送。 */
    public static void sendToServer(String channel, Map<String, Object> data) {
        ClientPlayNetworking.send(new PyPayload(PyNetworking.requireC2S(channel), PyPayload.toTag(data)));
    }
}
