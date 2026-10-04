package com.AlerCello86767.jython_language_runtime.client.render;

import java.util.List;
import java.util.stream.Collectors;

import com.mojang.blaze3d.vertex.PoseStack;

import org.python.core.Py;
import org.python.core.PyObject;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;
import com.AlerCello86767.jython_language_runtime.host.PythonEntity;

/**
 * Python 驱动的「自定义几何」实体渲染器（P9 / L4.3）。
 *
 * <p>26.1.2 的渲染管线是「抽取状态 + 提交」两段式：
 * {@code extractRenderState(entity, state, partialTick)} 填好 {@link EntityRenderState}
 * （含 x/y/z/ageInTicks/lightCoords 等），随后 {@code submit(...)} 提交几何体。
 * 这里把 {@code submit} 转发给 Python 的 {@code render(state, poseStack, collector, camera)}，
 * 纹理与几何体由 Python 用 {@link RenderHelper} 决定。
 *
 * <p><b>基类没有缩放/贴图钩子</b>（javap 核实：{@code EntityRenderer} 既无 {@code scale} 也无
 * {@code getTextureLocation}，二者只存在于 {@code LivingEntityRenderer}）。因此本类：
 * <ul>
 *   <li>{@code scale} 直接在 {@code submit} 里对 {@link PoseStack} 做 {@code poseStack.scale}
 *       包住 Python 回调与各层</li>
 *   <li>默认贴图仅存起来供 Python 查询（{@link #texture()}），实际用哪张由 Python 经
 *       {@link RenderHelper} 决定</li>
 *   <li>{@code layers} 用「{@code submit} 内依序回调」的等价方案（没有 {@code EntityModel}
 *       就无法构造真正的 {@code RenderLayer}）：每个层回调 {@code shouldRender(state)} 与
 *       {@code render(state, poseStack, collector)}</li>
 * </ul>
 */
public class PyEntityRenderer extends EntityRenderer<PythonEntity, EntityRenderState> {
    /** H4：渲染句柄在构造期解析一次——{@code submit} 是「每实体每帧」路径，不能再做 __findattr__。 */
    private final PyObject render;
    private final float scale;
    private final Identifier texture;
    /** 每个层一份句柄缓存，按声明顺序依序回调。 */
    private final List<PyHandles> layers;

    /** 向后兼容的构造器：自定义几何、无缩放、无默认贴图、无层。 */
    public PyEntityRenderer(EntityRendererProvider.Context context, PyObject behavior) {
        this(context, behavior, 1.0f, null, List.of());
    }

    public PyEntityRenderer(EntityRendererProvider.Context context, PyObject behavior,
                            float scale, Identifier texture, List<PyObject> layerBehaviors) {
        super(context);
        this.scale = scale;
        this.texture = texture;
        this.render = behavior == null ? null : behavior.__findattr__("render");
        this.layers = layerBehaviors.stream().map(PyHandles::new).collect(Collectors.toList());
        this.layers.forEach(handles -> handles.preload("shouldRender", "render"));
    }

    /** 供 Python 查询的默认贴图（无则 null）。 */
    public Identifier texture() {
        return texture;
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }

    @Override
    public void submit(EntityRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        boolean scaling = scale != 1.0f;
        if (scaling) {
            poseStack.pushPose();
            poseStack.scale(scale, scale, scale);
        }
        if (render != null) {
            render.__call__(Py.javas2pys(state, poseStack, collector, camera));
        }
        for (PyHandles layer : layers) {
            Boolean visible = layer.forward("shouldRender", Boolean.class, state);
            if (visible != null && !visible) {
                continue;
            }
            layer.call("render", state, poseStack, collector);
        }
        if (scaling) {
            poseStack.popPose();
        }
    }
}
