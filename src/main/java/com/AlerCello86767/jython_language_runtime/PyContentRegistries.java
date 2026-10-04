package com.AlerCello86767.jython_language_runtime;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.registry.CompostableRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.registry.FlattenableBlockRegistry;
import net.fabricmc.fabric.api.registry.FuelValueEvents;
import net.fabricmc.fabric.api.registry.OxidizableBlocksRegistry;
import net.fabricmc.fabric.api.registry.StrippableBlockRegistry;
import net.fabricmc.fabric.api.registry.TillableBlockRegistry;
import net.fabricmc.fabric.api.registry.VibrationFrequencyRegistry;
import net.fabricmc.fabric.api.registry.VillagerInteractionRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 内容关联注册门面（{@code fabric-content-registries-v0}）：把「这个物品/方块属于什么」告诉游戏。
 *
 * <p>这些都是**纯数据关联**，不新增内容，所以必须在 {@code onInitialize} 里调用；
 * 要关联的物品/方块必须已经注册好（先 {@code Registration.registerXxx} 再调这里）。
 * 写错 id 会直接抛 {@code IllegalArgumentException}。
 *
 * <pre>
 * PyContentRegistries.compost("jython_language_runtime:ruby", 0.3)          # 可堆肥，30% 提升堆肥层
 * PyContentRegistries.flammable("jython_language_runtime:ruby_block", 5, 5)  # 易燃：引燃 5 / 蔓延 5
 * PyContentRegistries.fuel("jython_language_runtime:ruby", 1600)             # 作燃料，烧 1600 tick（8 个物品）
 * PyContentRegistries.strippable("jython_language_runtime:ruby_block", "jython_language_runtime:metallurgic_infuser")  # 斧子去皮 → 目标方块
 * PyContentRegistries.tillable("jython_language_runtime:ruby_block", "minecraft:farmland")            # 锄头耕地 → 目标方块
 * PyContentRegistries.flattenable("jython_language_runtime:ruby_block", "minecraft:dirt_path")        # 踩平 → 目标方块
 * PyContentRegistries.oxidizable("jython_language_runtime:ruby_block", "minecraft:copper_block")      # 氧化下一阶段
 * PyContentRegistries.waxable("jython_language_runtime:ruby_block", "minecraft:copper_block")         # 涂蜡
 * PyContentRegistries.vibration("minecraft:block_place", 8)                          # 振动频率
 * PyContentRegistries.villagerCompostable("jython_language_runtime:ruby")                             # 村民可堆肥
 * PyContentRegistries.villagerFood("jython_language_runtime:ruby", 2)                                 # 村民食物 +2
 * PyContentRegistries.villagerGift("minecraft:fletcher", "minecraft:gameplay/hero_of_the_village/fletcher_gift")
 * </pre>
 *
 * <p>「村民可拾取」不在这里：Fabric 已弃用对应方法，改为数据驱动的
 * {@code minecraft:villager_picks_up} 物品标签，走 tag JSON。
 */
public final class PyContentRegistries {
    private PyContentRegistries() {
    }

    /** 可堆肥；{@code chance} 是每次提升堆肥层的概率（原版树叶为 0.3，蛋糕为 1.0）。 */
    public static void compost(String itemId, float chance) {
        CompostableRegistry.INSTANCE.add(item(itemId), chance);
    }

    /** 易燃：{@code burnChance} 引燃几率、{@code spreadChance} 蔓延几率，都是 0~100 的整数。 */
    public static void flammable(String blockId, int burnChance, int spreadChance) {
        FlammableBlockRegistry.getDefaultInstance().add(block(blockId), burnChance, spreadChance);
    }

    /** 作燃料；{@code ticks} 是燃烧时长（原版煤炭 1600、木棍 100）。 */
    public static void fuel(String itemId, int ticks) {
        Item resolved = item(itemId);
        FuelValueEvents.BUILD.register((builder, context) -> builder.add(resolved, ticks));
    }

    /** 斧子去皮：{@code fromBlockId} 被去皮成 {@code toBlockId}。 */
    public static void strippable(String fromBlockId, String toBlockId) {
        StrippableBlockRegistry.register(block(fromBlockId), block(toBlockId));
    }

    /** 锄头耕地：{@code fromBlockId} 被锄成 {@code toBlockId} 的默认状态（如 {@code minecraft:farmland}）。 */
    public static void tillable(String fromBlockId, String toBlockId) {
        TillableBlockRegistry.register(block(fromBlockId), context -> true, block(toBlockId).defaultBlockState());
    }

    /** 铲子踩平：{@code fromBlockId} 被铲成 {@code toBlockId} 的默认状态（如 {@code minecraft:dirt_path}）。 */
    public static void flattenable(String fromBlockId, String toBlockId) {
        FlattenableBlockRegistry.register(block(fromBlockId), block(toBlockId).defaultBlockState());
    }

    /** 氧化链条：{@code fromBlockId} 随机刻氧化成 {@code toBlockId}。 */
    public static void oxidizable(String fromBlockId, String toBlockId) {
        OxidizableBlocksRegistry.registerNextStage(block(fromBlockId), block(toBlockId));
    }

    /** 涂蜡：蜂鸣器/铜块类方块的涂蜡映射，{@code fromBlockId} → {@code toBlockId}。 */
    public static void waxable(String fromBlockId, String toBlockId) {
        OxidizableBlocksRegistry.registerWaxable(block(fromBlockId), block(toBlockId));
    }

    /** 振动频率（1~15）；{@code gameEventId} 如 {@code minecraft:block_place}。 */
    public static void vibration(String gameEventId, int frequency) {
        VibrationFrequencyRegistry.register(
                ResourceKey.create(Registries.GAME_EVENT, ModIds.parse(gameEventId)), frequency);
    }

    /** 村民可堆肥。 */
    public static void villagerCompostable(String itemId) {
        VillagerInteractionRegistries.registerCompostable(item(itemId));
    }

    /** 村民食物，{@code value} 是回复的食物值。 */
    public static void villagerFood(String itemId, int value) {
        VillagerInteractionRegistries.registerFood(item(itemId), value);
    }

    /** 村民职业的礼物战利品表；{@code professionId} 如 {@code minecraft:fletcher}。 */
    public static void villagerGift(String professionId, String lootTableId) {
        VillagerInteractionRegistries.registerGiftLootTable(
                ResourceKey.create(Registries.VILLAGER_PROFESSION, ModIds.parse(professionId)),
                ResourceKey.create(Registries.LOOT_TABLE, ModIds.parse(lootTableId)));
    }

    private static Item item(String itemId) {
        Identifier key = ModIds.parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            throw new IllegalArgumentException("未知物品: " + itemId);
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }

    private static Block block(String blockId) {
        Identifier key = ModIds.parse(blockId);
        if (!BuiltInRegistries.BLOCK.containsKey(key)) {
            throw new IllegalArgumentException("未知方块: " + blockId);
        }
        return BuiltInRegistries.BLOCK.getValue(key);
    }
}
