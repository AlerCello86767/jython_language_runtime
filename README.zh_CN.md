# jython_language_runtime

面向 Fabric 的 **Jython 语言运行时** —— 用 Python 编写 Minecraft 模组，无需编写一行 Java。

[English](README.md)

> Minecraft **26.1.2** · Fabric Loader **0.19.5** · Fabric API **0.155.3+26.1.2** · JDK **25** · Loom **1.18.2** · Jython **2.7.5b1**（在 JVM 上运行 Python 2.7 语法）

---

## 这是什么

它**不是**内容模组，而是一层**语言运行时**：

- Fabric 的 `LanguageAdapter` 把以 `"adapter": "jython"` 声明的入口点，映射到**你自己的模组 jar** 里的 `.py` 文件；
- 脚本由一个共享的 Jython 解释器执行，并实例化与文件同名的类；
- 通过 JDK 动态代理，把 Java 入口接口桥接到该 Python 对象；
- 注册、事件、网络、GUI 等能力都通过极薄的 Java **门面（facade）** 与 **宿主类（host）** 暴露，Python 侧只处理普通数据（字符串 / 数字 / dict / 函数）。

你只需要写 `class MyMod(object)`，其余交给运行时。

## 必须遵守的规则

1. **Python 类不要继承 Java 接口**，一律 `class X(object)`。（dev 环境下 Knot 的类加载拓扑会让 Jython 原生代理直接崩溃。）
2. **Python 里不要写非 ASCII 字符串字面量**。Python 2 的 `str` 是字节串，转成 Java `String` 会乱码；面向玩家的文本一律走 `Component.translatable("key")` + lang 文件。
3. **注册只在入口方法内完成**（`onInitialize` / `onInitializeClient`），之后注册表会冻结。
4. **id 与命名空间绑定**。短名（如 `"ruby"`）只在入口方法内可用，会补全成**你自己的 mod id**；在运行期回调（命令、事件、网络接收）里 id **必须全限定**，如 `"mymod:ruby"`。
5. 每个 `.py` 文件首行保留 `# -*- coding: utf-8 -*-`。

## 在自己的模组里使用

`fabric.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "mymod",
  "version": "1.0.0",
  "entrypoints": {
    "main":   [{ "adapter": "jython", "value": "com.example.mymod.MyMod" }],
    "client": [{ "adapter": "jython", "value": "com.example.mymod.client.MyModClient" }]
  },
  "depends": {
    "fabricloader": ">=0.19.5",
    "fabric-api": "*",
    "jython_language_runtime": ">=1.0-SNAPSHOT"
  }
}
```

> 运行时目前以快照版本发布，依赖请写 `>=1.0-SNAPSHOT`（或 `*`）；正式发布后可放宽为 `>=1.0`。

**不需要**自己声明 `languageAdapters` —— `jython` 适配器由本运行时全局注册。

`build.gradle`

```gradle
sourceSets {
    main   { resources.srcDir 'src/main/python' }
    client { resources.srcDir 'src/client/python' }   // 用到客户端入口时才需要
}

dependencies {
    implementation files("libs/jython_language_runtime-1.0.jar")  // 运行时
    implementation 'org.python:jython-slim:2.7.5b1'               // dev 运行必需

    // 不要 `include` jython-slim：运行时已经把它作为嵌套 jar（jar-in-jar）打包好了。
}
```

脚本位置 —— 入口点 `value`（全限定名）逐点换成斜杠加 `.py`，**类名必须与文件名一致**。

`src/main/python/com/example/mymod/MyMod.py`

```python
# -*- coding: utf-8 -*-
from com.AlerCello86767.jython_language_runtime import Registration

class MyMod(object):
    def onInitialize(self):
        Registration.registerItem("ruby", {
            "maxStackSize": 64,
            "group": "ingredients",
        })
```

> 若把代码拆成多个模块，请把可导入的辅助模块放在 python 根目录，并使用**带 mod 前缀的顶层模块名**（如 `mymod_utils.py`），避免与 Java 包路径冲突。

## 能用它做什么

- **内容**：方块、物品（食物 / 工具 / 盔甲 / 自定义行为）、实体与 Python 驱动的渲染、方块实体、容器菜单、自定义界面与 HUD。
- **交互**：命令、物品交互回调、世界 / 服务器 / 玩家 / 实体 / 方块事件。
- **世界**：自定义 `gamerule`、战利品修改、群系与地物与刷怪修改、维度属性、聊天与系统消息钩子。
- **网络**：自定义 C2S / S2C 数据包。
- **其他**：内容关联（堆肥 / 燃料 / 易燃 / …）、物品与方块的存储能力查询、方块间传输、声音、额外模型。

每个门面的完整参数说明见 [`info.md`](info.md)。

## 开发本运行时

```powershell
# Loom 1.18.2 需要 JDK 25（系统默认 JDK 可能更旧）
$env:JAVA_HOME="D:\javalist\java25"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"

.\gradlew.bat build      # 编译 + 打包
.\gradlew.bat runClient  # 启动开发客户端
```

运行时自身**不注册任何内容**、**不声明任何入口点**：它只提供 `jython` 语言适配器、各门面、宿主类以及少量 mixin。

## 许可证

MIT —— 见 [LICENSE.txt](LICENSE.txt)。
