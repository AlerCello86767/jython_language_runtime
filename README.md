# jython_language_runtime

A **Jython language runtime for Fabric** — write Minecraft mods in Python, without writing a single line of Java.

[中文文档](README.zh_CN.md)

> Minecraft **26.1.2** · Fabric Loader **0.19.5** · Fabric API **0.155.3+26.1.2** · JDK **25** · Loom **1.18.2** · Jython **2.7.5b1** (Python 2.7 syntax on the JVM)

---

## What it is

This is **not** a content mod. It is a *language runtime*:

- Fabric's `LanguageAdapter` maps an entrypoint declared with `"adapter": "jython"` to a `.py` file inside **your** mod jar;
- the script is executed by a shared Jython interpreter, and the class with the same name as the file is instantiated;
- a JDK dynamic proxy bridges the Java entrypoint interface to that Python object;
- registries, events, networking, GUI, etc. are exposed through thin Java **facades** and **host classes**, so Python only deals with plain data (strings / numbers / dicts / functions).

You write `class MyMod(object)` in Python. The runtime handles the rest.

## Rules you must follow

1. **Never implement Java interfaces from Python.** Always `class X(object)`. (In dev, Knot's class-loader topology makes Jython's native proxies blow up.)
2. **No non-ASCII string literals in Python.** Python 2 `str` is a byte string and will be mojibaked when converted to a Java `String`. All player-facing text goes through `Component.translatable("key")` plus lang files.
3. **Register only inside entrypoint methods** (`onInitialize` / `onInitializeClient`). Registries are frozen afterwards.
4. **Ids are namespace-scoped.** A short id such as `"ruby"` works only inside entrypoint methods and resolves to *your* mod id. In runtime callbacks (commands, events, network handlers) ids **must be fully qualified**, e.g. `"mymod:ruby"`.
5. Keep `# -*- coding: utf-8 -*-` on the first line of every `.py` file.

## Using it in your own mod

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

> While the runtime is still published as a snapshot, require `>=1.0-SNAPSHOT` (or `*`); relax it to `>=1.0` once a release is available.

You do **not** need to declare `languageAdapters` — the `jython` adapter is registered globally by this runtime.

`build.gradle`

```gradle
sourceSets {
    main   { resources.srcDir 'src/main/python' }
    client { resources.srcDir 'src/client/python' }   // only if you use a client entrypoint
}

dependencies {
    implementation files("libs/jython_language_runtime-1.0.jar")  // the runtime
    implementation 'org.python:jython-slim:2.7.5b1'               // required for dev runs

    // Do NOT `include` jython-slim: the runtime already bundles it as a nested jar (jar-in-jar).
}
```

Script location — the entrypoint `value` (FQCN) is mapped to `<mod root>/<path>.py`; **the class name must equal the file name**.

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

> If you split your code into multiple modules, place importable helpers at the python root using **mod-prefixed top-level names** (e.g. `mymod_utils.py`), so they do not collide with Java package paths.

## What you can build with it

- **Content**: blocks, items (food / tools / armor / custom behavior), entities with Python-driven rendering, block entities, container menus, custom screens and HUD.
- **Interaction**: commands, item-interaction callbacks, world / server / player / entity / block events.
- **World**: custom `gamerule`s, loot modifications, biome/feature/spawn modification, dimension attributes, chat & system message hooks.
- **Networking**: custom C2S/S2C payloads.
- **Misc**: content associations (compost / fuel / flammable / …), item & block capability lookups, block-to-block transfers, sounds, extra models.

The full parameter reference for every facade lives in [`info.md`](info.md).

## Developing this runtime

```powershell
# Loom 1.18.2 requires JDK 25 (the default JDK may be older)
$env:JAVA_HOME="D:\javalist\java25"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"

.\gradlew.bat build      # compile + package
.\gradlew.bat runClient  # launch a dev client
```

The runtime itself ships **no content** and declares **no entrypoints**: it only provides the `jython` language adapter, the facades, the host classes and a few mixins.

## License

MIT — see [LICENSE.txt](LICENSE.txt).
