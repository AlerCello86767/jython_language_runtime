package com.AlerCello86767.jython_language_runtime.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;

/**
 * 模型加载门面（{@code fabric-model-loading-api-v1}）。
 *
 * <p><b>先说边界：这个模块的主线是「用代码造模型」</b>——{@code ModelModifier} 的各个阶段都把
 * {@code UnbakedModel} / {@code BlockStateModel} 交给你改，而这些都是 Java 对象图，
 * Python 既不能继承也不能构造。所以「写一个程序化模型」「包一层模型」在 Python 侧做不到。
 *
 * <p>Python 能用的是其中一小块、但对本项目刚好有用：**按 id 注册「额外模型」并在运行期取回来**。
 * 额外模型不属于任何方块/物品定义，是给自定义渲染器（比如本项目 Python 驱动的实体渲染器）用的——
 * 声明一次，之后渲染时按名字取到烘焙好的 {@link BlockStateModel} 直接画。
 *
 * <pre>
 * # 客户端初始化期注册（声明式，只记名字和模型 id）
 * PyModels.registerBlockModel("ruby_golem_body", "jython_language_runtime:block/metallurgic_infuser")
 *
 * # 运行期（渲染回调里）取用；资源还没重载完时为 None
 * model = PyModels.blockModel("ruby_golem_body")
 * </pre>
 *
 * <p>模型 id 指向 {@code assets/<ns>/models/} 下的模型文件（{@code "jython_language_runtime:block/xxx"}）。
 * 注册只在客户端初始化期有效，晚于第一次资源重载就赶不上了。
 */
public final class PyModels {
    private PyModels() {
    }

    private static final Map<String, ExtraModelKey<BlockStateModel>> KEYS = new LinkedHashMap<>();
    private static final Map<ExtraModelKey<BlockStateModel>, Identifier> SOURCES = new LinkedHashMap<>();
    private static boolean pluginRegistered;

    /** 声明一个额外方块模型：把名字绑定到模型 id。只能在客户端初始化期调用。 */
    public static void registerBlockModel(String name, String modelId) {
        ensurePlugin();
        // ExtraModelKey 的 supplier 在资源加载时才会求值，命名空间必须此刻（注册期）捕获
        String namespace = ModIds.currentNamespace();
        ExtraModelKey<BlockStateModel> key = ExtraModelKey.create(() -> namespace + ":" + name);
        if (KEYS.putIfAbsent(name, key) != null) {
            throw new IllegalStateException("额外模型重复注册: " + name);
        }
        SOURCES.put(key, ModIds.parse(modelId));
    }

    /** 取已烘焙的额外模型；名字没注册过会抛错，资源还没重载完则返回 {@code null}。 */
    public static BlockStateModel blockModel(String name) {
        ExtraModelKey<BlockStateModel> key = KEYS.get(name);
        if (key == null) {
            throw new IllegalArgumentException("未注册的额外模型: " + name
                    + "（先用 PyModels.registerBlockModel 声明）");
        }
        Minecraft client = Minecraft.getInstance();
        var manager = client.getModelManager();
        if (manager == null) {
            return null;
        }
        return ((FabricModelManager) manager).getModel(key);
    }

    /** 插件只需注册一次，由它把上面攒下的声明真正交给模型加载器。 */
    private static void ensurePlugin() {
        if (pluginRegistered) {
            return;
        }
        pluginRegistered = true;
        ModelLoadingPlugin.register(context -> {
            for (Map.Entry<ExtraModelKey<BlockStateModel>, Identifier> entry : SOURCES.entrySet()) {
                context.addModel(entry.getKey(), SimpleUnbakedExtraModel.blockStateModel(entry.getValue()));
            }
        });
    }
}
