package com.AlerCello86767.jython_language_runtime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PySequence;
import org.python.core.PyString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/**
 * 指令门面：把 Brigadier 收进 Java，Python 只写「字面量 / 参数 / 执行体 / 补全 / 权限」。
 *
 * <pre>
 * def _register_commands(dispatcher, buildContext, selection):
 *     PyCommands.attach(dispatcher, PyCommands.literal("pytest")
 *         .then(PyCommands.literal("info").executes(lambda ctx: _cmd_info(ctx)))
 *         .then(PyCommands.arg("amount", "int", {"min": 1, "max": 64})
 *             .suggests(lambda ctx, builder: ["1", "10", "64"])
 *             .executes(lambda ctx: _cmd_amount(ctx)))
 *         .requires(lambda source: source.hasPermission(2)))
 * </pre>
 *
 * <p>补全回调 {@code (ctx, builder)} 有两种写法：
 * <ul>
 *   <li><b>返回候选列表</b>（推荐）：门面按当前已输入前缀自动过滤并提交，写成
 *       {@code lambda ctx, b: ["a", "b"]} 或配合 {@link PySuggestions} 取全量候选</li>
 *   <li><b>自己调 {@code builder.suggest(...)}</b>：返回 {@code None} 即按此处理</li>
 * </ul>
 *
 * <p>异常安全：执行体 / 补全 / 权限判定里的 Python 异常都会被捕获并记日志——执行体失败返回 0
 * （原版标红），补全失败返回空候选（**不会**打崩客户端补全），权限判定失败视为不通过。
 */
