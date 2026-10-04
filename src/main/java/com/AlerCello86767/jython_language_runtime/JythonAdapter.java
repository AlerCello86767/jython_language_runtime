package com.AlerCello86767.jython_language_runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.python.core.Py;
import org.python.core.PyException;
import org.python.core.PyObject;
import org.python.core.PyStringMap;
import org.python.core.PySystemState;
import org.python.util.PythonInterpreter;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric 语言适配器：把 Python（Jython）脚本里的类加载为入口点实例。
 *
 * <p>{@code fabric.mod.json} 中以 {@code "adapter": "jython"} 声明的入口，其 value
 * （全限定类名）会被映射为模组根目录下同名的 {@code .py} 文件，例如：
 * {@code com.AlerCello86767.jython_language_runtime.Jython_language_runtime -> com/AlerCello86767/jython_language_runtime/Jython_language_runtime.py}。
 *
 * <p>脚本中定义同名新式类（如 {@code class Jython_language_runtime(object)}），并实现对应入口接口要求
 * 的方法（如 {@code onInitialize}）。Python 类不直接继承 Java 接口：适配器通过 JDK
 * 动态代理完成桥接，这样在开发环境下也不会受 Knot 类加载拓扑影响。三个入口点共用一个
 * 解释器实例。
 */
public class JythonAdapter implements LanguageAdapter {
    private static volatile PythonInterpreter interpreter;

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        String relativePath = value.replace('.', '/') + ".py";
        // findPath 会遍历模组的全部根路径（dev 下 classes/resources 是两个根，生产环境是 jar）
        Path scriptPath = mod.findPath(relativePath).orElse(null);

        if (scriptPath == null || !Files.isRegularFile(scriptPath)) {
            throw new LanguageAdapterException("Python script not found for entry '" + value
                    + "' (expected " + relativePath + " in mod root)");
        }

