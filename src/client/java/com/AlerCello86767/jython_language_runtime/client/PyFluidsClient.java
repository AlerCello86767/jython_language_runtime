package com.AlerCello86767.jython_language_runtime.client;

import static com.AlerCello86767.jython_language_runtime.core.Params.asBoolean;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.PyFluids;
import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.host.PythonFluid;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;

/**
 * 流体客户端门面：给已注册的流体挂「贴图 + 颜色滤镜 + 透明层」。
 *
 * <pre>
 * PyFluidsClient.registerModel("test_liquid", {
 *     "still": "py_test:liquid/liquid",     # 相对 textures/ 的贴图 id（静止）
 *     "flowing": "py_test:liquid/liquid",   # 缺省与 still 相同
 *     "overlay": "",                         # 可选：侧面叠加贴图（如水的 flow 覆盖层）
 *     "tint": [83, 171, 192],               # RGB 颜色滤镜；不给则不染色
 *     "alpha": 0.8,                          # 不透明度：0..1 比例，或 0..255 整数；默认 1.0
 *     "translucent": True,                   # 是否走半透明渲染层，默认 True
 * })
 * </pre>
 *
 * <p>26.1 的流体外观由 {@code FluidModel}（贴图 3 张 + 一个着色源）决定：
 * 白底贴图 + {@code tint} 就是「叠加颜色滤镜」的正规做法——颜色乘法发生在渲染期，
 * 所以同一张白图配不同 tint 可以出不同颜色的流体。
 *
 * <p><b>不透明度必须靠 {@code translucent}</b>：渲染层（{@code ChunkSectionLayer}）是在
 * {@code FluidModel.Unbaked.bake} 里由贴图透明度算出来的，只有走 TRANSLUCENT 层时
 * 顶点的 alpha 才会参与混合。{@code translucent} 为真即把贴图标记为
 * {@code Material.forceTranslucent}——白底不透明图 + tint 的 alpha 才会生效。
 *
 * <p>贴图必须进 **blocks 图集**：贴图放在 {@code textures/<自定义目录>/} 时，
 * 需要在 {@code assets/minecraft/atlases/blocks.json} 里加一条 directory 源
 * （注意命名空间是 {@code minecraft}：该文件会与原版的 blocks.json **合并**，
 * 放在自己 mod 的命名空间下不会被读取）；放在 {@code textures/block/} 则会被原版图集自动收录。
 *
 * <p><b>必须写在客户端入口</b>（{@code onInitializeClient}），且要在 {@code PyFluids.register} 之后。
 */
public final class PyFluidsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyFluidsClient");

    private PyFluidsClient() {
    }

    /** 注册流体的客户端外观。 */
    public static void registerModel(String id, Map<String, Object> options) {
        Identifier ident = ModIds.parse(id);
        PythonFluid still = PyFluids.stillFluid(id);
        PythonFluid flowing = PyFluids.flowingFluid(id);

        String stillSprite = asString(options.get("still"), "");
        if (stillSprite.isEmpty()) {
            throw new IllegalArgumentException("Fluid model '" + id + "' requires a 'still' texture id");
        }
        String flowingSprite = asString(options.get("flowing"), stillSprite);
        String overlaySprite = asString(options.get("overlay"), "");
        // 渲染层由 Material.forceTranslucent 决定（见 FluidModel.Unbaked.bake）：
        // 只有 marked 为 translucent 的贴图才会走 TRANSLUCENT 层，tint 的 alpha 才会参与混合
        boolean translucent = asBoolean(options.get("translucent"), true);
        Material stillMaterial = new Material(spriteOf(stillSprite), translucent);
        Material flowingMaterial = new Material(spriteOf(flowingSprite), translucent);
        Material overlayMaterial = overlaySprite.isEmpty()
                ? null
                : new Material(spriteOf(overlaySprite), translucent);

        Integer argb = tintOf(options);
        BlockTintSource tintSource = null;
        if (argb != null) {
            final int color = argb;
            tintSource = state -> color;
        }
        FluidModel.Unbaked model = new FluidModel.Unbaked(stillMaterial, flowingMaterial, overlayMaterial, tintSource);
        FluidRenderingRegistry.register(still, flowing, model);
        LOGGER.info("Registered fluid model {} (still={}, flowing={}, tint={}, translucent={})",
                ident, stillSprite, flowingSprite,
                argb == null ? "none" : String.format("#%08X", argb), translucent);
    }

    /** {@code [r, g, b]} + {@code alpha} → ARGB；没给 tint 返回 null（不染色）。 */
    private static Integer tintOf(Map<String, Object> options) {
        Object raw = options.get("tint");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list) || list.size() < 3) {
            throw new IllegalArgumentException("'tint' must be a [r, g, b] list, got: " + raw);
        }
        int red = channel(list.get(0), "r");
        int green = channel(list.get(1), "g");
        int blue = channel(list.get(2), "b");
        double alpha = asDouble(options.get("alpha"), 1.0);
        // alpha 允许两种写法：0..1 的比例，或 0..255 的整数
        int a = alpha > 1.0 ? clamp((int) Math.round(alpha)) : clamp((int) Math.round(alpha * 255.0));
        return (a << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int channel(Object value, String name) {
        int channel = value instanceof Number number ? number.intValue()
                : Integer.parseInt(String.valueOf(value));
        if (channel < 0 || channel > 255) {
            throw new IllegalArgumentException("tint channel " + name + " must be 0..255, got: " + channel);
        }
        return channel;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static Identifier spriteOf(String raw) {
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            throw new IllegalArgumentException("Invalid texture id: " + raw);
        }
        return id;
    }
}
