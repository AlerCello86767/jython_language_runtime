package com.AlerCello86767.jython_language_runtime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.python.core.Py;
import org.python.core.PyObject;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import com.AlerCello86767.jython_language_runtime.host.PythonEntity;

/**
 * Python 驱动的实体渲染器（P9）。
 *
 * <p>26.1.2 的渲染管线是「抽取状态 + 提交」两段式：
 * {@code extractRenderState(entity, state, partialTick)} 填好 {@link EntityRenderState}
 * （含 x/y/z/ageInTicks/lightCoords 等），随后 {@code submit(...)} 提交几何体。
 * 这里把 {@code submit} 转发给 Python 的 {@code render(state, poseStack, collector, camera)}，
 * 纹理与几何体由 Python 用 {@link RenderHelper} 决定。
 */
public class PyEntityRenderer extends EntityRenderer<PythonEntity, EntityRenderState> {
    /** H4：渲染句柄在构造期解析一次——{@code submit} 是「每实体每帧」路径，不能再做 __findattr__。 */
    private final PyObject render;

    public PyEntityRenderer(EntityRendererProvider.Context context, PyObject behavior) {
        super(context);
        this.render = behavior.__findattr__("render");
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    @Override
    public void submit(EntityRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (render != null) {
            render.__call__(Py.javas2pys(state, poseStack, collector, camera));
        }
    }
}
