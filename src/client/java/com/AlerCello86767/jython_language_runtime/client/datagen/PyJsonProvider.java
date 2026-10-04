package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.concurrent.CompletableFuture;

import org.python.core.Py;
import org.python.core.PyObject;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/**
 * 通用 JSON provider 桥接：把 Python 回调拿到的 {@link PyJsonWriter} 写进指定目录。
 *
 * <p>这是 {@code PyDatagen.json(...)} 背后的 provider。它不解析任何数据语义，只负责
 * 「目录名 + id + Map → JSON 文件」，因此 L4.5 附魔 / L4.6 伤害类型这类运行时还未封装的
 * 数据可以直接落盘。
 */
public final class PyJsonProvider implements DataProvider {
    private final FabricPackOutput output;
    private final PackOutput.Target target;
    private final String directory;
    private final PyObject callback;

    public PyJsonProvider(FabricPackOutput output, String directory, PyObject callback) {
        this(output, PackOutput.Target.DATA_PACK, directory, callback);
    }

    public PyJsonProvider(FabricPackOutput output, PackOutput.Target target, String directory,
                          PyObject callback) {
        this.output = output;
        this.target = target;
        this.directory = directory;
        this.callback = callback;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cachedOutput) {
        PyJsonWriter writer = new PyJsonWriter(output, cachedOutput, target, directory);
        callback.__call__(Py.java2py(writer));
        return writer.finish();
    }

    @Override
    public String getName() {
        return "Py JSON (" + directory + ")";
    }
}
