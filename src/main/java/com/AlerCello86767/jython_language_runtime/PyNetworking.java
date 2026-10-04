package com.AlerCello86767.jython_language_runtime;

import java.util.HashMap;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.PyForwarder;
import com.AlerCello86767.jython_language_runtime.net.PyPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络通信门面（P11）——公共侧。
 *
 * <p>Python 传「频道名 + 一个函数」，数据体是扁平字典（bool/int/long/float/double/String）。
 *
 * <p><b>线程：不需要额外调度。</b>Fabric 的 global receiver 本身就在主线程回调——
 * 服务端在 server thread、客户端在 render thread，因此门面直接把调用转给 Python 即可。
 *
 * <p><b>频道必须在双端都注册 payload 类型</b>，所以声明要放在主入口（onInitialize）里：
 * C2S 用 {@link #onC2S}（顺带挂服务端接收函数），S2C 用 {@link #declareS2C}；
 * 客户端的接收函数与发送在 client 侧门面（ClientNetworking）里做。
 */
public final class PyNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyNetworking");

    private static final Map<Identifier, CustomPacketPayload.Type<PyPayload>> C2S_TYPES = new HashMap<>();
    private static final Map<Identifier, CustomPacketPayload.Type<PyPayload>> S2C_TYPES = new HashMap<>();
    private static final Map<Identifier, PyObject> C2S_HANDLERS = new HashMap<>();

    private PyNetworking() {
    }

    /** 声明一个 C2S 频道，并挂上服务端接收函数 {@code handler(player, data)}。 */
    public static void onC2S(String channel, PyObject handler) {
        Identifier id = ModIds.parse(channel);
        if (C2S_TYPES.containsKey(id)) {
            throw new IllegalArgumentException("C2S channel already declared: " + id);
        }
        CustomPacketPayload.Type<PyPayload> type = new CustomPacketPayload.Type<>(id);
        PayloadTypeRegistry.serverboundPlay().register(type, PyPayload.codecFor(type));
        C2S_TYPES.put(id, type);
        C2S_HANDLERS.put(id, handler);
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                PyForwarder.invoke(C2S_HANDLERS.get(id), context.player(), PyPayload.toMap(payload.data())));
        LOGGER.info("Declared C2S channel {}", id);
    }

    /** 声明一个 S2C 频道（只注册类型；客户端接收函数在 ClientNetworking 里挂）。 */
    public static void declareS2C(String channel) {
        Identifier id = ModIds.parse(channel);
        if (S2C_TYPES.containsKey(id)) {
            throw new IllegalArgumentException("S2C channel already declared: " + id);
        }
        CustomPacketPayload.Type<PyPayload> type = new CustomPacketPayload.Type<>(id);
        PayloadTypeRegistry.clientboundPlay().register(type, PyPayload.codecFor(type));
        S2C_TYPES.put(id, type);
        LOGGER.info("Declared S2C channel {}", id);
    }

    /** 取已声明的 S2C 频道类型（发送端与客户端接收端共用）。 */
    public static CustomPacketPayload.Type<PyPayload> requireS2C(String channel) {
        CustomPacketPayload.Type<PyPayload> type = S2C_TYPES.get(ModIds.parse(channel));
        if (type == null) {
            throw new IllegalArgumentException("S2C channel not declared: " + channel);
        }
        return type;
    }

    /** 取已声明的 C2S 频道类型（客户端发送用）。 */
    public static CustomPacketPayload.Type<PyPayload> requireC2S(String channel) {
        CustomPacketPayload.Type<PyPayload> type = C2S_TYPES.get(ModIds.parse(channel));
        if (type == null) {
            throw new IllegalArgumentException("C2S channel not declared: " + channel);
        }
        return type;
    }

    /** 服务端 → 指定玩家。 */
    public static void sendToPlayer(ServerPlayer player, String channel, Map<String, Object> data) {
        ServerPlayNetworking.send(player, new PyPayload(requireS2C(channel), PyPayload.toTag(data)));
    }
}