public final class PyCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyCommands");

    private PyCommands() {
    }

    /** 字面量子节点：{@code literal("info")}。 */
    public static Node literal(String name) {
        return new Node(Commands.literal(name));
    }

    /** 参数节点，不带范围：{@code arg("name", "word")}。 */
    public static Node arg(String name, String type) {
        return arg(name, type, null);
    }

    /**
     * 参数节点。
     *
     * <p>{@code type} 取值：{@code word} / {@code string} / {@code greedy} / {@code int} /
     * {@code double} / {@code float} / {@code bool} / {@code player} / {@code players} /
     * {@code entity} / {@code entities}。
     *
     * <p>{@code extra} 只对数值类型有意义，是 {@code {"min": .., "max": ..}} 形式的 Map。
     */
    public static Node arg(String name, String type, PyObject extra) {
        return new Node(Commands.argument(name, argumentTypeOf(type, extra)));
    }

    /** 把节点挂到 dispatcher 上（等价于 {@code dispatcher.register(node.build())}）。 */
    public static void attach(PyObject dispatcherArg, Node node) {
        Object raw = dispatcherArg.__tojava__(Object.class);
        if (!(raw instanceof CommandDispatcher<?> dispatcher)) {
            throw new IllegalArgumentException("PyCommands.attach expects a Brigadier CommandDispatcher, got: "
                    + raw);
        }
        // 本版 Brigadier 的 CommandDispatcher.register 只接受字面量构建器（不是 build 后的节点）
        if (!(node.builder instanceof LiteralArgumentBuilder<?> literal)) {
            throw new IllegalArgumentException(
                    "顶层指令必须是字面量：用 PyCommands.literal(\"名字\") 作为根节点");
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> typed = (CommandDispatcher<CommandSourceStack>) dispatcher;
        typed.register((LiteralArgumentBuilder<CommandSourceStack>) literal);
    }

    private static ArgumentType<?> argumentTypeOf(String type, PyObject extra) {
        switch (type) {
            case "word":
                return StringArgumentType.word();
            case "string":
                return StringArgumentType.string();
            case "greedy":
            case "greedyString":
                return StringArgumentType.greedyString();
            case "bool":
            case "boolean":
                return BoolArgumentType.bool();
            case "int":
            case "integer":
                return extra == null ? IntegerArgumentType.integer()
                        : IntegerArgumentType.integer(intBound(extra, "min", Integer.MIN_VALUE),
                                intBound(extra, "max", Integer.MAX_VALUE));
            case "double":
                return extra == null ? DoubleArgumentType.doubleArg()
                        : DoubleArgumentType.doubleArg(bound(extra, "min", -Double.MAX_VALUE),
                                bound(extra, "max", Double.MAX_VALUE));
            case "float":
                return extra == null ? FloatArgumentType.floatArg()
                        : FloatArgumentType.floatArg((float) bound(extra, "min", -Float.MAX_VALUE),
                                (float) bound(extra, "max", Float.MAX_VALUE));
            case "player":
                return EntityArgument.player();
            case "players":
                return EntityArgument.players();
            case "entity":
                return EntityArgument.entity();
            case "entities":
                return EntityArgument.entities();
            default:
                throw new IllegalArgumentException("Unsupported argument type: " + type
                        + " (expected word/string/greedy/int/double/float/bool/player/players/entity/entities)");
        }
    }

    /** 从 {@code {"min": .., "max": ..}} 里取数值，缺省用 fallback。 */
    private static double bound(PyObject extra, String key, double fallback) {
        Object raw = extra.__tojava__(Object.class);
        if (raw instanceof Map<?, ?> map && map.get(key) instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private static int intBound(PyObject extra, String key, int fallback) {
        return (int) bound(extra, key, fallback);
    }

    /** 供 Python 链式调用的节点包装。 */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static final class Node {
        private final ArgumentBuilder builder;

        Node(ArgumentBuilder builder) {
            this.builder = builder;
        }

        /** 挂子节点：{@code then(PyCommands.literal("info"))}。 */
        public Node then(Node child) {
            builder.then(child.builder);
            return this;
        }

        /** 批量挂子节点：{@code then([node1, node2])}。 */
        public Node then(PyObject children) {
            if (children instanceof PySequence sequence && !(children instanceof PyString)) {
                int length = sequence.__len__();
                for (int i = 0; i < length; i++) {
                    then(requireNode(sequence.__finditem__(i)));
                }
            } else {
                then(requireNode(children));
            }
            return this;
        }

        /** 执行体：{@code executes(lambda ctx: ...)}，返回 int（0 表示失败），None 视为成功。 */
        public Node executes(PyObject fn) {
            builder.executes((Command<CommandSourceStack>) context -> run(fn, context));
            return this;
        }

        /** 补全：只能用在 {@code arg} 节点上，{@code suggests(lambda ctx, builder: [...])}。 */
        public Node suggests(PyObject fn) {
            if (!(builder instanceof RequiredArgumentBuilder required)) {
                throw new IllegalArgumentException(
                        "suggests() 只能用在参数节点（PyCommands.arg）上，literal 节点不支持补全");
            }
            required.suggests((context, builder) -> suggest(fn, context, builder));
            return this;
        }

        /** 权限/条件：{@code requires(lambda source: source.hasPermission(2))}。 */
        public Node requires(PyObject fn) {
            builder.requires((Predicate<CommandSourceStack>) source -> permit(fn, source));
            return this;
        }

        /** 取出底层 Brigadier 构建器，供 {@code dispatcher.register(node.build())} 使用。 */
        public Object build() {
            return builder;
        }

        private static Node requireNode(PyObject raw) {
            Object java = raw.__tojava__(Object.class);
            if (java instanceof Node node) {
                return node;
            }
            throw new IllegalArgumentException("Expected a PyCommands node, got: " + java);
        }
    }

    private static int run(PyObject fn, CommandContext<CommandSourceStack> context) {
        try {
            PyObject result = fn.__call__(Py.java2py(context));
            if (result == null || result == Py.None) {
                return 1;
            }
            return result.asInt();
        } catch (Throwable error) {
            LOGGER.error("Python command failed", error);
            context.getSource().sendFailure(Component.literal("Command error, see the log: " + error));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggest(PyObject fn, CommandContext<CommandSourceStack> context,
                                                          SuggestionsBuilder builder) {
        try {
            PyObject result = fn.__call__(Py.java2py(context), Py.java2py(builder));
            if (result instanceof PySequence sequence && !(result instanceof PyString)) {
                String remaining = builder.getRemainingLowerCase();
                int length = sequence.__len__();
                for (int i = 0; i < length; i++) {
                    String candidate = sequence.__finditem__(i).toString();
                    if (remaining.isEmpty() || candidate.toLowerCase().startsWith(remaining)) {
                        builder.suggest(candidate);
                    }
                }
            } else if (result instanceof PyString text) {
                builder.suggest(text.toString());
            }
        } catch (Throwable error) {
            // 补全失败只能少几个候选，绝不能打断客户端的补全请求
            LOGGER.error("Python suggestion provider failed", error);
        }
        return builder.buildFuture();
    }

    private static boolean permit(PyObject fn, CommandSourceStack source) {
        try {
            return Py.py2boolean(fn.__call__(Py.java2py(source)));
        } catch (Throwable error) {
            // requires 跑在权限判定路径上，异常一律按「不通过」处理
            LOGGER.error("Python command requirement failed", error);
            return false;
        }
    }
}
