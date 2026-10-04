package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.concurrent.CompletableFuture;

import org.python.core.Py;
import org.python.core.PyObject;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;

/**
 * 附魔 provider 桥接：调用 Python 回调，回调收到 {@link PyEnchantmentGen}（{@code e}），
 * 由它把声明式参数包写成 {@code data/<ns>/enchantment/<path>.json}。
 *
 * <p>底层复用 {@link PyJsonWriter} 的落盘能力，门面只负责字段名、默认值与内置效果组件。
 */
public final class PyEnchantmentProvider implements DataProvider {
    private final FabricPackOutput output;
    private final PyObject callback;

    public PyEnchantmentProvider(FabricPackOutput output, PyObject callback) {
        this.output = output;
        this.callback = callback;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cachedOutput) {
        PyJsonWriter writer = new PyJsonWriter(output, cachedOutput, "enchantment");
        callback.__call__(Py.java2py(new PyEnchantmentGen(writer, output.getModId())));
        return writer.finish();
    }

    @Override
    public String getName() {
        return "Py Enchantments";
    }
}
