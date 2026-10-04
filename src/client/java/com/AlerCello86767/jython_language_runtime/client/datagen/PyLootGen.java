package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * Python 侧战利品表的 builder 门面（{@code PyDatagen.lootBlocks} 里的 {@code t}）。
 *
 * <p>目前提供最小可用集合：{@link #dropSelf(String)}（掉落自身）。后续条件/函数（L1.3）
 * 再往这里加。
 */
public final class PyLootGen {
    private final String namespace;
    private final BlockLootSubProvider provider;
    private final Set<Block> dropped;

    PyLootGen(String namespace, BlockLootSubProvider provider, Set<Block> dropped) {
        this.namespace = namespace;
        this.provider = provider;
        this.dropped = dropped;
    }

    /** 方块掉落自身：{@code t.dropSelf("mymod:machine_frame")}。 */
    public void dropSelf(String blockId) {
        Identifier id = DatagenIds.of(blockId, namespace);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("Unknown block: " + id);
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        provider.dropSelf(block);
        dropped.add(block);
    }
}
