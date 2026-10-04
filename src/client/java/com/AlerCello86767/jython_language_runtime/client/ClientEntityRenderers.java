package com.AlerCello86767.jython_language_runtime.client;

import org.python.core.PyObject;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;

import com.AlerCello86767.jython_language_runtime.EntityRegistration;
import com.AlerCello86767.jython_language_runtime.client.render.PyEntityRenderer;

/**
 * 客户端实体渲染器注册门面（P9）。
 *
 * <p>只能在客户端初始化阶段（Jython_language_runtimeClient.onInitializeClient）调用：
 * 渲染器注册进的是客户端渲染分发器，必须早于首个世界加载。
 *
 * <p>Python 传「实体 id + 一个渲染器类」，渲染器类的实例用于 {@code render} 回调，
 * 一个实体类型一份（不是每个实体一份）。
 */
public final class ClientEntityRenderers {
    private ClientEntityRenderers() {
    }

    /** 为实体 id 注册一个 Python 驱动的渲染器类。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void registerRenderer(String entityId, PyObject rendererClass) {
        EntityType type = EntityRegistration.requireEntityType(entityId);
        EntityRenderers.register(type, context -> new PyEntityRenderer(context, rendererClass.__call__()));
    }
}
