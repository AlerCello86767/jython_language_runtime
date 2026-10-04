package com.AlerCello86767.jython_language_runtime;

import java.lang.reflect.Field;
import java.util.Locale;

import net.fabricmc.fabric.api.dimension.v1.DimensionEvents;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * 维度门面（{@code fabric-dimensions-v1}）。
 *
 * <p>26.1.2 里自定义维度已经完全是数据包的事（{@code data/<ns>/dimension/}），运行时注册维度的接口没了，
 * 这个模块只剩一件事：**改已有维度的环境属性**——雾色、天空色、云高、日月角度、是否打雷烧怪等等。
 *
 * <pre>
 * def on_dimension(dimensionId, builder):        # dimensionId 形如 "minecraft:overworld"
 *     if dimensionId == "minecraft:the_nether":
 *         PyDimensions.setAttribute(builder, "sky_color", 0x00FF00)
 *         PyDimensions.setAttribute(builder, "cloud_height", 400.0)
 *
 * PyDimensions.onModifyAttributes(on_dimension)
 * </pre>
 *
 * <p>{@code attribute} 用 {@link EnvironmentAttributes} 里的字段名，大小写不敏感，例如
 * {@code fog_color} / {@code fog_start_distance} / {@code sky_color} / {@code cloud_height} /
 * {@code sun_angle} / {@code water_fog_color} / {@code monsters_burn} / {@code bed_rule}。
 * 值会按属性自身的类型收口（颜色是整数、距离/角度是浮点、开关是布尔）。
 */
public final class PyDimensions {
    private PyDimensions() {
    }

    /** {@code (dimensionId, builder) -> void}；{@code dimensionId} 取不到时为 null。 */
    public interface ModifyAttributesHandler {
        void handle(String dimensionId, EnvironmentAttributeMap.Builder builder);
    }

    /** 维度类型属性被汇总前回调，可往 builder 里写属性覆盖默认值。 */
    public static void onModifyAttributes(ModifyAttributesHandler handler) {
        DimensionEvents.MODIFY_ATTRIBUTES.register((dimensionType, builder, registries) -> handler.handle(
                dimensionType.unwrapKey().map(key -> key.identifier().toString()).orElse(null), builder));
    }

    /** 往构建器里写一条环境属性；名字非法会直接抛错。 */
    public static void setAttribute(EnvironmentAttributeMap.Builder builder, String attribute, Object value) {
        EnvironmentAttribute<?> resolved = attribute(attribute);
        set(builder, resolved, coerce(resolved, value));
    }

    @SuppressWarnings("unchecked")
    private static void set(EnvironmentAttributeMap.Builder builder, EnvironmentAttribute<?> attribute, Object value) {
        builder.set((EnvironmentAttribute<Object>) attribute, value);
    }

    /** 用反射按字段名取属性，省掉一张手工维护的对照表；字段不存在时给出可用示例。 */
    private static EnvironmentAttribute<?> attribute(String name) {
        try {
            Field field = EnvironmentAttributes.class.getField(name.toUpperCase(Locale.ROOT));
            return (EnvironmentAttribute<?>) field.get(null);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("未知环境属性: " + name
                    + "（用 EnvironmentAttributes 的字段名，如 sky_color / fog_color / cloud_height）");
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("无法读取环境属性: " + name, e);
        }
    }

    /** 按属性的默认值类型收口 Python 传来的值。 */
    private static Object coerce(EnvironmentAttribute<?> attribute, Object value) {
        Object sample = attribute.defaultValue();
        if (sample instanceof Integer) {
            return value instanceof Number n ? n.intValue() : Integer.decode(String.valueOf(value));
        }
        if (sample instanceof Float) {
            return value instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(value));
        }
        if (sample instanceof Double) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        if (sample instanceof Boolean) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        }
        // 其余类型（枚举、列表、粒子等）原样传下去，由游戏自己校验
        return value;
    }
}
