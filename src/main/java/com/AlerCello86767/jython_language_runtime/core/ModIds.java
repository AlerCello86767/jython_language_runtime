package com.AlerCello86767.jython_language_runtime.core;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.resources.Identifier;

/**
 * id 工具：path 校验、命名空间上下文与 id 解析。
 *
 * <p><b>命名空间归属：</b>本运行时可以被其他模组依赖。依赖方在入口脚本（onInitialize /
 * onInitializeClient / onInitializeDataGenerator）里可以写短名 id（如 {@code "ruby"}），
 * 由「当前命名空间」上下文补全为 {@code "<依赖方 modid>:ruby"}；适配器负责在入口脚本执行
 * 与入口方法调用期间维护该上下文。
 *
 * <p><b>运行期回调里必须写全限定 id</b>（如 {@code "mymod:ruby"}）——命令、事件回调、
 * 网络接收等都发生在入口方法之外，此时没有命名空间上下文，短名会直接抛错。
 * 这样设计是为了避免依赖方内容被静默注册到运行时的命名空间下。
 */
public final class ModIds {
    /** path 合法字符集：小写字母、数字、下划线、斜杠。 */
    private static final String VALID_PATH = "[a-z0-9_/]+";

    /**
     * 当前正在初始化的模组命名空间栈：适配器在入口脚本执行与入口方法调用前后 push/pop。
     * 用栈而非单值，容忍入口方法内部再触发嵌套调用。
     */
    private static final ThreadLocal<Deque<String>> NAMESPACES =
            ThreadLocal.withInitial(ArrayDeque::new);

    private ModIds() {
    }

    /** 推入当前命名空间，与 {@link #popNamespace()} 配对（适配器内部使用）。 */
    public static void pushNamespace(String namespace) {
        NAMESPACES.get().push(namespace);
    }

    /** 弹出当前命名空间，与 {@link #pushNamespace(String)} 配对。 */
    public static void popNamespace() {
        Deque<String> stack = NAMESPACES.get();
        if (stack.isEmpty()) {
            throw new IllegalStateException("命名空间上下文栈为空，无法弹出");
        }
        stack.pop();
    }

    /**
     * 当前命名空间。
     *
     * @throws IllegalStateException 不在任何模组的初始化上下文中——此时不能用省略命名空间的 id
     */
    public static String currentNamespace() {
        Deque<String> stack = NAMESPACES.get();
        if (stack.isEmpty()) {
            throw new IllegalStateException(
                    "此处没有命名空间上下文：省略命名空间的 id 只在入口方法"
                    + "（onInitialize / onInitializeClient）内可用；回调里请写全限定 id，"
                    + "形如 \"mymod:ruby\"");
        }
        return stack.peek();
    }

    /** 当前命名空间下的 id；path 非法时快速失败。 */
    public static Identifier of(String path) {
        if (path == null || !path.matches(VALID_PATH)) {
            throw new IllegalArgumentException("Invalid registry path: " + path);
        }
        return Identifier.fromNamespaceAndPath(currentNamespace(), path);
    }

    /** 解析 id：{@code "ns:path"}；省略命名空间时用当前命名空间（需上下文）。 */
    public static Identifier parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Empty registry id");
        }
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return Identifier.fromNamespaceAndPath(currentNamespace(), raw);
        }
        return Identifier.fromNamespaceAndPath(raw.substring(0, colon), raw.substring(colon + 1));
    }
}
