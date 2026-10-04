package com.AlerCello86767.jython_language_runtime.client.render;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import org.python.core.Py;
import org.python.core.PyObject;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;

import com.AlerCello86767.jython_language_runtime.host.PythonEntity;

/**
 * {@code model: "humanoid"} 路径的渲染器宿主（L4.3）。
 *
 * <p>关键签名（已用 javap 核实）：
 *
 * <pre>
 * LivingEntityRenderer&lt;T extends LivingEntity, S extends LivingEntityRenderState,
 *                       M extends EntityModel&lt;? super S&gt;&gt;
 *     extends EntityRenderer&lt;T, S&gt; implements RenderLayerParent&lt;S, M&gt;
 *   protected final boolean addLayer(RenderLayer&lt;S, M&gt;);
 *   protected void scale(S, PoseStack);                  // 缩放钩子（基类 EntityRenderer 没有）
 *   public abstract Identifier getTextureLocation(S);    // 贴图钩子（基类 EntityRenderer 没有）
 * </pre>
 *
 * <p>人形模型直接由 {@link HumanoidModel#createMesh(CubeDeformation, float)} /
 * {@link LayerDefinition#bakeRoot()} 现场烘焙，**不注册 {@code ModelLayerLocation}**——
 * 省去客户端模型层注册时机（需早于资源重载）的坑，也让每个实体的几何独立、互不干扰。
 *
 * <p>缩放：26.1.2 的实体缩放分两处——{@code LivingEntityRenderState.scale}（由实体
 * {@code getScale()} 填）已在基类 {@code submit} 里应用；本类的 {@code scale(S, PoseStack)}
 * 再叠乘用户声明的 {@code scale} 倍数。
 */
public class PyLivingEntityRenderer extends LivingEntityRenderer<PythonEntity, HumanoidRenderState,
        HumanoidModel<HumanoidRenderState>> {

    /** 可选的自由绘制回调；为 null 表示只用模型 + 层。 */
    private final PyObject render;
    private final float extraScale;
    private final Identifier texture;

    public PyLivingEntityRenderer(EntityRendererProvider.Context context, PyObject behavior,
                                  Identifier texture, float scale, List<PyObject> layerBehaviors) {
        super(context, buildModel(), 0.5f);
        this.texture = texture;
        this.extraScale = scale;
        this.render = behavior == null ? null : behavior.__findattr__("render");
        for (PyObject layerBehavior : layerBehaviors) {
            // 每个层一份 Python 实例 + 一份 RenderLayer 宿主
            addLayer(new PythonRenderLayer<>(this, layerBehavior, texture));
        }
    }

    private static HumanoidModel<HumanoidRenderState> buildModel() {
        return new HumanoidModel<>(LayerDefinition
                .create(HumanoidModel.createMesh(CubeDeformation.NONE, 0.0f), 64, 64)
                .bakeRoot());
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        // 恒非 null：注册时已兜底（见 ClientEntityRenderers 的 FALLBACK_TEXTURE）
        return texture;
    }

    @Override
    protected void scale(HumanoidRenderState state, PoseStack poseStack) {
        poseStack.scale(extraScale, extraScale, extraScale);
    }

    @Override
    public void submit(HumanoidRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (render != null) {
            poseStack.pushPose();
            poseStack.scale(extraScale, extraScale, extraScale);
            render.__call__(Py.javas2pys(state, poseStack, collector, camera));
            poseStack.popPose();
        }
    }
}
