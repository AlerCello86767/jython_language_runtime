package com.AlerCello86767.jython_language_runtime.net;

import java.util.LinkedHashMap;
import java.util.Map;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 通用 payload（P11）：频道由 {@link CustomPacketPayload.Type} 决定，数据体是一个扁平 CompoundTag。
 *
 * <p>支持的值类型：Boolean / Integer / Long / Float / Double / String。
 * 注意 NBT 里 boolean 存成 byte，所以读回时 byte 一律按 boolean 解释——不要往里放 byte/short。
 */
public final class PyPayload implements CustomPacketPayload {
    private final CustomPacketPayload.Type<PyPayload> type;
    private final CompoundTag data;

    public PyPayload(CustomPacketPayload.Type<PyPayload> type, CompoundTag data) {
        this.type = type;
        this.data = data;
    }

    @Override
    public CustomPacketPayload.Type<PyPayload> type() {
        return type;
    }

    public CompoundTag data() {
        return data;
    }

    /** 频道的编解码器。频道 id 由原版协议单独承载，codec 只处理数据体。 */
    public static StreamCodec<RegistryFriendlyByteBuf, PyPayload> codecFor(
            CustomPacketPayload.Type<PyPayload> type) {
        return StreamCodec.<RegistryFriendlyByteBuf, PyPayload>ofMember(
                (payload, buf) -> buf.writeNbt(payload.data()),
                buf -> {
                    CompoundTag tag = buf.readNbt();
                    return new PyPayload(type, tag == null ? new CompoundTag() : tag);
                });
    }

    /** Python 字典 → CompoundTag。 */
    public static CompoundTag toTag(Map<String, Object> data) {
        CompoundTag tag = new CompoundTag();
        if (data == null) {
            return tag;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean b) {
                tag.putBoolean(key, b);
            } else if (value instanceof Integer i) {
                tag.putInt(key, i);
            } else if (value instanceof Long l) {
                tag.putLong(key, l);
            } else if (value instanceof Float f) {
                tag.putFloat(key, f);
            } else if (value instanceof Double d) {
                tag.putDouble(key, d);
            } else if (value instanceof String s) {
                tag.putString(key, s);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported payload value for '" + key + "': " + value);
            }
        }
        return tag;
    }

    /** CompoundTag → Java Map。Python 侧用 {@code data.get("k")} 读取。 */
    public static Map<String, Object> toMap(CompoundTag tag) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, Tag> entry : tag.entrySet()) {
            Tag value = entry.getValue();
            Object converted = switch (value.getId()) {
            case Tag.TAG_BYTE -> value.asBoolean().orElse(false);
            case Tag.TAG_INT -> value.asInt().orElse(0);
            case Tag.TAG_LONG -> value.asLong().orElse(0L);
            case Tag.TAG_FLOAT -> value.asFloat().orElse(0f);
            case Tag.TAG_DOUBLE -> value.asDouble().orElse(0d);
            case Tag.TAG_STRING -> value.asString().orElse("");
            default -> null;
            };
            if (converted != null) {
                data.put(entry.getKey(), converted);
            }
        }
        return data;
    }
}
