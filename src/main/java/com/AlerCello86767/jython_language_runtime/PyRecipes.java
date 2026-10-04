package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.asList;
import static com.AlerCello86767.jython_language_runtime.core.Params.asMap;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.host.PythonRecipe;
import com.AlerCello86767.jython_language_runtime.host.PythonRecipeInput;
import com.AlerCello86767.jython_language_runtime.host.PythonRecipeSerializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.recipe.v1.sync.RecipeSynchronization;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * 配方系统门面（L1.1）：Python 声明配方类型，Java 承载通用 {@code Recipe}/{@code RecipeSerializer}。
 *
 * <pre>{@code
 * # 初始化期（onInitialize 窗口内）：声明配方类型的「输入槽形状 + 输出 + 附加字段」
 * PyRecipes.registerType("crushing", {
 *     "inputs":  [{"type": "item", "count": 1}],
 *     "outputs": [{"type": "item"}, {"type": "chance"}],
 *     "fields":  {"energy": "int", "duration": "int"},
 * })
 *
 * # 运行期（机器 tick）：查询 + 产出
 * recipe = PyRecipes.find("crushing", level, [inputStack])   # -> RecipeHolder 或 None
 * result = PyRecipes.assemble(recipe, [inputStack])          # -> {"outputs": [...], "energy": .., "duration": ..}
 * }</pre>
 *
 * <p><b>注册的三件事（缺一不可）：</b>
 * <ol>
 *   <li>{@code BuiltInRegistries.RECIPE_TYPE}：让配方能按类型分组（不能用
 *       {@code RecipeType.register(String)}——它内部走 {@code Identifier.withDefaultNamespace}，会强制
 *       {@code minecraft:} 命名空间）；</li>
 *   <li>{@code BuiltInRegistries.RECIPE_SERIALIZER}：record {@code RecipeSerializer}（MapCodec + StreamCodec），
 *       JSON 的 {@code "type"} 字段按此解析；</li>
 *   <li>{@code RecipeSynchronization.synchronizeRecipeSerializer}：Fabric 自 1.21.2 接管配方同步，
 *       不登记则客户端收不到自定义类型配方。</li>
 * </ol>
 *
 * <p><b>成本模型（性能纪律）：</b>机器每 tick 都要找配方，因此**绝不在 Python 侧做全表扫描**。
 * 查询路径是：{@code find} → 取「按 (RecipeManager, RecipeType) 缓存的候选表」→ 在 Java 侧逐条
 * {@code matches}。候选表缓存按 {@link RecipeManager} 实例（弱引用）保存，数据包重载
 * （{@code END_DATA_PACK_RELOAD}）时整体失效，因此一张表只在重载后被构建一次。
 *
 * <p>候选表内部再按「输入物品」建倒排索引：查找时取**第一个非空输入槽**的物品对应的小列表。
 * 正确性：若某配方能匹配，则它必然在这些非空槽上都有同名输入物品，故一定落在该列表中（详见
 * {@link TypeCache#pool}）。这样 per-tick 成本从「该类型全部配方」降到「用到第一个输入物品的配方」。
 *
 * <p>所有注册必须发生在模组初始化阶段（onInitialize 窗口内）。
 */
public final class PyRecipes {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyRecipes");

    /** 已声明的配方类型：id → 编解码器宿主。运行期查询按此解析。 */
    private static final Map<Identifier, PythonRecipeSerializer> SPECS = new ConcurrentHashMap<>();

    /** 候选表缓存：RecipeManager（弱引用）→ (RecipeType → 候选表)。数据包重载时清空。 */
    private static final Map<RecipeManager, Map<RecipeType<?>, TypeCache>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 重载钩子只注册一次。 */
    private static final AtomicBoolean RELOAD_HOOK = new AtomicBoolean(false);

    /** 不带 level 的 {@code assemble} 用的随机源（服务端主线程专用，见 2 参重载注释）。 */
    private static final RandomSource FALLBACK_RANDOM = RandomSource.createThreadLocalInstance();

    private PyRecipes() {
    }

    /**
     * 声明并注册一个配方类型。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code inputs} (List&lt;Map&gt)：输入槽形状。每项 {@code type} 目前只支持 {@code "item"}，
     *       {@code count} 为该槽位在配方 JSON 未写 count 时的默认需求数量（默认 1）。非法 type 抛
     *       {@link IllegalArgumentException}。</li>
     *   <li>{@code outputs} (List&lt;Map&gt)：输出槽形状。每项 {@code type} ∈ {@code "item"}（主产物）
     *       或 {@code "chance"}（概率副产物）。</li>
     *   <li>{@code fields} (Map&lt;String, String&gt;)：附加数值字段表，值 ∈
     *       {@code int} / {@code float} / {@code double} / {@code bool} / {@code string}。</li>
     * </ul>
     */
    public static void registerType(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        Map<String, Object> opts = options == null ? Collections.emptyMap() : options;

        List<PythonRecipeSerializer.InputDecl> inputs = new ArrayList<>();
        List<?> rawInputs = asList(opts.get("inputs"));
        if (rawInputs != null) {
            int index = 0;
            for (Object raw : rawInputs) {
                Map<String, Object> entry = asMap(raw);
                if (entry == null) {
                    throw new IllegalArgumentException("inputs[" + index + "] must be a map");
                }
                String type = asString(entry.get("type"), "item");
                if (!"item".equals(type)) {
                    throw new IllegalArgumentException(
                            "inputs[" + index + "].type 目前只支持 \"item\"，收到: " + type);
                }
                inputs.add(new PythonRecipeSerializer.InputDecl(Math.max(1, asInt(entry.get("count"), 1))));
                index++;
            }
        }

        List<PythonRecipeSerializer.OutputDecl> outputs = new ArrayList<>();
        List<?> rawOutputs = asList(opts.get("outputs"));
        if (rawOutputs != null) {
            int index = 0;
            for (Object raw : rawOutputs) {
                Map<String, Object> entry = asMap(raw);
                if (entry == null) {
                    throw new IllegalArgumentException("outputs[" + index + "] must be a map");
                }
                String type = asString(entry.get("type"), "item");
                if (!"item".equals(type) && !"chance".equals(type)) {
                    throw new IllegalArgumentException(
                            "outputs[" + index + "].type 只支持 \"item\" 或 \"chance\"，收到: " + type);
                }
                outputs.add(new PythonRecipeSerializer.OutputDecl("chance".equals(type)));
                index++;
            }
        }

        Map<String, PythonRecipeSerializer.FieldType> fields = new LinkedHashMap<>();
        Map<String, Object> rawFields = asMap(opts.get("fields"));
        if (rawFields != null) {
            for (Map.Entry<String, Object> entry : rawFields.entrySet()) {
                fields.put(entry.getKey(),
                        PythonRecipeSerializer.FieldType.from(asString(entry.getValue(), "int")));
            }
        }

        // 1) 注册 RecipeType：必须自己 register——RecipeType.register(String) 会强制 minecraft: 命名空间
        RecipeType<PythonRecipe> recipeType = new RecipeType<>() {
            @Override
            public String toString() {
                return id.toString();
            }
        };
        Registry.register(BuiltInRegistries.RECIPE_TYPE, id, recipeType);

        // 2) 构造并注册通用 serializer（record：MapCodec + StreamCodec）
        PythonRecipeSerializer helper =
                new PythonRecipeSerializer(id, recipeType, inputs, outputs, fields);
        RecipeSerializer<PythonRecipe> serializer =
                new RecipeSerializer<>(helper.codec(), helper.streamCodec());
        helper.bind(serializer);
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id, serializer);

        // 3) 登记到 Fabric 双端配方同步（不登记客户端收不到）
        RecipeSynchronization.synchronizeRecipeSerializer(serializer);

        SPECS.put(id, helper);
        ensureReloadHook();

        LOGGER.info("Registered recipe type {} (inputs={}, outputs={}, fields={})",
                id, inputs.size(), outputs.size(), fields.size());
    }

    /**
     * 运行期查询：按类型与输入找第一条匹配的配方。
     *
     * @param typeId 配方类型 id；写全限定（{@code "mymod:crushing"}）最稳；短名会在已注册类型里按 path 唯一匹配
     * @param level  任意 {@link Level}；服务端机器 tick 传 ServerLevel
     * @param inputs 输入物品栈列表（Python list of ItemStack，多余的槽位会被忽略）
     * @return 命中的 {@link RecipeHolder}；无匹配、或非服务端 / level 为空时返回 {@code null}
     */
    public static RecipeHolder<PythonRecipe> find(String typeId, Level level, List<?> inputs) {
        PythonRecipeSerializer spec = resolve(typeId);
        RecipeManager manager = managerOf(level);
        if (manager == null) {
            return null;
        }
        PythonRecipeInput input = toInput(inputs);
        List<RecipeHolder<PythonRecipe>> pool = cache(manager, spec.type()).pool(input);
        for (RecipeHolder<PythonRecipe> holder : pool) {
            if (holder.value().matches(input, level)) {
                return holder;
            }
        }
        return null;
    }

    /**
     * 产出（不含等级随机源，用门面内置随机源）。
     *
     * <p>等价于 {@code assemble(recipe, inputs, null)}。内置随机源是单线程实例（服务端主线程），
     * 因此机器 tick 建议用三参重载走 {@code level} 的世界随机源。
     */
    public static Map<String, Object> assemble(Object recipe, List<?> inputs) {
        return assemble(recipe, inputs, null);
    }

    /**
     * 产出：把配方产物按概率掷随机数，连同声明的附加字段一起返回给 Python。
     *
     * <p>返回结构（Python 侧可直接下标取值）：{@code {"outputs": [ItemStack, ...], <字段名>: <值>, ...}}。
     * 概率：{@code chance >= 1.0} 必出，否则 {@code random.nextFloat() < chance} 才产出。
     *
     * @param recipe {@link #find} 返回的 {@link RecipeHolder}，或直接是 {@code PythonRecipe}
     * @param inputs 输入（保留形参以对齐 API；产出不依赖输入）
     * @param level  世界；为 {@code null} 时退回门面内置随机源
     */
    public static Map<String, Object> assemble(Object recipe, List<?> inputs, Level level) {
        PythonRecipe resolved = resolveRecipe(recipe);
        RandomSource random = level != null ? level.getRandom() : FALLBACK_RANDOM;

        List<ItemStack> produced = new ArrayList<>();
        for (PythonRecipe.Output output : resolved.outputs()) {
            float chance = output.chance();
            if (chance >= 1.0f || random.nextFloat() < chance) {
                produced.add(output.stack().copy());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outputs", produced);
        result.putAll(resolved.fieldValues());
        return result;
    }

    /**
     * 该类型下的全部配方（按 (RecipeManager, RecipeType) 缓存，只在数据包重载后重算一次）。
     *
     * <p>返回只读列表；供 {@code find} 之外的场景（如图鉴、调试）使用。
     */
    public static List<RecipeHolder<PythonRecipe>> candidates(String typeId, Level level) {
        PythonRecipeSerializer spec = resolve(typeId);
        RecipeManager manager = managerOf(level);
        if (manager == null) {
            return List.of();
        }
        return cache(manager, spec.type()).all();
    }

    /** {@link #candidates} 的别名：列出该类型的全部配方。 */
    public static List<RecipeHolder<PythonRecipe>> listAll(String typeId, Level level) {
        return candidates(typeId, level);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 注册一次数据包重载监听：重载后清空候选表（RecipeManager 实例可能复用，缓存必须失效）。 */
    private static void ensureReloadHook() {
        if (RELOAD_HOOK.compareAndSet(false, true)) {
            ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                    (server, resourceManager, success) -> CACHE.clear());
        }
    }

    /** 取服务端 RecipeManager；客户端 / 无服务端时返回 null。 */
    private static RecipeManager managerOf(Level level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer().getRecipeManager();
    }

    /** 解析类型 id：全限定直接查表；短名按 path 在所有已注册类型里唯一匹配（运行期无命名空间上下文）。 */
    private static PythonRecipeSerializer resolve(String typeId) {
        if (typeId == null || typeId.isEmpty()) {
            throw new IllegalArgumentException("Empty recipe type id");
        }
        if (typeId.indexOf(':') >= 0) {
            PythonRecipeSerializer spec = SPECS.get(ModIds.parse(typeId));
            if (spec == null) {
                throw new IllegalArgumentException("Unknown recipe type: " + typeId);
            }
            return spec;
        }
        PythonRecipeSerializer found = null;
        Identifier foundId = null;
        for (Map.Entry<Identifier, PythonRecipeSerializer> entry : SPECS.entrySet()) {
            if (entry.getKey().getPath().equals(typeId)) {
                if (found != null) {
                    throw new IllegalArgumentException("Ambiguous recipe type '" + typeId
                            + "': matches " + foundId + " and " + entry.getKey());
                }
                found = entry.getValue();
                foundId = entry.getKey();
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Unknown recipe type: " + typeId);
        }
        return found;
    }

    /** 取（必要时构建）某 RecipeManager 下某类型的候选表。 */
    private static TypeCache cache(RecipeManager manager, RecipeType<PythonRecipe> type) {
        Map<RecipeType<?>, TypeCache> perManager =
                CACHE.computeIfAbsent(manager, ignored -> new ConcurrentHashMap<>());
        return perManager.computeIfAbsent(type, ignored -> TypeCache.build(manager.getAllOfType(type)));
    }

    /** 把 Python 传来的列表防御性转成 {@link PythonRecipeInput}（非 ItemStack 一律当空槽）。 */
    private static PythonRecipeInput toInput(List<?> inputs) {
        List<ItemStack> stacks = new ArrayList<>();
        if (inputs != null) {
            for (Object raw : inputs) {
                stacks.add(raw instanceof ItemStack ? (ItemStack) raw : ItemStack.EMPTY);
            }
        }
        return new PythonRecipeInput(stacks);
    }

    /** 兼容 {@link RecipeHolder} 与裸 {@code PythonRecipe} 两种入参。 */
    private static PythonRecipe resolveRecipe(Object recipe) {
        if (recipe instanceof RecipeHolder<?>) {
            Object value = ((RecipeHolder<?>) recipe).value();
            if (value instanceof PythonRecipe) {
                return (PythonRecipe) value;
            }
        }
        if (recipe instanceof PythonRecipe) {
            return (PythonRecipe) recipe;
        }
        throw new IllegalArgumentException("Not a Python recipe instance: " + recipe);
    }

    /**
     * 某个配方类型的候选表（按 RecipeManager 实例缓存，重载时随外层缓存整体失效）。
     *
     * <p>构建一次的成本是 O(R)（R = 该类型配方数）；之后每次查找只做 O(1) 的倒排命中 + 对命中小列表
     * 的线性 {@code matches}。
     */
    private static final class TypeCache {
        private final List<RecipeHolder<PythonRecipe>> all;
        private final Map<Item, List<RecipeHolder<PythonRecipe>>> byInputItem;
        private final List<RecipeHolder<PythonRecipe>> noInput;

        private TypeCache(List<RecipeHolder<PythonRecipe>> all,
                          Map<Item, List<RecipeHolder<PythonRecipe>>> byInputItem,
                          List<RecipeHolder<PythonRecipe>> noInput) {
            this.all = all;
            this.byInputItem = byInputItem;
            this.noInput = noInput;
        }

        static TypeCache build(Collection<RecipeHolder<PythonRecipe>> recipes) {
            List<RecipeHolder<PythonRecipe>> all = List.copyOf(recipes);
            Map<Item, List<RecipeHolder<PythonRecipe>>> byItem = new HashMap<>();
            List<RecipeHolder<PythonRecipe>> noInput = new ArrayList<>();
            for (RecipeHolder<PythonRecipe> holder : all) {
                List<ItemStack> ins = holder.value().inputStacks();
                if (ins.isEmpty()) {
                    noInput.add(holder);
                    continue;
                }
                Set<Item> seen = new HashSet<>();
                for (ItemStack stack : ins) {
                    if (stack.isEmpty() || !seen.add(stack.getItem())) {
                        continue;
                    }
                    byItem.computeIfAbsent(stack.getItem(), key -> new ArrayList<>()).add(holder);
                }
            }
            return new TypeCache(all, byItem, noInput);
        }

        List<RecipeHolder<PythonRecipe>> all() {
            return all;
        }

        /**
         * 取可能匹配的候选列表。
         *
         * <p>取**第一个非空输入槽**的物品对应的倒排列表。正确性：设该槽位下标为 k，若配方 r 能匹配，
         * 且 r 的输入槽数 R &gt; k，则 r 的第 k 槽输入物品必等于传入物品，故 r 落在该列表里；若
         * R &le; k，则传入的 0..k-1 槽全空（k 是第一个非空），r 的输入槽要求非空物品，故 r 不可能匹配。
         * 因此用第一个非空槽的列表既不会漏（无假阴性），又比「取最小列表」更简单且正确。
         *
         * <p>倒排未命中说明没有任何配方在该槽位使用此物品 → 直接无候选。传入全空 → 只有无输入配方可能匹配。
         */
        List<RecipeHolder<PythonRecipe>> pool(PythonRecipeInput input) {
            for (int i = 0; i < input.size(); i++) {
                ItemStack stack = input.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                List<RecipeHolder<PythonRecipe>> list = byInputItem.get(stack.getItem());
                return list == null ? List.of() : list;
            }
            return noInput;
        }
    }
}
