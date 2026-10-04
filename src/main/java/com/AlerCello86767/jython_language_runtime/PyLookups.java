package com.AlerCello86767.jython_language_runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.python.core.PyObject;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * 能力查询门面（{@code fabric-api-lookup-api-v1}）。
 *
 * <p><b>这个模块是「能力框架」，不是功能本身。</b>它的作用是让各方言（方块 / 物品 / 实体）声明
 * 「我提供某个能力接口」，别人再按接口去查。项目里已经在用的有两处：
 * 方块实体的物品栏（{@code Registration.registerBlock} 里挂的 {@code ItemStorage.SIDED}）、
 * 以及容器物品（潜影盒这类，走 {@code ItemStorage.ITEM}）。
 *
 * <p><b>Python 侧只能「查」，不能「注册」</b>：注册新能力需要一个 Java 接口作为键
 * （{@code BlockApiLookup.get(id, ApiClass, ContextClass)}），而 Python 类不实现 Java 接口
 * ——这是本项目的根本约束（见 SKILL）。所以这里只暴露查询，能注册的部分已经在
 * {@code Registration} 里自动做了，Python 不需要也不应该碰。
 *
 * <pre>
 * PyLookups.hasBlockItemStorage(level, pos, "up")   # 这个方块朝上的面是不是容器
 * PyLookups.blockItemStorageSides(level, pos)       # 哪几个面暴露物品存储，如 ["none","up","down"]
 * PyLookups.hasItemStorage(itemStack)               # 这个物品是不是自带物品栏（潜影盒等）
 * </pre>
 */
public final class PyLookups {
    private PyLookups() {
    }

    /**
     * 该位置朝 {@code direction} 的面是否暴露物品存储。
     *
     * <p>{@code direction} 取 {@code "up"/"down"/"north"/"south"/"west"/"east"}，
     * 或 {@code None} 表示「无面」。漏斗这类方块只在特定面暴露，两种都要试。
     */
    public static boolean hasBlockItemStorage(PyObject levelArg, PyObject posArg, String direction) {
        return PyTransfers.storageAt(levelArg, posArg, direction) != null;
    }

    /** 列出该位置暴露物品存储的面；无面记作 {@code "none"}。全都不暴露时返回空列表。 */
    public static List<String> blockItemStorageSides(PyObject levelArg, PyObject posArg) {
        List<String> sides = new ArrayList<>();
        if (PyTransfers.storageAt(levelArg, posArg, null) != null) {
            sides.add("none");
        }
        for (Direction direction : Direction.values()) {
            String name = direction.name().toLowerCase(Locale.ROOT);
            if (PyTransfers.storageAt(levelArg, posArg, name) != null) {
                sides.add(name);
            }
        }
        return sides;
    }

    /** 该物品是否自带物品栏（潜影盒、背包类物品）；空堆一律返回 {@code False}。 */
    public static boolean hasItemStorage(PyObject stackArg) {
        return itemStorage(stackArg) != null;
    }

    private static Storage<ItemVariant> itemStorage(PyObject stackArg) {
        ItemStack stack = (ItemStack) stackArg.__tojava__(ItemStack.class);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return ItemStorage.ITEM.find(stack, ContainerItemContext.withConstant(stack));
    }
}
