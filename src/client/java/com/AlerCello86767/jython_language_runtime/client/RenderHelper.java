package com.AlerCello86767.jython_language_runtime.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

/**
 * 供 Python 渲染回调使用的绘制原语（P9）。
 *
 * <p>渲染决策在 Python（画什么、画几个、怎么变换），顶点细节在 Java。典型用法：
 *
 * <pre>
 * def render(self, state, poseStack, collector, camera):
 *     type = RenderHelper.entityCutout("minecraft:textures/entity/zombie/zombie.png")
 *     poseStack.pushPose()
 *     RenderHelper.box(poseStack, collector, type, RenderHelper.FULL_LIGHT,
 *                      -0.5, 0.0, -0.5, 0.5, 1.0, 0.5)
 *     poseStack.popPose()
 * </pre>
 */
public final class RenderHelper {
    /** 全亮光照值（{@code 0xF000F0}），做无光照演示时用。 */
    public static final int FULL_LIGHT = 15728880;

    // ---------- H4：渲染类型缓存 ----------
    // 渲染回调是「每实体每帧」路径：原先每次都要 ModIds.parse（正则校验 + 新建 Identifier）
    // 再取 RenderType。这里按纹理 id 缓存，解析结果与 RenderType 都只算一次。
    // RenderType 只持有渲染管线与纹理 id、绘制时才解析纹理，可长期持有——
    // 原版各 EntityRenderer 同样是把 RenderType 存进字段长期复用。
    private static final Map<String, Identifier> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<String, RenderType> CUTOUT = new ConcurrentHashMap<>();
    private static final Map<String, RenderType> TRANSLUCENT = new ConcurrentHashMap<>();
    private static final Map<String, RenderType> SOLID = new ConcurrentHashMap<>();

    private RenderHelper() {
    }

    /** 纹理 id → 实体镂空渲染类型（不剔除背面，适合手写几何体）。 */
    public static RenderType entityCutout(String textureId) {
        return CUTOUT.computeIfAbsent(textureId, id -> RenderTypes.entityCutout(texture(id)));
    }

    /** 纹理 id → 实体半透明渲染类型（用于发光/透明部位）。 */
    public static RenderType entityTranslucent(String textureId) {
        return TRANSLUCENT.computeIfAbsent(textureId, id -> RenderTypes.entityTranslucent(texture(id)));
    }

    /** 纹理 id → 实体实心渲染类型。 */
    public static RenderType entitySolid(String textureId) {
        return SOLID.computeIfAbsent(textureId, id -> RenderTypes.entitySolid(texture(id)));
    }

    /** 纹理 id 只解析一次；必须是全限定 id（运行期回调没有命名空间上下文）。 */
    private static Identifier texture(String textureId) {
        return TEXTURES.computeIfAbsent(textureId, ModIds::parse);
    }

    /** 向收集器提交一个带纹理的立方体。 */
    public static void box(PoseStack poseStack, SubmitNodeCollector collector, RenderType type,
                           int packedLight,
                           float x0, float y0, float z0, float x1, float y1, float z1) {
        collector.submitCustomGeometry(poseStack, type,
                (pose, consumer) -> cube(pose, consumer, packedLight, x0, y0, z0, x1, y1, z1));
    }

    /**
     * 直接画一个六面纹理立方体（白色、给定光照）。
     *
     * <p>可在 Python 里作为 {@code submitCustomGeometry} 的 lambda 体使用：
     * {@code collector.submitCustomGeometry(poseStack, type,
     * lambda pose, c: RenderHelper.cube(pose, c, light, ...))}
     */
    public static void cube(PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        quad(pose, consumer, packedLight, 0, -1, 0, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
        quad(pose, consumer, packedLight, 0, 1, 0, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        quad(pose, consumer, packedLight, 0, 0, -1, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0);
        quad(pose, consumer, packedLight, 0, 0, 1, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        quad(pose, consumer, packedLight, -1, 0, 0, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        quad(pose, consumer, packedLight, 1, 0, 0, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
    }

    /** 画一个四边形，四个角按顺序给出，法线由调用方给。 */
    public static void quad(PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
                            float nx, float ny, float nz,
                            float ax, float ay, float az,
                            float bx, float by, float bz,
                            float cx, float cy, float cz,
                            float dx, float dy, float dz) {
        vertex(pose, consumer, packedLight, nx, ny, nz, ax, ay, az, 0f, 1f);
        vertex(pose, consumer, packedLight, nx, ny, nz, bx, by, bz, 1f, 1f);
        vertex(pose, consumer, packedLight, nx, ny, nz, cx, cy, cz, 1f, 0f);
        vertex(pose, consumer, packedLight, nx, ny, nz, dx, dy, dz, 0f, 0f);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
                               float nx, float ny, float nz,
                               float x, float y, float z, float u, float v) {
        consumer.addVertex(pose, x, y, z)
                .setColor(1.0f, 1.0f, 1.0f, 1.0f)
                .setUv(u, v)
                .setLight(packedLight)
                .setNormal(pose, nx, ny, nz);
    }
}
