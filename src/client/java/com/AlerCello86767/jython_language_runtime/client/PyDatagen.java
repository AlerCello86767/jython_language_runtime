package com.AlerCello86767.jython_language_runtime.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.client.datagen.PyBlockLootProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyBlockModelGen;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyBlockTagProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyDamageTypeGen;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyDamageTypeProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyEnchantmentGen;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyEnchantmentProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyItemTagProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyJsonProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyJsonWriter;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyLangProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyModelProvider;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyRecipeGen;
import com.AlerCello86767.jython_language_runtime.client.datagen.PyRecipeProvider;

import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;

/**
 * Datagen 门面（L1.2）：让 Python 在 {@code fabric-datagen} 入口里声明 provider，由 Gradle
 * {@code runDatagen} 产出配方 / 战利品 / 模型 / lang / 标签 / 任意 JSON。
 *
 * <p>Python 侧不接触任何 Fabric 数据生成类型：只传入口拿到的 {@link FabricDataGenerator}
 * 和一个回调（或一份声明式数据），门面负责 {@code createPack().addProvider(...)} 注册桥接
 * provider。
 *
 * <pre>
 * def onInitializeDataGenerator(self, pack):
 *     PyDatagen.recipes(pack, lambda gen: (
 *         gen.smelting("mymod:ruby_dust", "mymod:ruby_ore", 0.7),
 *         gen.custom("crushing", "mymod:crush_iron", {"inputs": [...], "outputs": [...]}),
 *     ))
 *     PyDatagen.lang(pack, "en_us", {"item.mymod.ruby": "Ruby"})
 *     PyDatagen.lootBlocks(pack, lambda t: t.dropSelf("mymod:machine_frame"))
 *     PyDatagen.blockModels(pack, lambda m: (m.cubeAll("mymod:machine_frame"), m.item("mymod:ruby")))
 *     PyDatagen.tags(pack, "block", "mineable/pickaxe", ["mymod:machine_frame"])
 *     PyDatagen.json(pack, "enchantment", lambda out: out.write("mymod:smelting_touch", {...}))
 *     PyDatagen.damageTypes(pack, lambda d: d.define("mymod:overheat", {"exhaustion": 0.1}))
 *     PyDatagen.enchantments(pack, lambda e: e.define("mymod:smelting_touch", {"items": "#minecraft:enchantable/mining"}))
 * </pre>
 *
 * <p>各 provider 的参数类型见对应 builder 门面：{@link PyRecipeGen}、{@link PyBlockModelGen}、
 * {@link PyDamageTypeGen}、{@link PyEnchantmentGen} 与 {@link PyJsonWriter}。
 * id 省略命名空间时按当前模组补全。
 */
public final class PyDatagen {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyDatagen");

    private PyDatagen() {
    }

