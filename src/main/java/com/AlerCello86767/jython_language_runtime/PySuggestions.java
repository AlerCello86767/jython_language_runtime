package com.AlerCello86767.jython_language_runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.host.PythonBlockEntity;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * 补全工具箱：给 {@code PyCommands.suggests(...)} 用，避免每个 mod 重写「列出所有已注册 id」这类样板。
 *
 * <pre>
 * PyCommands.arg("item", "word").suggests(
 *     lambda ctx, builder: PySuggestions.ids("item"))          # 静态注册表，带缓存
 * PyCommands.arg("enchant", "word").suggests(
 *     lambda ctx, builder: PySuggestions.idsFrom(ctx, "enchantment"))  # 动态注册表，走服务端
 * PyCommands.arg("player", "word").suggests(
 *     lambda ctx, builder: PySuggestions.players(ctx))
 * PyCommands.literal("withdraw").suggests(...)
 *     PySuggestions.containerItems(block_entity)               # 容器里已有的物品
 * </pre>
 *
 * <p>返回的是**全量候选列表**，前缀过滤由 {@code PyCommands} 的补全包装统一完成。
 * 静态注册表在 init 后冻结，因此结果按注册表名缓存；动态注册表（附魔 / 伤害类型等随数据包重载变化）
 * 不做缓存。
 */
public final class PySuggestions {
    /** 静态注册表快照：注册完成后不再变化，按注册表名缓存。 */
    private static final Map<String, List<String>> STATIC_CACHE = new ConcurrentHashMap<>();

    private PySuggestions() {
    }

    /**
     * 静态注册表（{@code BuiltInRegistries}）里的全部 id，如 {@code "item"} / {@code "block"} /
     * {@code "mob_effect"} / {@code "menu"} / {@code "attribute"} / {@code "particle_type"}。
     *
     * <p>结果会缓存——补全每按一次 tab 都会调用，不能每次遍历注册表。
     */
    public static List<String> ids(String registry) {
        return STATIC_CACHE.computeIfAbsent(registry, name -> {
            Identifier key = withDefaultNamespace(name);
            Registry<?> found = BuiltInRegistries.REGISTRY.getValue(key);
            if (found == null) {
                throw new IllegalArgumentException("Unknown static registry: " + name
                        + "（动态注册表如 enchantment / damage_type 请用 PySuggestions.idsFrom(ctx, ...)）");
            }
            return collect(found);
        });
    }

    /**
     * 通过命令上下文取注册表里的全部 id——静态与**动态**注册表都支持（附魔 / 伤害类型随数据包重载，
     * 必须走服务端 {@code registryAccess}）。
     *
     * <p>补全回调里第一个参数 {@code ctx} 原样传进来即可。不缓存（每个数据包生命周期一份）。
     */
    public static List<String> idsFrom(PyObject context, String registry) {
        RegistryAccess access = sourceOf(context).getServer().registryAccess();
        Identifier wanted = withDefaultNamespace(registry);
        for (RegistryAccess.RegistryEntry<?> entry : access.registries().toList()) {
            if (entry.key().identifier().equals(wanted)) {
                return collect(entry.value());
            }
        }
        throw new IllegalArgumentException("Unknown registry: " + registry);
    }

    /** 在线玩家名（取自命令上下文，无需服务端遍历）。 */
    public static List<String> players(PyObject context) {
        List<String> names = new ArrayList<>();
        for (String name : sourceOf(context).getOnlinePlayerNames()) {
            names.add(name);
        }
        return names;
    }

    /** 容器（或带物品栏的方块实体）里**已有**的非空物品 id，去重。 */
    public static List<String> containerItems(PyObject containerArg) {
        Object raw = containerArg.__tojava__(Object.class);
        Container container = raw instanceof PythonBlockEntity blockEntity ? blockEntity.container()
                : (raw instanceof Container javaContainer ? javaContainer : null);
        if (container == null) {
            throw new IllegalArgumentException("PySuggestions.containerItems expects a BlockEntity"
                    + " with a container (or a Container), got: " + raw);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) {
                    ids.add(id.toString());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> collect(Registry<?> registry) {
        List<String> ids = new ArrayList<>(registry.size());
        for (Identifier id : registry.keySet()) {
            ids.add(id.toString());
        }
        return ids;
    }

    private static Identifier withDefaultNamespace(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Empty registry name");
        }
        if (name.indexOf(':') >= 0) {
            Identifier parsed = Identifier.tryParse(name);
            if (parsed == null) {
                throw new IllegalArgumentException("Invalid registry name: " + name);
            }
            return parsed;
        }
        return Identifier.fromNamespaceAndPath("minecraft", name);
    }

    private static CommandSourceStack sourceOf(PyObject contextArg) {
        Object raw = contextArg.__tojava__(Object.class);
        if (raw instanceof CommandContext<?> context && context.getSource() instanceof CommandSourceStack source) {
            return source;
        }
        throw new IllegalArgumentException("PySuggestions 需要一个 Brigadier CommandContext"
                + "（补全回调的第一个参数），实际收到: " + raw);
    }
}
