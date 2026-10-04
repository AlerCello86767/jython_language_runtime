package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.concurrent.CompletableFuture;

import org.python.core.Py;
import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;

/**
 * 配方 provider 桥接。
 *
 * <p>26.1 的 {@link FabricRecipeProvider} 把配方生成拆成 {@code Runner} +
 * {@code RecipeProvider}：这里在 {@link #createRecipeProvider} 里造一个一次性
 * {@link RecipeProvider}，它的 {@code buildRecipes()} 就是 Python 回调的落点，
 * 拿到的是 {@link PyRecipeGen}（{@code gen}）。{@code gen.smelting} 走原版
 * {@link RecipeOutput}，{@code gen.custom} 走 {@link PyJsonWriter} 直写 JSON。
 */
public final class PyRecipeProvider extends FabricRecipeProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyDatagen");

    private final PyObject callback;
    private PyJsonWriter customWriter;

    public PyRecipeProvider(FabricPackOutput output,
                            CompletableFuture<HolderLookup.Provider> registries,
                            PyObject callback) {
        super(output, registries);
        this.callback = callback;
    }

    @Override
    protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        PyRecipeGen gen = new PyRecipeGen(this.output, output, customWriter);
        return new RecipeProvider(registries, output) {
            @Override
            public void buildRecipes() {
                callback.__call__(Py.java2py(gen));
            }
        };
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cachedOutput) {
        this.customWriter = new PyJsonWriter(this.output, cachedOutput, "recipe");
        LOGGER.info("Generating recipes for {}", output.getModId());
        CompletableFuture<?> recipes = super.run(cachedOutput);
        return CompletableFuture.allOf(recipes, customWriter.finish());
    }

    @Override
    public String getName() {
        return "Python Recipes";
    }
}
