package com.AlerCello86767.jython_language_runtime.client.datagen;

import org.python.core.Py;
import org.python.core.PyObject;

import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;

/**
 * 模型 provider 桥接（client 源集）。
 *
 * <p>{@link FabricModelProvider} 把方块模型与物品模型拆成两个回调；这里只在
 * {@link #generateBlockStateModels} 里调用一次 Python 回调（用一个由
 * {@link BlockModelGenerators} 的公开输出字段合成的 {@link ItemModelGenerators}
 * 同时支撑 {@code m.item}），避免同一个回调被触发两次。{@link #generateItemModels}
 * 因此留空。
 */
public final class PyModelProvider extends FabricModelProvider {
    private final FabricPackOutput packOutput;
    private final PyObject callback;

    public PyModelProvider(FabricPackOutput output, PyObject callback) {
        super(output);
        this.packOutput = output;
        this.callback = callback;
    }

    @Override
    public void generateBlockStateModels(BlockModelGenerators blockModelGenerators) {
        ItemModelGenerators itemModelGenerators =
                new ItemModelGenerators(blockModelGenerators.itemModelOutput, blockModelGenerators.modelOutput);
        callback.__call__(Py.java2py(
                new PyBlockModelGen(packOutput.getModId(), blockModelGenerators, itemModelGenerators)));
    }

    @Override
    public void generateItemModels(ItemModelGenerators itemModelGenerators) {
        // 所有工作都在 generateBlockStateModels 里通过单次 Python 回调完成
    }
}
