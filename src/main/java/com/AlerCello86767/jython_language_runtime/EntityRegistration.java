package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asBoolean;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asFloat;
import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.asMap;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.PyHandles;
import com.AlerCello86767.jython_language_runtime.host.PythonEntity;

import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

/**
 * 实体注册门面（P9）。
 *
 * <p>Python 传「实体 id + 一个 Python 类 + 参数包」。Python 类是**行为宿主**：
 * 每个实体实例化一份（Java 侧调用 {@code entityClass.__call__()}），所以实例状态天然隔离。
 * 宿主可用方法见 {@link PythonEntity}。
 *
 * <p>客户端渲染器分开注册，见 client 源集的 {@code ClientEntityRenderers}（只能在
 * onInitializeClient 调用）。实体注册只在 mod init 窗口内合法。
 */
public final class EntityRegistration {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/EntityRegistration");

    private EntityRegistration() {
    }

    /**
     * 注册一个实体类型。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code category} (String)：misc/monster/creature/ambient/axolotls/water_creature/
     *       water_ambient/underground_water_creature，默认 misc</li>
     *   <li>{@code width} / {@code height} (float)：碰撞箱尺寸，默认 0.6 / 1.8</li>
     *   <li>{@code trackingRange} (int)：客户端追踪范围（区块），默认 5</li>
     *   <li>{@code fireImmune} (boolean)：是否免疫火焰，默认 false</li>
     *   <li>{@code attributes} (Map)：属性 id → 基础值。给了才会注册 LivingEntity 默认属性；
     *       不给则该实体没有属性表（原版对生物实体会报缺失警告）。
     *       <b>顺序：</b>若引用自定义属性（如 {@code "mymod:mana_regen"}），必须先用
     *       {@link PyAttributes#register} 注册该属性，否则这里会解析失败</li>
     * </ul>
     */
    public static void registerEntity(String path, PyObject entityClass, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        MobCategory category = resolveCategory(asString(options.get("category"), "misc"));
        float width = asFloat(options.get("width"), 0.6f);
        float height = asFloat(options.get("height"), 1.8f);
        int trackingRange = asInt(options.get("trackingRange"), 5);

        // H11：行为类一个钩子都没实现时，不必给每个实体创建 Python 对象
        boolean hasHooks = PyHandles.implementsAny(entityClass, "tick", "hurtServer", "interact",
                "getMainArm", "readAdditionalSaveData", "addAdditionalSaveData", "goals");
        EntityType.Builder<PythonEntity> builder = EntityType.Builder.of(
                (type, level) -> new PythonEntity(type, level, hasHooks ? entityClass.__call__() : null),
                category);
        builder.sized(width, height);
        builder.clientTrackingRange(trackingRange);
        if (asBoolean(options.get("fireImmune"), false)) {
            builder.fireImmune();
        }

        EntityType<PythonEntity> type = builder.build(ResourceKey.create(Registries.ENTITY_TYPE, id));
        Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type);

        int attributeCount = registerAttributes(type, asMap(options.get("attributes")));
        LOGGER.info("Registered entity {} (category={}, size={}x{}, trackingRange={}, attributes={})",
                id, category, width, height, trackingRange, attributeCount);
    }

    /** 按 id 取已注册的实体类型（供刷怪蛋与客户端渲染器使用）。 */
    public static EntityType<?> requireEntityType(String id) {
        Identifier key = ModIds.parse(id);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            throw new IllegalArgumentException("Unknown entity type: " + id + "（实体必须先注册，请检查注册顺序）");
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(key);
    }

    /** 注册 LivingEntity 默认属性，返回实际写入的属性条数。 */
    private static int registerAttributes(EntityType<PythonEntity> type, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return 0;
        }
        AttributeSupplier.Builder builder = LivingEntity.createLivingAttributes();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            Holder<Attribute> attribute;
            try {
                attribute = Registration.resolveAttribute(entry.getKey());
            } catch (IllegalArgumentException e) {
                // 自定义属性必须先于实体注册（进的是同一张 BuiltInRegistries.ATTRIBUTE）
                throw new IllegalArgumentException("Unknown attribute '" + entry.getKey()
                        + "' for entity " + type + "：自定义属性请先用 PyAttributes.register(...) 注册，"
                        + "再注册该实体", e);
            }
            builder.add(attribute, asDouble(entry.getValue(), 0.0d));
        }
        FabricDefaultAttributeRegistry.register(type, builder);
        return attributes.size();
    }

    private static MobCategory resolveCategory(String name) {
        switch (name) {
        case "misc": return MobCategory.MISC;
        case "monster": return MobCategory.MONSTER;
        case "creature": return MobCategory.CREATURE;
        case "ambient": return MobCategory.AMBIENT;
        case "axolotls": return MobCategory.AXOLOTLS;
        case "underground_water_creature": return MobCategory.UNDERGROUND_WATER_CREATURE;
        case "water_creature": return MobCategory.WATER_CREATURE;
        case "water_ambient": return MobCategory.WATER_AMBIENT;
        default:
            throw new IllegalArgumentException("Unknown mob category: " + name);
        }
    }
}
