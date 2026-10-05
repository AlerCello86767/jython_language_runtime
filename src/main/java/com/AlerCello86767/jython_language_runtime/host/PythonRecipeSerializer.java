package com.AlerCello86767.jython_language_runtime.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 通用配方编解码器宿主（L1.1）。
 *
 * <p><b>26.1.2 的真实形状（已用 javap 核实）：</b>原版 {@link RecipeSerializer} 不再是可以实现的
 * 接口，而是一个 **record**：
 * <pre>{@code
 * public record RecipeSerializer<T extends Recipe<?>>(
 *         MapCodec<T> codec,
 *         StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {}
 * }</pre>
 * 所以「实现一个 serializer」在本版本等价于「构造一个 MapCodec + StreamCodec 交给 record」。
 * 本类的职责就是按 Python 声明的「输入槽形状 / 输出槽 / 附加字段表」动态拼出这两个编解码器。
 *
 * <p><b>字段表 → Codec 的取舍：</b>DFU 的 {@code RecordCodecBuilder} 要求字段在编译期固定，
 * 无法表达运行期才拿到的字段表。这里改为实现一个自定义 {@link MapCodec}：解码时把整个 JSON
 * 对象包成 {@link Dynamic}，再按声明表逐字段读取；编码时用 {@link DynamicOps} 逐字段回写。
 * 代价是放弃 DFU 的静态字段校验，改为在 {@link #decodeBody} 里手工校验必填项（如输入/输出的
 * {@code item}）。收益是任意字段表都能工作，且 {@code keys()} 仍能上报字段名。
 *
 * <p><b>双端同步：</b>Fabric 自 1.21.2 起接管了配方同步（原版只发配方书数据）。因此除
 * {@code StreamCodec} 外，注册方还必须调用
 * {@code RecipeSynchronization.synchronizeRecipeSerializer(serializer)}，否则客户端收不到
 * 自定义类型配方；本类的 {@link #streamCodec()} 就是双端同步用的字节格式（字段顺序与声明表一致）。
 *
 * <p>本类只负责序列化；注册与运行期查询见 {@code PyRecipes}。
 */
public final class PythonRecipeSerializer {
    /** 附加字段支持的类型。Declaration 里用字符串书写，这里做翻译。 */
    public enum FieldType {
        INT, FLOAT, DOUBLE, BOOL, STRING;

        /** 把 Python 声明的类型名翻译成枚举；非法值抛 {@link IllegalArgumentException}。 */
        public static FieldType from(String raw) {
            String name = raw == null ? "int" : raw.toLowerCase(Locale.ROOT);
            switch (name) {
                case "int":
                case "integer":
                    return INT;
                case "float":
                    return FLOAT;
                case "double":
                    return DOUBLE;
                case "bool":
                case "boolean":
                    return BOOL;
                case "string":
                case "str":
                    return STRING;
                default:
                    throw new IllegalArgumentException("Unsupported recipe field type: " + raw);
            }
        }
    }

    /**
     * 输入槽位声明。
     *
     * <p>目前只支持普通物品输入（{@code {"type": "item"}}）；{@code count} 是该槽位在配方 JSON
     * 未写 {@code count} 时的默认需求数量。标签输入等留待后续扩展。
     */
    public record InputDecl(int count) {
    }

    /** 输出槽位声明：{@code chanced=true} 表示该槽位允许概率副产物。 */
    public record OutputDecl(boolean chanced) {
    }

    private final Identifier id;
    private final RecipeType<PythonRecipe> type;
    private final List<InputDecl> inputs;
    private final List<OutputDecl> outputs;
    private final Map<String, FieldType> fields;
    /** 原版 {@code RecipeBookCategory} 是具体类（有公开无参构造），每个自定义类型复用一个实例。 */
    private final RecipeBookCategory bookCategory = new RecipeBookCategory();
    /** 注册后的原版 serializer 记录。解码时写进每个配方实例，供 {@code getSerializer()} 返回（同步按它分组）。 */
    private RecipeSerializer<PythonRecipe> serializer;

    public PythonRecipeSerializer(Identifier id, RecipeType<PythonRecipe> type,
                                  List<InputDecl> inputs, List<OutputDecl> outputs,
                                  Map<String, FieldType> fields) {
        this.id = id;
        this.type = type;
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        this.fields = new LinkedHashMap<>(fields);
    }

    /** 回填原版 serializer 记录（构造 codec 时需要它，故注册流程里一步绑定）。 */
    public void bind(RecipeSerializer<PythonRecipe> serializer) {
        this.serializer = serializer;
    }

    public Identifier id() {
        return id;
    }

    public RecipeType<PythonRecipe> type() {
        return type;
    }

    public RecipeSerializer<PythonRecipe> serializer() {
        return serializer;
    }

    public RecipeBookCategory bookCategory() {
        return bookCategory;
    }

    public List<InputDecl> inputDecls() {
        return inputs;
    }

    public List<OutputDecl> outputDecls() {
        return outputs;
    }

    public Map<String, FieldType> fieldTypes() {
        return java.util.Collections.unmodifiableMap(fields);
    }

    /** JSON ↔ {@link PythonRecipe} 的 MapCodec；交给原版 {@code RecipeSerializer} record。 */
    public MapCodec<PythonRecipe> codec() {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                List<T> names = new ArrayList<>(2 + fields.size());
                names.add(ops.createString("inputs"));
                names.add(ops.createString("outputs"));
                for (String field : fields.keySet()) {
                    names.add(ops.createString(field));
                }
                return names.stream();
            }

            @Override
            public <T> DataResult<PythonRecipe> decode(DynamicOps<T> ops, MapLike<T> input) {
                return decodeBody(ops, input);
            }

            @Override
            public <T> RecordBuilder<T> encode(PythonRecipe value, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                return encodeBody(value, ops, prefix);
            }
        };
    }

    /** 双端同步用的字节编解码；字段顺序严格按声明表，两端一致即可。 */
    public StreamCodec<RegistryFriendlyByteBuf, PythonRecipe> streamCodec() {
        return StreamCodec.of(this::write, this::read);
    }

    // ------------------------------------------------------------------
    // 解码：JSON → PythonRecipe
    // ------------------------------------------------------------------

    private <T> DataResult<PythonRecipe> decodeBody(DynamicOps<T> ops, MapLike<T> input) {
        Dynamic<T> root = new Dynamic<>(ops, ops.createMap(input.entries()));

        // 输入：{item, count?} 列表；count 缺省取声明表的默认值
        // 注意：这里只能造 ItemStackTemplate。解析期 new ItemStack 会读 holder 的组件，
        // 而组件要到 updateComponentsAndStaticRegistryTags 才绑定 → NPE（详见 PythonRecipe 类注释）。
        List<ItemStackTemplate> inputTemplates = new ArrayList<>();
        List<Dynamic<T>> inputElements = root.get("inputs").asStream().toList();
        int inputIndex = 0;
        for (Dynamic<T> element : inputElements) {
            int slot = inputIndex++;
            String itemRaw = element.get("item").asString(null);
            if (itemRaw == null) {
                return DataResult.error(() -> "Recipe input[" + slot + "] is missing 'item'");
            }
            Identifier itemId = Identifier.tryParse(itemRaw);
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                return DataResult.error(() -> "Unknown item in recipe input: " + itemRaw);
            }
            int fallbackCount = slot < inputs.size() ? inputs.get(slot).count() : 1;
            int count = Math.max(1, element.get("count").asInt(fallbackCount));
            inputTemplates.add(new ItemStackTemplate(BuiltInRegistries.ITEM.getValue(itemId), count));
        }

        // 输出：{item, count?, chance?} 列表；chance 缺省 1.0（必定产出）
        List<PythonRecipe.Output> results = new ArrayList<>();
        List<Dynamic<T>> outputElements = root.get("outputs").asStream().toList();
        int outputIndex = 0;
        for (Dynamic<T> element : outputElements) {
            int slot = outputIndex++;
            String itemRaw = element.get("item").asString(null);
            if (itemRaw == null) {
                return DataResult.error(() -> "Recipe output[" + slot + "] is missing 'item'");
            }
            Identifier itemId = Identifier.tryParse(itemRaw);
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                return DataResult.error(() -> "Unknown item in recipe output: " + itemRaw);
            }
            int count = Math.max(1, element.get("count").asInt(1));
            float chance = element.get("chance").asFloat(1.0f);
            chance = Math.max(0.0f, Math.min(1.0f, chance));
            results.add(new PythonRecipe.Output(
                    new ItemStackTemplate(BuiltInRegistries.ITEM.getValue(itemId), count), chance));
        }

        // 附加字段：按声明表读取，缺失补类型默认值
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, FieldType> entry : fields.entrySet()) {
            String name = entry.getKey();
            switch (entry.getValue()) {
                case INT:
                    values.put(name, root.get(name).asInt(0));
                    break;
                case FLOAT:
                    values.put(name, root.get(name).asFloat(0.0f));
                    break;
                case DOUBLE:
                    values.put(name, root.get(name).asDouble(0.0d));
                    break;
                case BOOL:
                    values.put(name, root.get(name).asBoolean(false));
                    break;
                case STRING:
                    values.put(name, root.get(name).asString(""));
                    break;
            }
        }

        return DataResult.success(new PythonRecipe(this, inputTemplates, results, values));
    }

    // ------------------------------------------------------------------
    // 编码：PythonRecipe → JSON（datagen / 调试路径；运行期正常走数据包直读 JSON）
    // ------------------------------------------------------------------

    private <T> RecordBuilder<T> encodeBody(PythonRecipe recipe, DynamicOps<T> ops, RecordBuilder<T> prefix) {
        prefix.add("inputs", ops.createList(recipe.inputs().stream().map(template -> {
            Map<T, T> entry = new LinkedHashMap<>();
            entry.put(ops.createString("item"), ops.createString(itemName(template)));
            entry.put(ops.createString("count"), ops.createInt(template.count()));
            return ops.createMap(entry.entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue())));
        })));

        prefix.add("outputs", ops.createList(recipe.outputs().stream().map(output -> {
            Map<T, T> entry = new LinkedHashMap<>();
            entry.put(ops.createString("item"), ops.createString(itemName(output.template())));
            entry.put(ops.createString("count"), ops.createInt(output.template().count()));
            if (output.chance() < 1.0f) {
                entry.put(ops.createString("chance"), ops.createFloat(output.chance()));
            }
            return ops.createMap(entry.entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue())));
        })));

        for (Map.Entry<String, FieldType> entry : fields.entrySet()) {
            Object value = recipe.fieldValues().get(entry.getKey());
            switch (entry.getValue()) {
                case INT:
                    prefix.add(entry.getKey(), ops.createInt(value instanceof Number ? ((Number) value).intValue() : 0));
                    break;
                case FLOAT:
                    prefix.add(entry.getKey(), ops.createFloat(value instanceof Number ? ((Number) value).floatValue() : 0.0f));
                    break;
                case DOUBLE:
                    prefix.add(entry.getKey(), ops.createDouble(value instanceof Number ? ((Number) value).doubleValue() : 0.0d));
                    break;
                case BOOL:
                    prefix.add(entry.getKey(), ops.createBoolean(value instanceof Boolean ? (Boolean) value : false));
                    break;
                case STRING:
                    prefix.add(entry.getKey(), ops.createString(value instanceof String ? (String) value : ""));
                    break;
            }
        }
        return prefix;
    }

    private static String itemName(ItemStackTemplate template) {
        return BuiltInRegistries.ITEM.getKey(template.item().value()).toString();
    }

    // ------------------------------------------------------------------
    // 网络编解码（双端同步）
    // ------------------------------------------------------------------

    private void write(RegistryFriendlyByteBuf buf, PythonRecipe recipe) {
        List<ItemStackTemplate> ins = recipe.inputs();
        buf.writeVarInt(ins.size());
        for (ItemStackTemplate template : ins) {
            ItemStackTemplate.STREAM_CODEC.encode(buf, template);
        }
        List<PythonRecipe.Output> outs = recipe.outputs();
        buf.writeVarInt(outs.size());
        for (PythonRecipe.Output output : outs) {
            ItemStackTemplate.STREAM_CODEC.encode(buf, output.template());
            buf.writeFloat(output.chance());
        }
        for (Map.Entry<String, FieldType> entry : fields.entrySet()) {
            Object value = recipe.fieldValues().get(entry.getKey());
            switch (entry.getValue()) {
                case INT:
                    buf.writeInt(value instanceof Number ? ((Number) value).intValue() : 0);
                    break;
                case FLOAT:
                    buf.writeFloat(value instanceof Number ? ((Number) value).floatValue() : 0.0f);
                    break;
                case DOUBLE:
                    buf.writeDouble(value instanceof Number ? ((Number) value).doubleValue() : 0.0d);
                    break;
                case BOOL:
                    buf.writeBoolean(value instanceof Boolean ? (Boolean) value : false);
                    break;
                case STRING:
                    buf.writeUtf(value instanceof String ? (String) value : "");
                    break;
            }
        }
    }

    private PythonRecipe read(RegistryFriendlyByteBuf buf) {
        int inputCount = buf.readVarInt();
        List<ItemStackTemplate> ins = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            ins.add(ItemStackTemplate.STREAM_CODEC.decode(buf));
        }
        int outputCount = buf.readVarInt();
        List<PythonRecipe.Output> outs = new ArrayList<>(outputCount);
        for (int i = 0; i < outputCount; i++) {
            ItemStackTemplate template = ItemStackTemplate.STREAM_CODEC.decode(buf);
            outs.add(new PythonRecipe.Output(template, buf.readFloat()));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, FieldType> entry : fields.entrySet()) {
            switch (entry.getValue()) {
                case INT:
                    values.put(entry.getKey(), buf.readInt());
                    break;
                case FLOAT:
                    values.put(entry.getKey(), buf.readFloat());
                    break;
                case DOUBLE:
                    values.put(entry.getKey(), buf.readDouble());
                    break;
                case BOOL:
                    values.put(entry.getKey(), buf.readBoolean());
                    break;
                case STRING:
                    values.put(entry.getKey(), buf.readUtf());
                    break;
            }
        }
        return new PythonRecipe(this, ins, outs, values);
    }
}
