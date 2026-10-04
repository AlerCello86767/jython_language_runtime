package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.python.core.Py;
import org.python.core.PyObject;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * 方块战利品表 provider 桥接。
 *
 * <p>{@link #generate()} 里把 Python 回调拿到的 {@link PyLootGen}（{@code t}）交给脚本；
 * 回调结束后，把所有本模组但没显式生成战利品的方块排除出严格校验——避免只关心一两个方块时，
 * 整套 runDatagen 因「缺少战利品表」整体失败。
 */
public final class PyBlockLootProvider extends FabricBlockLootSubProvider {
    private final FabricPackOutput packOutput;
    private final PyObject callback;
    private final Set<Block> dropped = new HashSet<>();

    public PyBlockLootProvider(FabricPackOutput output,
                               CompletableFuture<HolderLookup.Provider> registries,
                               PyObject callback) {
        super(output, registries);
        this.packOutput = output;
        this.callback = callback;
    }

    @Override
    public void generate() {
        callback.__call__(Py.java2py(new PyLootGen(packOutput.getModId(), this, dropped)));
        for (Identifier blockId : BuiltInRegistries.BLOCK.keySet()) {
            if (!blockId.getNamespace().equals(packOutput.getModId())) {
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(blockId);
            if (!dropped.contains(block)) {
                excludeFromStrictValidation(block);
            }
        }
    }
}
