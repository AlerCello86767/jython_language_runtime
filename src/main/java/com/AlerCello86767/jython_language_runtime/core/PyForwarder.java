package com.AlerCello86767.jython_language_runtime.core;

import org.python.core.Py;
import org.python.core.PyObject;

/**
 * Python 调用内核：宿主方法转发 + 可调用对象直呼，主源集与 client 源集共用。
 *
 * <p>约定：Python 侧定义与 Java 方法同名的函数；未实现、或返回 {@code None} 时视为「未处理」，
 * 由调用方回退 super。返回值必须是 Java 类型，经 {@link PyObject#__tojava__(Class)} 转换，
 * 无法转换（{@link Py#NoConversion}）按未处理对待。
 */
public final class PyForwarder {
    private PyForwarder() {
    }

    /**
     * 调用 Python 对象的同名方法并把返回值转换为 {@code type}。
     *
     * @return Python 未实现该方法、或返回 None 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T forward(PyObject target, String name, Class<T> type, Object... args) {
        PyObject method = target.__findattr__(name);
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
     * void 型方法转发：Python 实现了该方法就执行。
     *
     * @return 是否已由 Python 处理
     */
    public static boolean call(PyObject target, String name, Object... args) {
        PyObject method = target.__findattr__(name);
        if (method == null) {
            return false;
        }
        method.__call__(Py.javas2pys(args));
        return true;
    }

    /** 直接调用一个 Python 可调用对象（门面持有 Python 函数的场景，如网络接收函数）。 */
    public static void invoke(PyObject callable, Object... args) {
        callable.__call__(Py.javas2pys(args));
    }
}
