package com.AlerCello86767.jython_language_runtime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import org.python.core.PyObject;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

/**
 * Python 驱动的渲染层宿主（L4.3）。
 *
 * <p>26.1.2 的 {@code RenderLayer} 形状（已用 javap 核实）：
 *
 * <pre>
 * public abstract class RenderLayer&lt;S extends EntityRenderState, M extends EntityModel&lt;? super S&gt;&gt; {
 *     public RenderLayer(RenderLayerParent&lt;S, M&gt; parent);
 *     public M getParentModel();
 *     public abstract void submit(PoseStack, SubmitNodeCollector, int packedLight, S state,
 *                                 float limbSwing, float limbSwingAmount);
 * }
 * </pre>
 *
 * <p>它**没有** {@code shouldRender}，而且泛型参数 {@code M} 必须是一个真实的
 * {@link EntityModel}（要取 {@code getParentModel()}），因此只能挂在
 * {@code LivingEntityRenderer} 这类「有模型」的渲染器上——即本项目的 {@code humanoid} 路径。
 * 纯自定义几何（{@code PyEntityRenderer}）没有模型，层改为在 {@code submit} 里依序回调 Python，
 * 见 {@link PyEntityRenderer}。
 *
 * <p>Python 行为类（每个「层」一份实例）可实现的钩子：
 * <ul>
 *   <li>{@code shouldRender(state)} —— 返回 bool，决定这一层要不要画；未实现则默认画</li>
 *   <li>{@code tint(state)} —— 可选，返回 ARGB 颜色整数。返回后宿主会把父模型
 *       **用该颜色重绘一遍**（原版 {@code coloredCutoutModelCopyLayerRender}）——
 *       「受伤泛红」就是靠它：{@code shouldRender} 判断受伤、{@code tint} 给红色</li>
 *   <li>{@code render(state, poseStack, collector)} —— 可选，Python 用 {@link RenderHelper}
 *       自由绘制（例如发光层再画一遍本体）</li>
 * </ul>
 */
public class PythonRenderLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>>
        extends RenderLayer<S, M> {

    /** 每层行为对象一份句柄缓存：层是「每实体每帧」路径。 */
    private final PyHandles handles;
    /** 重绘父模型时用的默认贴图（继承自渲染器声明的 texture）。 */
    private final Identifier texture;

    public PythonRenderLayer(RenderLayerParent<S, M> parent, PyObject behavior, Identifier texture) {
        super(parent);
        this.texture = texture;
        this.handles = new PyHandles(behavior);
        this.handles.preload("shouldRender", "tint", "render");
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                       S state, float limbSwing, float limbSwingAmount) {
        Boolean visible = handles.forward("shouldRender", Boolean.class, state);
        if (visible != null && !visible) {
            return;
        }
        // tint 用 Long 承接：Python 侧常写 0xFFFF3333 这类大于 int 上界的字面量，
        // 先收成 long 再截断成 int，避免 Jython 转 Integer 时溢出。
        Long tint = handles.forward("tint", Long.class, state);
        if (tint != null) {
            coloredCutoutModelCopyLayerRender(getParentModel(), texture, poseStack, collector,
                    packedLight, state, tint.intValue(), 0);
        }
        handles.call("render", state, poseStack, collector);
    }
}
