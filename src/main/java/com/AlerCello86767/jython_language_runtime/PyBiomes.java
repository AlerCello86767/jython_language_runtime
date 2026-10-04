package com.AlerCello86767.jython_language_runtime;

import java.util.Locale;
import java.util.function.Predicate;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;

/**
 * 生物群系修改门面（{@code fabric-biome-api-v1}）：给已有群系加地物 / 加刷怪 / 加洞穴雕刻。
 *
 * <pre>
 * PyBiomes.addFeature("overworld", "minecraft:ore_diamond", "underground_ores")
 * PyBiomes.addFeature("#minecraft:is_forest", "jython_language_runtime:ruby_geode", "underground_decoration")
 * PyBiomes.addSpawn("minecraft:plains", "creature", "jython_language_runtime:ruby_golem", 10, 2, 4)
 * PyBiomes.addCarver("nether", "minecraft:cave")
 * </pre>
 *
 * <p>{@code selector} 决定改哪些群系，支持：
 * <ul>
 *   <li>{@code "all"} / {@code "vanilla"} / {@code "overworld"} / {@code "nether"} / {@code "end"}</li>
 *   <li>{@code "#minecraft:is_forest"} 这类群系标签（前缀 {@code #}）</li>
 *   <li>{@code "minecraft:plains"} 单个群系</li>
 * </ul>
 *
 * <p>{@code step} 是地物生成阶段，取 {@code raw_generation / lakes / local_modifications /
 * underground_structures / surface_structures / strongholds / underground_ores / underground_decoration /
 * fluid_springs / vegetal_decoration / top_layer_modification}。阶段选错不会报错，但地物可能被地形覆盖——
 * 矿石用 {@code underground_ores}，植被用 {@code vegetal_decoration}。
 *
 * <p>{@code featureId} 指向 {@code data/<ns>/worldgen/placed_feature/} 下的地物，
 * {@code carverId} 指向 {@code data/<ns>/worldgen/configured_carver/}——两者都是**资源文件**，
 * 本门面只负责把它们挂到群系上，生成不出口资源。加刷怪用实体类型 id，取自实体注册表。
 */
public final class PyBiomes {
    private PyBiomes() {
    }

    /** 给匹配的群系追加一个已放置地物（{@code data/<ns>/worldgen/placed_feature/}）。 */
    public static void addFeature(String selector, String featureId, String step) {
        BiomeModifications.addFeature(
                selector(selector),
                step(step),
                ResourceKey.create(Registries.PLACED_FEATURE, ModIds.parse(featureId)));
    }

    /** 给匹配的群系追加一个洞口雕刻（{@code data/<ns>/worldgen/configured_carver/}）。 */
    public static void addCarver(String selector, String carverId) {
        BiomeModifications.addCarver(
                selector(selector),
                ResourceKey.create(Registries.CONFIGURED_CARVER, ModIds.parse(carverId)));
    }

    /**
     * 给匹配的群系追加刷怪。
     *
     * @param category 生物类别，取 {@code monster / creature / ambient / axolotls /
     *                 underground_water_creature / water_creature / water_ambient / misc}
     * @param weight   权重，越大越常见
     * @param minGroup 每群最少数量
     * @param maxGroup 每群最多数量
     */
    public static void addSpawn(String selector, String category, String entityId,
                                int weight, int minGroup, int maxGroup) {
        BiomeModifications.addSpawn(
                selector(selector),
                mobCategory(category),
                entityType(entityId),
                weight, minGroup, maxGroup);
    }

    // ---------- 内部 ----------

    private static Predicate<BiomeSelectionContext> selector(String spec) {
        String value = spec.trim();
        switch (value) {
            case "all":
                return BiomeSelectors.all();
            case "vanilla":
                return BiomeSelectors.vanilla();
            case "overworld":
                return BiomeSelectors.foundInOverworld();
            case "nether":
                return BiomeSelectors.foundInTheNether();
            case "end":
                return BiomeSelectors.foundInTheEnd();
            default:
                break;
        }
        if (value.startsWith("#")) {
            Identifier tagId = ModIds.parse(value.substring(1));
            return BiomeSelectors.tag(TagKey.create(Registries.BIOME, tagId));
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, ModIds.parse(value));
        return BiomeSelectors.includeByKey(key);
    }

    private static GenerationStep.Decoration step(String name) {
        try {
            return GenerationStep.Decoration.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知生成阶段: " + name
                    + "（可用 raw_generation/lakes/local_modifications/underground_structures/"
                    + "surface_structures/strongholds/underground_ores/underground_decoration/"
                    + "fluid_springs/vegetal_decoration/top_layer_modification）");
        }
    }

    private static MobCategory mobCategory(String name) {
        try {
            return MobCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知生物类别: " + name
                    + "（可用 monster/creature/ambient/axolotls/underground_water_creature/"
                    + "water_creature/water_ambient/misc）");
        }
    }

    private static EntityType<?> entityType(String entityId) {
        Identifier key = ModIds.parse(entityId);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            throw new IllegalArgumentException("未知实体: " + entityId);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(key);
    }
}
