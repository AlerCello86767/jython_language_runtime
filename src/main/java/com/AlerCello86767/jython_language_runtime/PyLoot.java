package com.AlerCello86767.jython_language_runtime;

import java.util.ArrayList;
import java.util.List;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

/**
 * 战利品表门面（{@code fabric-loot-api-v3}）：让 Python 往已有战利品表里加/改掉落。
 *
 * <pre>
 * PyLoot.addDrop("minecraft:blocks/diamond_ore", "jython_language_runtime:ruby", 1, 2)          # 必掉 1~2 个
 * PyLoot.addDrop("minecraft:entities/zombie", "jython_language_runtime:ruby", 1, 1, 0.25)       # 25% 掉 1 个
 * PyLoot.onModifyDrops(fn)   # fn(tableId, drops)：drops 是 List[ItemStack]，可随意增删改
 * PyLoot.stack("jython_language_runtime:ruby", 3)   # 造一个 ItemStack 给上面用
 * </pre>
 *
 * <p>{@link #addDrop} 是「追加」语义：保留原表内容，另开一个池子加掉落。表 id 写法与资源路径一致
 * （{@code minecraft:blocks/stone}、{@code minecraft:entities/zombie}），不是方块/物品 id——用
 * {@code /loot} 命令或 {@code minecraft:blocks/...} 目录确认。
 *
 * <p>所有表 id / 物品 id 在调用时就校验，写错了会在初始化阶段直接抛错。
 */
public final class PyLoot {
    private PyLoot() {
    }

    /** 一条「追加掉落」规则。 */
    private record Drop(Identifier tableId, Item item, int minCount, int maxCount, float chance) {
    }

    private static final List<Drop> DROPS = new ArrayList<>();
    private static boolean modifyInstalled;

    /** {@code (tableId, drops) -> void}；{@code tableId} 取不到时（动态表）为 null。 */
    public interface ModifyDropsHandler {
        void handle(String tableId, List<ItemStack> drops);
    }

    /**
     * 往指定战利品表追加一条掉落。
     *
     * @param tableId   战利品表 id，如 {@code minecraft:blocks/diamond_ore}
     * @param itemId    掉落物 id
     * @param minCount  最少数量
     * @param maxCount  最多次数（等于 minCount 即固定数量）
     * @param chance    掉落概率 0~1，1 表示必掉
     */
    public static void addDrop(String tableId, String itemId, int minCount, int maxCount, float chance) {
        if (minCount < 1 || maxCount < minCount) {
            throw new IllegalArgumentException("数量区间不合法: " + minCount + "~" + maxCount);
        }
        if (chance <= 0.0f || chance > 1.0f) {
            throw new IllegalArgumentException("概率必须在 (0, 1] 内: " + chance);
        }
        DROPS.add(new Drop(ModIds.parse(tableId), item(itemId), minCount, maxCount, chance));
        installModify();
    }

    /**
     * 掉落**结算后**回调，可以直接改这一轮的掉落结果（清空、替换、追加）。
     *
     * <p>{@code drops} 就是即将生成的 {@code List<ItemStack>}，原地修改即可；配 {@link #stack} 造物品。
     */
    public static void onModifyDrops(ModifyDropsHandler handler) {
        LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> handler.handle(
                table.unwrapKey().map(key -> key.identifier().toString()).orElse(null), drops));
    }

    /**
     * 供 Python 构造掉落物：{@code PyLoot.stack("jython_language_runtime:ruby", 2)}。
     *
     * <p><b>只能在运行期调用</b>（战利品回调、机器产出、指令等）。注册期（{@code onInitialize}）
     * 物品 holder 的组件还没绑定，构造 {@code ItemStack} 会抛
     * {@code NullPointerException: Components not bound yet}——这是原版的加载顺序，
     * 不是本运行时的限制。要在注册期描述「物品 + 数量」，请用注册门面的声明式参数（如
     * {@code getDrops}、配方 JSON）。
     */
    public static ItemStack stack(String itemId, int count) {
        return new ItemStack(item(itemId), count);
    }

    /** 注册「追加掉落」的总回调——只在第一次 {@link #addDrop} 时装一次，之后靠规则列表匹配。 */
    private static void installModify() {
        if (modifyInstalled) {
            return;
        }
        modifyInstalled = true;
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            for (Drop drop : DROPS) {
                if (!drop.tableId().equals(key.identifier())) {
                    continue;
                }
                LootPoolSingletonContainer.Builder<?> entry = LootItem.lootTableItem(drop.item());
                entry.apply(SetItemCountFunction.setCount(
                        UniformGenerator.between(drop.minCount(), drop.maxCount())));
                LootPool.Builder pool = LootPool.lootPool().add(entry);
                if (drop.chance() < 1.0f) {
                    pool.when(LootItemRandomChanceCondition.randomChance(drop.chance()));
                }
                builder.withPool(pool);
            }
        });
    }

    private static Item item(String itemId) {
        Identifier key = ModIds.parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            throw new IllegalArgumentException("未知物品: " + itemId);
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }
}
