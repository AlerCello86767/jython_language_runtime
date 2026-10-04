package com.AlerCello86767.jython_language_runtime.client.datagen;

import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Python 侧模型的 builder 门面（{@code PyDatagen.blockModels} 里的 {@code m}）。
 *
 * <ul>
 *   <li>{@link #cubeAll(String)}：方块 cube_all 模型（六面同贴图 {@code block/<name>}），
 *       同时产出 blockstate；方块物品的 item 定义由 Fabric 自动补。</li>
 *   <li>{@link #item(String)}：普通物品模型，父级 {@code minecraft:item/generated}，
 *       并写出 {@code assets/<ns>/items/<name>.json} 定义。</li>
 * </ul>
 */
public final class PyBlockModelGen {
    private final String namespace;
    private final BlockModelGenerators blockGenerators;
    private final ItemModelGenerators itemGenerators;

    PyBlockModelGen(String namespace,
                    BlockModelGenerators blockGenerators,
                    ItemModelGenerators itemGenerators) {
        this.namespace = namespace;
        this.blockGenerators = blockGenerators;
        this.itemGenerators = itemGenerators;
    }

    /** 方块 cube_all：{@code m.cubeAll("mymod:machine_frame")}。 */
    public void cubeAll(String blockId) {
        Identifier id = DatagenIds.of(blockId, namespace);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("Unknown block: " + id);
        }
        blockGenerators.createTrivialCube(BuiltInRegistries.BLOCK.getValue(id));
    }

    /** 普通物品模型：{@code m.item("mymod:ruby")}。 */
    public void item(String itemId) {
        Identifier id = DatagenIds.of(itemId, namespace);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        itemGenerators.generateFlatItem(item, ModelTemplates.FLAT_ITEM);
    }
}
