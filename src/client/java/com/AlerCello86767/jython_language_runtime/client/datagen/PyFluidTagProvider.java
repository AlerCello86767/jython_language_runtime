package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.tags.TagAppender;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

/**
 * 流体标签 provider 桥接：把 {@code PyDatagen.tags(pack, "fluid", "water", [ids])}
 * 写成 {@code data/<tag 命名空间>/tags/fluid/<path>.json}。
 */
public final class PyFluidTagProvider extends FabricTagsProvider.FluidTagsProvider {
    private final Identifier tagId;
    private final List<Identifier> values;

    public PyFluidTagProvider(FabricPackOutput output,
                              CompletableFuture<HolderLookup.Provider> registries,
                              Identifier tagId,
                              List<Identifier> values) {
        super(output, registries);
        this.tagId = tagId;
        this.values = values;
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        TagAppender<ResourceKey<Fluid>, Fluid> appender =
                builder(TagKey.create(Registries.FLUID, tagId));
        for (Identifier value : values) {
            appender.add(ResourceKey.create(Registries.FLUID, value));
        }
    }
}
