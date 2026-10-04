package com.AlerCello86767.jython_language_runtime.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import com.AlerCello86767.jython_language_runtime.EntityRegistration;
import com.AlerCello86767.jython_language_runtime.client.render.PyEntityRenderer;
import com.AlerCello86767.jython_language_runtime.client.render.PyLivingEntityRenderer;
import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.Params;

/**
 * 客户端实体渲染器注册门面（P9 / L4.3）。
 *
 * <p>只能在客户端初始化阶段（{@code Jython_language_runtimeClient.onInitializeClient}）调用：
 * 渲染器注册进的是客户端渲染分发器，必须早于首个世界加载。
 *
 * <p>两条路径：
 * <ul>
 *   <li><b>向后兼容</b>：{@code registerRenderer(entityId, rendererClass)} —— 传一个 Python
 *       渲染器类，走「纯自定义几何」宿主 {@link PyEntityRenderer}。</li>
 *   <li><b>声明式参数包</b>：{@code registerRenderer(entityId, options)} —— 第二参是
 *       Python dict（Jython 转成 {@link Map}）或模型名简写字符串，字段见
 *       {@link #registerRenderer(String, Map)}。</li>
 * </ul>
 *
 * <pre>
 * ClientEntityRenderers.registerRenderer("ruby_golem", {
 *     "model": "humanoid",                      # 或 "custom"（默认，纯自定义几何）
 *     "texture": "mymod:textures/entity/ruby_golem.png",
 *     "layers": [GlowLayer],                    # RenderLayer 宿主，Python 决定贴图/条件
 *     "scale": 1.4,
 * })
 * </pre>
 */
public final class ClientEntityRenderers {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/ClientEntityRenderers");

    /** 实体 id → 默认贴图 id，供 Python 在渲染回调里查询（{@link #defaultTexture}）。 */
    private static final Map<String, String> DEFAULT_TEXTURES = new ConcurrentHashMap<>();

    /** humanoid 未给贴图时的兜底贴图：{@code getTextureLocation} 不允许返回 null。 */
    private static final Identifier FALLBACK_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/zombie/zombie.png");

    private ClientEntityRenderers() {
    }

    /**
     * 为实体 id 注册一个 Python 类驱动的自定义几何渲染器（向后兼容重载）。
     *
     * <p>Jython 有时会把 dict / str 也分派到本重载（{@code PyDictionary} 既是 {@link Map}
     * 也是 {@code PyObject}），这里兜底转交给对应的重载，保证两种分派结果行为一致。
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void registerRenderer(String entityId, PyObject rendererClass) {
        if (rendererClass instanceof Map) {
            registerRenderer(entityId, (Map<String, Object>) rendererClass);
            return;
        }
        if (rendererClass instanceof CharSequence) {
            registerRenderer(entityId, rendererClass.toString());
            return;
        }
        EntityType type = EntityRegistration.requireEntityType(entityId);
        EntityRenderers.register(type, context -> new PyEntityRenderer(context, rendererClass.__call__()));
        LOGGER.info("Registered entity renderer for {} (custom geometry, python class)", entityId);
    }

    /**
     * 用「声明式参数包」注册实体渲染器。
     *
     * <p>{@code options} 字段（全部可选）：
     * <ul>
     *   <li>{@code model} —— {@code "custom"}（默认）或 {@code "humanoid"}。
     *       选 {@code "humanoid"} 时走 {@link PyLivingEntityRenderer}
     *       （{@code HumanoidModel} + 自定义贴图 + 真 {@code RenderLayer}）</li>
     *   <li>{@code renderer} —— Python 渲染器类（可选）：提供自由绘制回调
     *       {@code render(state, poseStack, collector, camera)}；humanoid 下为可选补充</li>
     *   <li>{@code texture} —— 默认贴图 id（如 {@code "mymod:textures/entity/ruby_golem.png"}）。
     *       humanoid 下会成为 {@code getTextureLocation} 的返回值；custom 下仅存起来供
     *       Python 经 {@link #defaultTexture} / {@code PyEntityRenderer.texture()} 查询</li>
     *   <li>{@code scale} —— 渲染缩放倍数（float，默认 1.0）</li>
     *   <li>{@code layers} —— Python 层类列表。humanoid 下每个类一份真 {@code RenderLayer}
     *       实例（可 {@code shouldRender}/{@code tint}/{@code render}）；custom 下在
     *       {@code submit} 内依序回调（{@code shouldRender}/{@code render}）</li>
     * </ul>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void registerRenderer(String entityId, Map<String, Object> options) {
        EntityType type = EntityRegistration.requireEntityType(entityId);
        String model = Params.asString(options.get("model"), "custom");
        String textureId = Params.asString(options.get("texture"), null);
        float scale = Params.asFloat(options.get("scale"), 1.0f);
        Object rendererOption = options.get("renderer");
        List<PyObject> layers = collectLayers(options.get("layers"));

        if (textureId != null) {
            DEFAULT_TEXTURES.put(entityId, textureId);
        }

        if ("humanoid".equals(model)) {
            Identifier texture = textureId != null ? ModIds.parse(textureId) : FALLBACK_TEXTURE;
            if (textureId == null) {
                LOGGER.warn("humanoid renderer for {} has no 'texture'; falling back to {}",
                        entityId, FALLBACK_TEXTURE);
            }
            EntityRenderers.register(type, context -> new PyLivingEntityRenderer(
                    context, asBehavior(rendererOption), texture, scale, layers));
            LOGGER.info("Registered humanoid entity renderer for {} (texture={}, scale={}, layers={})",
                    entityId, texture, scale, layers.size());
            return;
        }

        PyObject behavior = asBehavior(rendererOption);
        if (behavior == null && layers.isEmpty()) {
            LOGGER.warn("custom renderer for {} has neither 'renderer' nor 'layers'; nothing will draw",
                    entityId);
        }
        EntityRenderers.register(type, context -> new PyEntityRenderer(
                context, behavior, scale,
                textureId != null ? ModIds.parse(textureId) : null, layers));
        LOGGER.info("Registered entity renderer for {} (custom geometry, texture={}, scale={}, layers={})",
                entityId, textureId, scale, layers.size());
    }

    /** 模型名简写：{@code registerRenderer("ruby_golem", "humanoid")} 等价于只给 model 字段。 */
    public static void registerRenderer(String entityId, String model) {
        registerRenderer(entityId, Map.of("model", model));
    }

    /** 查询某实体注册时声明的默认贴图 id；没声明或未注册返回 {@code null}。 */
    public static String defaultTexture(String entityId) {
        return DEFAULT_TEXTURES.get(entityId);
    }

    /** {@code "renderer"} 字段是个 Python 类，取它的实例（每个实体类型一份）。 */
    private static PyObject asBehavior(Object rendererOption) {
        return rendererOption instanceof PyObject pyObject ? pyObject.__call__() : null;
    }

    /** 把 Python 层类列表实例化：每个「层接口」一份 Python 实例。 */
    private static List<PyObject> collectLayers(Object rawLayers) {
        List<PyObject> layers = new ArrayList<>();
        List<?> declared = Params.asList(rawLayers);
        if (declared == null) {
            return layers;
        }
        for (Object item : declared) {
            if (item instanceof PyObject pyObject) {
                layers.add(pyObject.__call__());
            } else {
                throw new IllegalArgumentException("每一层必须是 Python 类: " + item);
            }
        }
        return layers;
    }
}
