package com.AlerCello86767.jython_language_runtime.client.datagen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;

/**
 * Python 侧写 JSON 的输出门面：把「id + 由 str/int/float/bool/dict/list 组成的 Map」写成
 * {@code data/<namespace>/<目录>/<path>.json}。
 *
 * <p>这是 datagen 里唯一「不经过原版 Codec/Builder」的入口，用于原版没有提供 datagen 支持、
 * 或运行时尚未封装 serializer 的数据（如 L4.5 附魔、L4.6 伤害类型、L1.1 自定义配方）。
 * 写到哪个目录由目录名字符串决定（{@code "recipe"} / {@code "enchantment"} / {@code "damage_type"} ...）。
 *
 * <pre>
 * PyDatagen.json(pack, "enchantment", lambda out: out.write("mymod:smelting_touch", {...}))
 * </pre>
 */
public final class PyJsonWriter {
    private final FabricPackOutput output;
    private final CachedOutput cachedOutput;
    private final String directory;
    private final List<CompletableFuture<?>> writes = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    PyJsonWriter(FabricPackOutput output, CachedOutput cachedOutput, String directory) {
        this.output = output;
        this.cachedOutput = cachedOutput;
        this.directory = directory;
    }

    /** Python：{@code out.write("ns:path", { ... })}。 */
    public void write(String id, Map<String, Object> value) {
        Identifier key = DatagenIds.of(id, output.getModId());
        Path path = output.createPathProvider(PackOutput.Target.DATA_PACK, directory).json(key);
        writes.add(DataProvider.saveStable(cachedOutput, toJson(value), path));
    }

    /** 回调跑完后调用：把所有已提交的写入合并为一个 future。 */
    CompletableFuture<?> finish() {
        CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0]))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        completion.completeExceptionally(error);
                    } else {
                        completion.complete(null);
                    }
                });
        return completion;
    }

    /** Map / List / 标量 → Gson JsonElement。 */
    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            for (Object element : list) {
                array.add(toJson(element));
            }
            return array;
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        throw new IllegalArgumentException("Unsupported JSON value type: "
                + value.getClass().getName() + " = " + value);
    }
}
