package com.AlerCello86767.jython_language_runtime.host;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 通用配方宿主（L1.1）：承载「一个数据包 JSON 配方实例」。
 *
 * <p>26.1.2 的 {@code Recipe} 接口已用 javap 核实为：
 * <pre>{@code
 * boolean matches(T input, Level level);
 * ItemStack assemble(T input);
 * boolean showNotification();
 * String group();
 * RecipeSerializer<? extends Recipe<T>> getSerializer();
 * RecipeType<? extends Recipe<T>> getType();
 * PlacementInfo placementInfo();
 * RecipeBookCategory recipeBookCategory();
 * // 有默认实现：isSpecial() -> false、display() -> List.of()
 * }</pre>
 * 注意本版本 **没有** {@code getResultItem}，且 {@code assemble} 只返回单个 {@code ItemStack}——
 * 多产物 / 概率副产物无法通过原版接口表达，因此实际产出走 {@code PyRecipes.assemble} 在 Java 侧一次算完
 * （见该方法注释）。{@link #assemble} 只返回「第一个必出产物」，仅为满足接口契约。
 *
 * <p>{@code getSerializer()} 必须返回**注册进 RECIPE_SERIALIZER 的那个记录实例**（身份相等），
 * 因为 Fabric 的配方同步按 serializer 分组下发（{@code fabric_getRecipesBySyncedSerializer}）。
 */
public final class PythonRecipe implements Recipe<PythonRecipeInput> {
    /** 单个产物：物品栈 + 产出概率（1.0 表示必出）。 */
    public record Output(ItemStack stack, float chance) {
    }

    private final PythonRecipeSerializer owner;
    private final List<ItemStack> inputStacks;
    private final List<Output> outputs;
    private final Map<String, Object> fieldValues;

    public PythonRecipe(PythonRecipeSerializer owner, List<ItemStack> inputStacks,
                        List<Output> outputs, Map<String, Object> fieldValues) {
        this.owner = owner;
        this.inputStacks = List.copyOf(inputStacks);
        this.outputs = List.copyOf(outputs);
        this.fieldValues = Collections.unmodifiableMap(new LinkedHashMap<>(fieldValues));
    }

    /** 配方的具体输入物品栈（按槽位顺序）。 */
    public List<ItemStack> inputStacks() {
        return inputStacks;
    }

    /** 配方声明的产物列表（含概率）。 */
    public List<Output> outputs() {
        return outputs;
    }

    /** 附加字段值（energy / duration 等），键为声明时的字段名。 */
    public Map<String, Object> fieldValues() {
        return fieldValues;
    }

    /**
     * 按槽位形状匹配：逐个比较配方输入槽与传入槽位的物品，要求物品相同且数量足够。
     *
     * <p>规则：传入槽位可以比配方输入多（机器常有多余空槽），多出的忽略；配方的每个输入槽都必须匹配。
     * 无输入的配方只在「传入全空」时匹配。
     */
    @Override
    public boolean matches(PythonRecipeInput input, Level level) {
        if (inputStacks.isEmpty()) {
            return input.isEmpty();
        }
        if (input.size() < inputStacks.size()) {
            return false;
        }
        for (int i = 0; i < inputStacks.size(); i++) {
            ItemStack need = inputStacks.get(i);
            ItemStack have = input.getItem(i);
            if (have.isEmpty() || have.getItem() != need.getItem() || have.getCount() < need.getCount()) {
                return false;
            }
        }
        return true;
    }

    /** 原版接口只支持单产物：返回第一个必出产物（无必出产物时退回第一个）。 */
    @Override
    public ItemStack assemble(PythonRecipeInput input) {
        for (Output output : outputs) {
            if (output.chance() >= 1.0f) {
                return output.stack().copy();
            }
        }
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.get(0).stack().copy();
    }

    @Override
    public boolean showNotification() {
        return true;
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public RecipeSerializer<? extends Recipe<PythonRecipeInput>> getSerializer() {
        return owner.serializer();
    }

    @Override
    public RecipeType<? extends Recipe<PythonRecipeInput>> getType() {
        return owner.type();
    }

    /** 机器配方不参与原版合成台的摆放，直接标为不可摆放。 */
    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return owner.bookCategory();
    }
}
