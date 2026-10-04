package com.AlerCello86767.jython_language_runtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 方块间物品传输门面（{@code fabric-transfer-api-v1}）：让 Python 的机器能和管道、
 * 漏斗、原版容器互相取放物品。
 *
 * <pre>
 * n = PyTransfers.extractAt(level, pos, "up", "minecraft:iron_ingot", 8)   # 抽走，返回实际数量
 * n = PyTransfers.insertAt(level, pos, "down", "jython_language_runtime:ruby", 4)           # 放入
 * n = PyTransfers.move(level, fromPos, "north", level, toPos, "south", None, 64)  # 搬运
 * info = PyTransfers.peekAt(level, pos, "up")   # {"item": "minecraft:iron_ingot", "count": 12} 或 None
 * </pre>
 *
 * <p>{@code direction} 取 {@code "up"/"down"/"north"/"south"/"west"/"east"}，或 {@code None}
 * 表示「无面」（不少方块只认其中一个，两种都要能试）。
 *
 * <p><b>方块实体的物品栏已自动暴露给这套 API</b>（见 {@code Registration.registerBlock}），
 * 管道和漏斗能直接取放，Python 不需要额外声明。本门面用于反向场景：Python 主动去操作别人。
 * 默认暴露为「所有面、所有槽位全开」；要按面收紧（上进料、下出料）用 {@link PyStorage#itemSides}。
 *
 * <p>所有操作都走事务：真正执行才 {@code commit}，探测则 {@code abort}，不会留下中间状态。
 */
public final class PyTransfers {
    private PyTransfers() {
    }

    /** 从 {@code (level, pos, direction)} 的存储抽取指定物品，返回实际取出的数量。 */
    public static int extractAt(PyObject levelArg, PyObject posArg, String direction, String itemId, int count) {
        Storage<ItemVariant> storage = storageAt(levelArg, posArg, direction);
        if (storage == null) {
            return 0;
        }
        ItemVariant variant = variantOf(itemId);
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = storage.extract(variant, count, transaction);
            transaction.commit();
            return (int) moved;
        }
    }

    /** 向 {@code (level, pos, direction)} 的存储放入指定物品，返回实际放入的数量。 */
    public static int insertAt(PyObject levelArg, PyObject posArg, String direction, String itemId, int count) {
        Storage<ItemVariant> storage = storageAt(levelArg, posArg, direction);
        if (storage == null) {
            return 0;
        }
        ItemVariant variant = variantOf(itemId);
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = storage.insert(variant, count, transaction);
            transaction.commit();
            return (int) moved;
        }
    }

    /**
     * 两点之间搬运，返回实际搬运数量——管道 / 机器互推的核心。
     *
     * <p>{@code itemId} 传 {@code None} 表示不限物品（任取可抽取的），{@code limit} 是本次上限。
     */
    public static int move(PyObject fromLevelArg, PyObject fromPosArg, String fromDirection,
                           PyObject toLevelArg, PyObject toPosArg, String toDirection,
                           String itemId, int limit) {
        Storage<ItemVariant> from = storageAt(fromLevelArg, fromPosArg, fromDirection);
        Storage<ItemVariant> to = storageAt(toLevelArg, toPosArg, toDirection);
        if (from == null || to == null) {
            return 0;
        }
        ItemVariant filter = itemId == null ? null : variantOf(itemId);
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = StorageUtil.move(from, to,
                    variant -> filter == null || filter.equals(variant), limit, transaction);
            transaction.commit();
            return (int) moved;
        }
    }

    /**
     * 探测该位置存储里第一个可抽取的物品；返回 {@code {"item": id, "count": n}}，没有则 {@code None}。
     *
     * <p>只读探测，走事务后立即 {@code abort}，不会改动内容。
     */
    public static Map<String, Object> peekAt(PyObject levelArg, PyObject posArg, String direction) {
        Storage<ItemVariant> storage = storageAt(levelArg, posArg, direction);
        if (storage == null) {
            return null;
        }
        try (Transaction transaction = Transaction.openOuter()) {
            Iterator<StorageView<ItemVariant>> views = storage.iterator();
            while (views.hasNext()) {
                StorageView<ItemVariant> view = views.next();
                if (!view.isResourceBlank() && view.getAmount() > 0) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("item", BuiltInRegistries.ITEM.getKey(view.getResource().getItem()).toString());
                    result.put("count", (int) view.getAmount());
                    transaction.abort();
                    return result;
                }
            }
            transaction.abort();
        }
        return null;
    }

    /** 取某个位置某个面的物品存储；该面没有存储时返回 {@code null}（不是错误）。同包门面复用。 */
    static Storage<ItemVariant> storageAt(PyObject levelArg, PyObject posArg, String direction) {
        Level level = (Level) levelArg.__tojava__(Level.class);
        BlockPos pos = (BlockPos) posArg.__tojava__(BlockPos.class);
        if (level == null || pos == null) {
            throw new IllegalArgumentException("PyTransfers 需要 (level, pos) 两个参数");
        }
        return ItemStorage.SIDED.find(level, pos, parseDirection(direction));
    }

    /** {@code null} 表示无面；原版很多方块只在特定面暴露存储，两种都要能试。同包门面复用。 */
    static Direction parseDirection(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Direction.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知方向: " + name
                    + "（可用 up/down/north/south/west/east，或 None）");
        }
    }

    private static ItemVariant variantOf(String itemId) {
        Identifier key = ModIds.parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            throw new IllegalArgumentException("未知物品: " + itemId);
        }
        Item item = BuiltInRegistries.ITEM.getValue(key);
        return ItemVariant.of(item);
    }
}
