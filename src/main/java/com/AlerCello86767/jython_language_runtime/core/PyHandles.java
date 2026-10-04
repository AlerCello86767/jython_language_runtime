package com.AlerCello86767.jython_language_runtime.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.python.core.Py;
import org.python.core.PyObject;

/**
 * 每个 Python 行为对象的**方法句柄缓存**（性能优化 H1/H3）。
 *
 * <p>为什么需要它：{@link PyForwarder} 每次转发都做一次 {@code __findattr__} 属性查找。
 * 对于每 tick / 每帧都会走的高频钩子（方块实体 {@code tick}、物品 {@code inventoryTick}、
 * 实体渲染 {@code render} …），这次查找是纯固定开销。本类把「名字 → 方法句柄」的结果
 * 缓存下来，命中后只剩一次 map 查找；「未实现」也缓存成哨兵，从而**未实现的方法不会再跨界**。
 *
 * <p>约定与生命周期：一个 {@code PyHandles} 对应一个固定的 Python 行为对象，由宿主在构造期创建，
 * 因此缓存生命周期与宿主一致。**行期不得动态增删方法**（行为类的方法集在注册前就固定），
 * 否则缓存会过期。
 *
 * <p>线程安全：内部用 {@link ConcurrentHashMap}，允许宿主在多线程下被调用。
 */
public final class PyHandles {
    /** 哨兵：表示「Python 侧未实现该方法」。ConcurrentHashMap 不允许 null 值，故用哨兵占位。 */
    private static final Object ABSENT = new Object();

    private final PyObject target;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    public PyHandles(PyObject target) {
        this.target = target;
    }

    /**
     * 该对象是否实现了给定名字中的任意一个方法（一次性探测，不缓存）。
     *
     * <p>供宿主在**注册期**判断「这个行为类到底有没有钩子」：一个钩子都没有时就不必为每个
     * 游戏对象创建 Python 实例（性能优化 H11）。
     */
    public static boolean implementsAny(PyObject target, String... names) {
        for (String name : names) {
            if (target.__findattr__(name) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 预先解析并缓存若干方法名。
     *
     * <p>构造期调用可把「首次探测」的成本移出热路径（H3：物品宿主一次预扫描全部可重写方法）。
     */
    public void preload(String... names) {
        for (String name : names) {
            resolve(name);
        }
    }

    /** 该方法是否由 Python 实现（结果缓存，未实现也只探测一次）。 */
    public boolean has(String name) {
        return resolve(name) != null;
    }

    /** 取已解析的方法句柄；未实现返回 {@code null}。 */
    public PyObject method(String name) {
        return resolve(name);
    }

    /**
     * 调用同名方法并把返回值转换为 {@code type}。
     *
     * @return Python 未实现该方法、或返回 {@code None} 时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T forward(String name, Class<T> type, Object... args) {
        PyObject method = resolve(name);
        if (method == null) {
            return null;
        }
        PyObject result = method.__call__(Py.javas2pys(args));
        if (result == null) {
            return null;
        }
        Object converted = result.__tojava__(type);
        return converted == Py.NoConversion ? null : (T) converted;
    }

    /**
     * void 型方法调用。
     *
     * @return 是否已由 Python 处理（未实现返回 {@code false}，调用方据此回退 super）
     */
    public boolean call(String name, Object... args) {
        PyObject method = resolve(name);
        if (method == null) {
            return false;
        }
        method.__call__(Py.javas2pys(args));
        return true;
    }

    private PyObject resolve(String name) {
        Object cached = cache.get(name);
        if (cached == null) {
            PyObject method = target.__findattr__(name);
            cached = method == null ? ABSENT : method;
            cache.put(name, cached);
        }
        return cached == ABSENT ? null : (PyObject) cached;
    }
}