    /** 配方 provider：回调收到 {@link PyRecipeGen}（{@code gen}）。 */
    public static void recipes(Object generator, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.RegistryDependentFactory<PyRecipeProvider>)
                        (output, registries) -> new PyRecipeProvider(output, registries, callback));
        LOGGER.info("Registered datagen recipe provider for {}", dataGenerator.getModId());
    }

    /** 语言 provider：把 {@code {翻译键: 译文}} 写成 {@code assets/<ns>/lang/<locale>.json}。 */
    public static void lang(Object generator, String locale, Map<String, Object> translations) {
        FabricDataGenerator dataGenerator = require(generator);
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.RegistryDependentFactory<PyLangProvider>)
                        (output, registries) -> new PyLangProvider(output, locale, registries, translations));
        LOGGER.info("Registered datagen lang provider '{}' ({} entries)", locale, translations.size());
    }

    /** 方块战利品 provider：回调收到 {@link com.AlerCello86767.jython_language_runtime.client.datagen.PyLootGen}（{@code t}）。 */
    public static void lootBlocks(Object generator, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.RegistryDependentFactory<PyBlockLootProvider>)
                        (output, registries) -> new PyBlockLootProvider(output, registries, callback));
        LOGGER.info("Registered datagen block loot provider for {}", dataGenerator.getModId());
    }

    /** 模型 provider（client）：回调收到 {@link PyBlockModelGen}（{@code m}）。 */
    public static void blockModels(Object generator, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.Factory<PyModelProvider>)
                        output -> new PyModelProvider(output, callback));
        LOGGER.info("Registered datagen model provider for {}", dataGenerator.getModId());
    }

    /**
     * 标签 provider：{@code tags(pack, "block"/"item", "mineable/pickaxe", [ids])}。
     *
     * <p>标签名省略命名空间时默认 {@code minecraft}——挖掘/工具类标签必须落在 minecraft 命名空间
     * 才会被原版识别；条目 id 省略命名空间时按当前模组补全。
     */
    public static void tags(Object generator, String registry, String tag, List<String> values) {
        FabricDataGenerator dataGenerator = require(generator);
        String namespace = dataGenerator.getModId();
        Identifier tagId = parseTagId(tag);
        List<Identifier> ids = new ArrayList<>();
        for (String value : values) {
            ids.add(of(value, namespace));
        }
        if ("block".equals(registry)) {
            dataGenerator.createPack()
                    .addProvider((FabricDataGenerator.Pack.RegistryDependentFactory<PyBlockTagProvider>)
                            (output, registries) -> new PyBlockTagProvider(output, registries, tagId, ids));
        } else if ("item".equals(registry)) {
            dataGenerator.createPack()
                    .addProvider((FabricDataGenerator.Pack.RegistryDependentFactory<PyItemTagProvider>)
                            (output, registries) -> new PyItemTagProvider(output, registries, tagId, ids));
        } else {
            throw new IllegalArgumentException("Unsupported tag registry: " + registry
                    + " (expected block/item)");
        }
        LOGGER.info("Registered datagen {} tag provider: {} <- {}", registry, tagId, ids);
    }

    /** 通用 JSON provider：回调收到 {@link PyJsonWriter}（{@code out}），写 {@code data/<ns>/<目录>/*.json}。 */
    public static void json(Object generator, String directory, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        if (directory == null || directory.isEmpty()) {
            throw new IllegalArgumentException("json requires a directory name");
        }
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.Factory<PyJsonProvider>)
                        output -> new PyJsonProvider(output, directory, callback));
        LOGGER.info("Registered datagen json provider '{}' for {}", directory, dataGenerator.getModId());
    }

    /**
     * 资源侧通用 JSON provider：回调收到 {@link PyJsonWriter}（{@code out}），
     * 写 {@code assets/<ns>/<目录>/*.json}。
     *
     * <p>方块状态（{@code "blockstates"}）、模型（{@code "models"}）、物品定义（{@code "items"}）
     * 这类**资产** JSON 用它；数据包侧（{@code data/}）用 {@link #json(Object, String, PyObject)}。
     *
     * <p><b>目录与 id 是拼接关系</b>：`assets/<ns>/<目录>/<id 的 path>.json`。
     * 例如目录 {@code "models"} + id {@code "mymod:block/ruby_crop_stage0"}
     * → {@code assets/mymod/models/block/ruby_crop_stage0.json}。别写成 {@code "models/block"}，
     * 那会得到 {@code models/block/block/...}。
     *
     * <pre>
     * PyDatagen.assetsJson(pack, "blockstates", lambda out: out.write("mymod:ruby_crop", {
     *     "variants": {"age=0": {"model": "mymod:block/ruby_crop_stage0"}, ...},
     * }))
     * </pre>
     */
    public static void assetsJson(Object generator, String directory, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        if (directory == null || directory.isEmpty()) {
            throw new IllegalArgumentException("assetsJson requires a directory name");
        }
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.Factory<PyJsonProvider>)
                        output -> new PyJsonProvider(output, PackOutput.Target.RESOURCE_PACK,
                                directory, callback));
        LOGGER.info("Registered datagen assets json provider '{}' for {}",
                directory, dataGenerator.getModId());
    }

    /**
     * 伤害类型 provider（L4.6）：回调收到 {@link PyDamageTypeGen}（{@code d}），
     * 写 {@code data/<ns>/damage_type/<path>.json}。
     */
    public static void damageTypes(Object generator, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.Factory<PyDamageTypeProvider>)
                        output -> new PyDamageTypeProvider(output, callback));
        LOGGER.info("Registered datagen damage type provider for {}", dataGenerator.getModId());
    }

    /**
     * 附魔 provider（L4.5）：回调收到 {@link PyEnchantmentGen}（{@code e}），
     * 写 {@code data/<ns>/enchantment/<path>.json}。
     */
    public static void enchantments(Object generator, PyObject callback) {
        FabricDataGenerator dataGenerator = require(generator);
        dataGenerator.createPack()
                .addProvider((FabricDataGenerator.Pack.Factory<PyEnchantmentProvider>)
                        output -> new PyEnchantmentProvider(output, callback));
        LOGGER.info("Registered datagen enchantment provider for {}", dataGenerator.getModId());
    }

    private static FabricDataGenerator require(Object generator) {
        if (generator instanceof FabricDataGenerator dataGenerator) {
            return dataGenerator;
        }
        throw new IllegalArgumentException("PyDatagen expects the FabricDataGenerator passed to"
                + " onInitializeDataGenerator, got: " + generator);
    }

    /** 标签名省略命名空间时默认 minecraft。 */
    private static Identifier parseTagId(String tag) {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("Empty tag id");
        }
        int colon = tag.indexOf(':');
        if (colon < 0) {
            return Identifier.fromNamespaceAndPath("minecraft", tag);
        }
        return Identifier.fromNamespaceAndPath(tag.substring(0, colon), tag.substring(colon + 1));
    }

    private static Identifier of(String raw, String namespace) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Empty id in tag entry");
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return Identifier.fromNamespaceAndPath(namespace, raw);
        }
        return Identifier.fromNamespaceAndPath(raw.substring(0, colon), raw.substring(colon + 1));
    }
}
