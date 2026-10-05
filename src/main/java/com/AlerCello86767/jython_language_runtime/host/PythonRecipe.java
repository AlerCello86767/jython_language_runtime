package com.AlerCello86767.jython_language_runtime.host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 通用配方宿主（L1.1）：承载「一个数据包 JSON 配方实例」。
 *
 * <p><b>为什么存 {@link ItemStackTemplate} 而不是 {@link ItemStack}：</b>配方 JSON 是在
 * `ReloadableServerResources.loadResources` 那次 reload 里解析的，而物品 holder 的组件（`Holder
 * .components()`）要到之后单独调用的 `updateComponentsAndStaticRegistryTags()` 才绑定。
 * 解析期 `new ItemStack(...)` 会直接抛 `NullPointerException: Components not bound yet`，
 * 且异常会冲出 `SimpleJsonResourceListener.scanDirectory`，**导致整轮配方（含原版）全部加载失败**。
 * 原版同样规避了这点——`ShapedRecipe.result` 存的就是 `ItemStackTemplate`，到 {@code assemble()}
 * 才 `create()` 出 `ItemStack`。本类照此办理：模板在解析期建，栈在运行期产生。
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
    /** 单个产物：物品模板 + 产出概率（1.0 表示必出）。 */
    public record Output(ItemStackTemplate template, float chance) {
    }

    private final PythonRecipeSerializer owner;
    private final List<ItemStackTemplate> inputs;
    private final List<Output> outputs;
    private final Map<String, Object> fieldValues;

    public PythonRecipe(PythonRecipeSerializer owner, List<ItemStackTemplate> inputs,
                        List<Output> outputs, Map<String, Object> fieldValues) {
        this.owner = owner;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.fieldValues = Collections.unmodifiableMap(new LinkedHashMap<>(fieldValues));
    }

    /** 配方的具体输入物品模板（按槽位顺序）。 */
    public List<ItemStackTemplate> inputs() {
        return inputs;
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
     *
     * <p>物品相同按 **holder 身份** 比较（原版一个物品一个 holder），不触达组件，热路径上无分配。
     */
    @Override
    public boolean matches(PythonRecipeInput input, Level level) {
        if (inputs.isEmpty()) {
            return input.isEmpty();
        }
        if (input.size() < inputs.size()) {
            return false;
        }
        for (int i = 0; i < inputs.size(); i++) {
            ItemStackTemplate need = inputs.get(i);
            ItemStack have = input.getItem(i);
            if (have.isEmpty() || have.typeHolder() != need.item() || have.getCount() < need.count()) {
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
                return output.template().create();
            }
        }
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.get(0).template().create();
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

    /**
     * 摆放信息：由输入槽拼出真实 ingredients（对齐原版烹饪/切石机的做法）。
     *
     * <p><b>不能返回 {@link PlacementInfo#NOT_PLACEABLE}：</b>那会得到空的
     * {@code slotsToIngredientIndex}，{@code isImpossibleToPlace()} 为真，于是
     * {@code RecipeManager.unpackRecipeInfo} 会打
     * {@code Recipe ... can't be placed due to empty ingredients and will be ignored}
     * 并**直接跳过**该配方，配方进不了 ingredients / 属性集索引。
     * 只有「无输入」的配方才按 NOT_PLACEABLE 处理。
     */
    @Override
    public PlacementInfo placementInfo() {
        if (inputs.isEmpty()) {
            return PlacementInfo.NOT_PLACEABLE;
        }
        List<Ingredient> ingredients = new ArrayList<>(inputs.size());
        for (ItemStackTemplate template : inputs) {
            ingredients.add(Ingredient.of(template.item().value()));
        }
        return PlacementInfo.create(ingredients);
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return owner.bookCategory();
    }
}