        String source;
        try (InputStream in = Files.newInputStream(scriptPath)) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LanguageAdapterException("Failed to read Python script: " + relativePath, e);
        }

        String simpleName = value.substring(value.lastIndexOf('.') + 1);
        // 该入口所属模组的命名空间：入口脚本执行与入口方法（onInitialize 等）调用期间，
        // 省略命名空间的 id 都会补全成它，见 core/ModIds
        String namespace = mod.getMetadata().getId();
        ClassLoader knotLoader = JythonAdapter.class.getClassLoader();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(knotLoader);
        ModIds.pushNamespace(namespace);
        try {
            PythonInterpreter py = getInterpreter();
            // 把该模组的根路径加入 sys.path：入口脚本才能 import 自己拆出的模块
            addModToSysPath(mod, py);

            // 每个入口点使用独立的全局命名空间，互不污染
            PyStringMap globals = new PyStringMap();
            py.setLocals(globals);
            try {
                py.exec(source);
            } catch (PyException e) {
                throw new LanguageAdapterException("Error while executing Python script: " + relativePath
                        + " (" + e.getMessage() + ")", e);
            }

            PyObject pyClass = py.get(simpleName);
            if (pyClass == null) {
                throw new LanguageAdapterException("Python script " + relativePath
                        + " does not define a class named '" + simpleName + "'");
            }

            PyObject instance;
            try {
                instance = pyClass.__call__();
            } catch (PyException e) {
                throw new LanguageAdapterException("Failed to instantiate Python class '" + value + "'"
                        + " (" + e.getMessage() + ")", e);
            }

            // JDK 动态代理把 Python 方法桥接为目标入口接口（接口类来自 Knot）。
            // 入口方法由 Fabric 在 create 返回之后调用，因此命名空间在 handler 里逐次推入。
            Object proxy = Proxy.newProxyInstance(type.getClassLoader(),
                    new Class<?>[] { type }, new PyObjectHandler(instance, namespace));
            if (!type.isInstance(proxy))
                throw new LanguageAdapterException("Internal error: proxy not instance of " + type);
            return type.cast(proxy);
        } finally {
            ModIds.popNamespace();
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    /** 已加入 Jython sys.path 的路径，避免每个入口点重复追加（共享解释器，全局生效）。 */
    private static final Set<String> SYS_PATH_ADDED = ConcurrentHashMap.newKeySet();

    /**
     * 把模组的根路径加入 Jython 的 {@code sys.path}，使入口脚本能 import 自己拆出的模块。
     *
     * <p>{@code .py} 通过 {@code resources.srcDir 'src/main/python'} 打成资源，落在模组根下，
     * 因此模组根就是 Python 的模块搜索根。Java 包导入走 classpath，不受此影响。
     */
    private static void addModToSysPath(ModContainer mod, PythonInterpreter py) {
        for (Path root : mod.getRootPaths()) {
            String path = root.toAbsolutePath().toString();
            if (SYS_PATH_ADDED.add(path)) {
                py.getSystemState().path.append(Py.newString(path));
            }
        }
    }

    /**
     * 把接口方法调用转发给 Python 对象。
     */
    private static final class PyObjectHandler implements InvocationHandler {
        private final PyObject target;
        private final String namespace;

        PyObjectHandler(PyObject target, String namespace) {
            this.target = target;
            this.namespace = namespace;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // java.lang.Object 方法单独处理
            if (method.getDeclaringClass() == Object.class) {
                switch (name) {
                case "toString":
                    return target.__str__().toString();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args.length == 1 && proxy == args[0];
                default:
                    return null;
                }
            }

            // 入口方法（onInitialize / onInitializeClient / onInitializeDataGenerator）在此调用，
            // 短名 id 依赖这里的命名空间上下文
            ModIds.pushNamespace(namespace);
            try {
                // 代理不会自动执行接口默认方法。Python 未实现该方法、而接口方法是 default 时，
                // 回退到默认实现（如 DataGeneratorEntrypoint.getEffectiveModId）；
                // 非 default 的缺失方法仍由 target.invoke 抛出 AttributeError
                if (target.__findattr__(name) == null && method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                PyObject[] pyArgs = args == null ? new PyObject[0] : Py.javas2pys(args);
                PyObject result = target.invoke(name, pyArgs);

                Class<?> returnType = method.getReturnType();
                if (returnType == void.class) {
                    return null;
                }
                Object converted = result.__tojava__(returnType);
                return converted == Py.NoConversion ? null : converted;
            } finally {
                ModIds.popNamespace();
            }
        }
    }

    private static PythonInterpreter getInterpreter() {
        PythonInterpreter local = interpreter;
        if (local != null) {
            return local;
        }
        synchronized (JythonAdapter.class) {
            if (interpreter == null) {
                Properties props = new Properties();
                // 跳过 Java 包扫描缓存：省掉启动期扫描 jar 的开销，也避免写只读的 jar-in-jar 位置。
                // 注意：该属性管的是「包扫描缓存」，不是 $py.class 的位置（详见 docs/internals/performance.md 的 H10）
                props.setProperty("python.cachedir.skip", "true");
                // 跳过 site 导入：启动更快，也避免在模组环境里找不到 site.py
                props.setProperty("python.import.site", "false");
                PySystemState.initialize(props, null);
                interpreter = new PythonInterpreter();
                // 强制所有 Java 导入走 Knot，保证第三方类与适配器看到的类型一致
                interpreter.getSystemState().setClassLoader(JythonAdapter.class.getClassLoader());
                // 预热标准库：首次「按源码导入」会走 imp/warnings 等机制，而 Jython 2.7.5b1
                // 在 warnings 尚未初始化完成时被 Py.warning 访问 .warn 会抛 AttributeError。
                // 在加入任何 mod 的 sys.path 之前先加载它们，规避该时序问题。
                interpreter.exec("import sys, os, linecache, warnings, imp, traceback");
            }
            return interpreter;
        }
    }
}
