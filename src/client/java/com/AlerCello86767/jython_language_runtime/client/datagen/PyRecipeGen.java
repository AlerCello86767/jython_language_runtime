package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * Python 侧配方的 builder 门面（{@code onInitializeDataGenerator} 里的 {@code gen}）。
 *
 * <pre>
 * gen.smelting("mymod:ruby_dust", "mymod:ruby_ore", 0.7)
 * gen.custom("crushing", "mymod:crush_iron", {"inputs": [...], "outputs": [...]})
 * </pre>
 *
 * <p>{@code smelting} 走原版 {@code RecipeOutput#accept}（26.1 的形状，不再是旧的
 * {@code Consumer<FinishedRecipe>}）；{@code custom} 直接写一个自定义 type 的 JSON，
 * 供运行时 L1.1 的通用 {@code PythonRecipeSerializer} 读取。
 */
public final class PyRecipeGen {
    private final String namespace;
    private final RecipeOutput recipeOutput;
    private final PyJsonWriter customWriter;

    PyRecipeGen(FabricPackOutput output, RecipeOutput recipeOutput, PyJsonWriter customWriter) {
        this.namespace = output.getModId();
        this.recipeOutput = recipeOutput;
        this.customWriter = customWriter;
    }

    /** 原版熔炼：{@code gen.smelting("mymod:ruby_dust", "mymod:ruby_ore", 0.7)}。 */
    public void smelting(String resultId, String inputId, double experience) {
        Identifier result = DatagenIds.of(resultId, namespace);
        Identifier input = DatagenIds.of(inputId, namespace);
        Item resultItem = requireItem(result);
        Item inputItem = requireItem(input);
        // 配方 id 取「产物 + _from_smelting」，与原版命名约定一致
        Identifier id = Identifier.fromNamespaceAndPath(result.getNamespace(),
                result.getPath() + "_from_smelting");
        SmeltingRecipe recipe = new SmeltingRecipe(
                new Recipe.CommonInfo(true),
                new AbstractCookingRecipe.CookingBookInfo(CookingBookCategory.MISC, ""),
                Ingredient.of(inputItem),
                new ItemStackTemplate(resultItem),
                (float) experience,
                200);
        recipeOutput.accept(ResourceKey.create(Registries.RECIPE, id), recipe, null);
    }

    /**
     * 自定义 type 的配方：{@code gen.custom("crushing", "mymod:crush_iron", {...})}。
     *
     * <p>body 里的字段原样落盘，只额外补一个 {@code "type"}——没写命名空间时补成配方 id 的命名空间
     * （{@code "crushing"} → {@code "mymod:crushing"}）。
     */
    public void custom(String typeId, String recipeId, Map<String, Object> body) {
        Identifier id = DatagenIds.of(recipeId, namespace);
        String type = typeId.indexOf(':') >= 0
                ? DatagenIds.of(typeId, namespace).toString()
                : Identifier.fromNamespaceAndPath(id.getNamespace(), typeId).toString();
        Map<String, Object> merged = new LinkedHashMap<>(body);
        merged.put("type", type);
        customWriter.write(id.toString(), merged);
    }

    private static Item requireItem(Identifier id) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        return BuiltInRegistries.ITEM.getValue(id);
    }
}
