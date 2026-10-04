package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asList;
import static com.AlerCello86767.jython_language_runtime.core.Params.asBoolean;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.requireString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * 自定义实体属性门面（L4.1）。
 *
 * <p>Python 侧只发一个 id + 参数包：
 *
 * <pre>{@code
 * PyAttributes.register("mana_regen", {"default": 1.0, "min": 0.0, "max": 1024.0, "syncable": True})
 * }</pre>
 *
 * <p>注册进的是 {@link BuiltInRegistries#ATTRIBUTE}，与「原版属性」同一张表，因此随后
 * {@link Registration#resolveAttribute(String)} 能查到它 —— 但是**注册顺序有要求**：
 * 自定义属性必须先注册，实体（{@link EntityRegistration#registerEntity}）才能在自己的
 * {@code attributes} 参数包里引用 {@code "mymod:mana_regen"}。
 *
 * <p>挂载有两种方式：
 * <ul>
 *   <li>参数包里给 {@code "entities": ["mymod:ruby_golem"]}——通过 Fabric 的
 *       {@link FabricDefaultAttributeRegistry#MODIFY} 事件，在属性表冻结（{@code BuiltInRegistries.freeze()}）
 *       时为这些实体补上该属性（基础值 = default）。该事件固定在 freeze 期触发，晚于全部
 *       onInitialize 注册，因此实体/属性谁先注册都可以。</li>
 *   <li>不给 {@code entities}，改在实体自己的 {@code attributes} 映射里写
 *       {@code "mymod:mana_regen": 4.0}（此时必须先注册属性）。</li>
 * </ul>
 *
 * <p>与其它门面一样，本方法只能在 mod init 窗口内调用。
 */
public final class PyAttributes {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyAttributes");

    private PyAttributes() {
    }

    /**
     * 注册一个自定义实体属性。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code default} (double)：基础值，默认 0.0</li>
     *   <li>{@code min} (double)：下限，默认 {@code -Double.MAX_VALUE}</li>
     *   <li>{@code max} (double)：上限，默认 {@code Double.MAX_VALUE}</li>
     *   <li>{@code syncable} (boolean)：是否向客户端同步，默认 false</li>
     *   <li>{@code entities} (List)：可选的实体 id 列表；给了就会把这些实体挂上该属性</li>
     * </ul>
     */
    public static void register(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        double defaultValue = asDouble(options.get("default"), 0.0d);
        double min = asDouble(options.get("min"), -Double.MAX_VALUE);
        double max = asDouble(options.get("max"), Double.MAX_VALUE);
        boolean syncable = asBoolean(options.get("syncable"), false);

        if (min > max) {
            throw new IllegalArgumentException(
                    "Attribute " + id + ": min (" + min + ") must not be greater than max (" + max + ")");
        }
        if (defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException(
                    "Attribute " + id + ": default (" + defaultValue + ") must be within [" + min + ", " + max + "]");
        }

        // 描述键沿用原版 attribute.name.* 体系，并带上命名空间避免跨模组撞键
        RangedAttribute attribute = new RangedAttribute(
                "attribute.name." + id.getNamespace() + "." + id.getPath(), defaultValue, min, max);
        if (syncable) {
            attribute.setSyncable(true);
        }
        Registry.register(BuiltInRegistries.ATTRIBUTE, id, attribute);
        LOGGER.info("Registered attribute {} (default={}, min={}, max={}, syncable={})",
                id, defaultValue, min, max, syncable);

        attachToEntities(id, attribute, defaultValue, options);
    }

    /** 把刚注册的属性挂到 {@code entities} 列表里的实体上（可选）。 */
    private static void attachToEntities(Identifier id, Attribute attribute, double defaultValue,
                                         Map<String, Object> options) {
        List<?> raw = asList(options.get("entities"));
        if (raw == null || raw.isEmpty()) {
            return;
        }
        List<Identifier> entityIds = new ArrayList<>();
        for (Object entry : raw) {
            entityIds.add(ModIds.parse(requireString(entry, "entities[]")));
        }

        // Holder 取自注册表本体，确保与实体属性表内部用的是同一个 Holder
        Holder<Attribute> holder = BuiltInRegistries.ATTRIBUTE.get(id)
                .orElseThrow(() -> new IllegalStateException("Attribute registration failed: " + id));

        // MODIFY 在 BuiltInRegistries.freeze() 期触发（晚于全部 onInitialize 注册），
        // 因此这里即使实体还没注册也安全 —— 到 freeze 时一定已经就绪。
        FabricDefaultAttributeRegistry.MODIFY.register(context -> {
            for (Identifier entityId : entityIds) {
                if (!BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) {
                    throw new IllegalArgumentException(
                            "Unknown entity type for attribute mounting: " + entityId
                            + "（请确认实体已注册）");
                }
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
                // 注意：26.1 的 EntityType#getBaseClass() 恒返回 Entity.class（javap 已核实），
                // 拿它做「是不是生物」的判断永远为假，因此这里不做类型校验；
                // 挂到非生物实体上会在运行时由原版属性系统报错。
                @SuppressWarnings("unchecked")
                EntityType<? extends LivingEntity> livingType = (EntityType<? extends LivingEntity>) type;
                context.modify(livingType, (entityType, builder) -> builder.add(holder, defaultValue));
            }
        });
        LOGGER.info("Attribute {} will be attached to entities {} (default={})", id, entityIds, defaultValue);
    }
}
