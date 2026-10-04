# jython_language_runtime —— Jython Fabric Language Runtime

> 用 Python（Jython 2.7.5b1，Python 2.7 语法跑在 JVM 上）编写 Minecraft Fabric 模组。
> 本文档是运行时原理、构建配置与全部门面参数包的单一事实来源。

---

# 1. 运行原理

## 1.1 启动链路

```
游戏启动（Knot 类加载器）
  → Fabric Loader 读 fabric.mod.json
  → languageAdapters 把 "jython" 映射到 JythonAdapter
  → 入口点声明 {"adapter": "jython", "value": "com.xxx.MyMod"}
  → Loader 调 JythonAdapter.create(mod, value, 入口接口.class)
      ├─ value 是 Python 模块路径（点分隔）→ com/xxx/MyMod.py 文件路径
      ├─ mod.findPath(路径) 在【调用方 mod 自己】的根里找脚本
      │   （dev 下 classes/resources 是两个根；生产是 jar）
      ├─ UTF-8 读源码 → 共享解释器 py.exec(source)
      │   （每个入口点独立 PyStringMap 命名空间，互不污染）
      ├─ 取同名 Python 类，__call__() 实例化
      └─ JDK 动态代理包成入口接口，InvocationHandler 把
         Java 方法调用转发到 Python 对象的同名方法
  → 游戏持有的就是一个"会调 Python 的"接口实例
```

## 1.2 为什么用 JDK 动态代理而不是 Jython 原生继承

Jython 继承 Java 接口（如 `class X(ModInitializer)`）会走 MakeProxies 字节码生成，
在 dev 环境因 Knot 对 `net.fabricmc.loader.**` 包的硬编码父委托而类型分裂
（`PyProxy` 被 AppClassLoader 和 Knot 各加载一份），实例化即崩。
因此本项目铁律：

- **Python 类一律 `class X(object)`，不继承任何 Java 类型**
- 需要"实现接口"的场合由 Java 侧做桥：
  - 入口点 → JythonAdapter 的 JDK 动态代理
  - 物品行为 → DelegatingItem 转发宿主
  - 实体行为 → PythonEntity 宿主
  - 方块实体行为 → PythonBlock + PythonBlockEntity 宿主
  - Fabric 事件 → 门面内部用 Java 方法引用/lambda 接 Python 函数

## 1.3 双端边界

- `main` 入口的注册、事件、网络声明都在**服务端与客户端共有的初始化窗口**执行
- 纯客户端逻辑（渲染器、S2C 收包、按键）放 `client` 入口，走 client 源集的门面
- Fabric global receiver 本身就在主线程回调（服务端 server thread / 客户端 render thread），
  Python 回调**不需要**额外线程调度

---

# 2. 如何添加 Jython（构建配置）

## 2.1 环境

- JDK 25（`D:\javalist\java25`）。每次跑 gradle 前：
  ```powershell
  $env:JAVA_HOME="D:\javalist\java25"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
  ```
  默认 java 是 21，Loom 1.18 要求 JVM 25。
- Minecraft 26.1.2（Mojang 官方映射）、Fabric Loader 0.19.5、Loom 1.18.x

## 2.2 build.gradle 关键片段

```groovy
sourceSets { main { resources.srcDir 'src/main/python' } }  // .py 作为资源打包

configurations {
    jython {
        // 游戏已提供的库必须排除，否则 Knot 内重复定义 → loader constraint violation
        exclude group: 'org.ow2.asm'
        exclude group: 'com.google.guava'
        exclude group: 'com.ibm.icu'
        exclude group: 'io.netty'
        exclude group: 'org.bouncycastle'
        exclude group: 'commons-io'
        exclude group: 'org.apache.commons', module: 'commons-compress'
    }
}

loom { mods { "jython_language_runtime" {
    sourceSet sourceSets.main
    sourceSet sourceSets.client
    configuration configurations.jython   // jython 无 fabric.mod.json，必须归入 mod 组
} } }

dependencies {
    implementation 'org.python:jython-slim:2.7.5b1'  // 编译类路径
    jython        'org.python:jython-slim:2.7.5b1'   // 组归属
    include       'org.python:jython-slim:2.7.5b1'   // 生产 jar-in-jar
}
```

## 2.3 fabric.mod.json

```json
"languageAdapters": { "jython": "com.AlerCello86767.jython_language_runtime.JythonAdapter" },
"entrypoints": {
  "main": [{ "adapter": "jython", "value": "com.AlerCello86767.jython_language_runtime.Jython_language_runtime" }],
  "client": [{ "adapter": "jython", "value": "com.AlerCello86767.jython_language_runtime.client.Jython_language_runtimeClient" }],
  "fabric-datagen": [{ "adapter": "jython", "value": "com.AlerCello86767.jython_language_runtime.client.Jython_language_runtimeDataGenerator" }]
}
```

## 2.4 Jython 解释器设置（JythonAdapter 内）

- `python.cachedir.skip=true`：不写 `$py.class` 缓存（jar-in-jar 解压位置只读）
- `python.import.site=false`：跳过 site 导入，启动更快
- `systemState.setClassLoader(Knot)`：强制 Python 的 Java import 走 Knot，
  保证门面类与游戏类只有一份

---

# 3. 门面清单与参数包

Python 侧调用约定：**参数包是普通 dict，键固定，类型为字符串/数字/布尔/列表/嵌套 dict**；
字符串语义翻译（如 `"metal"→SoundType.METAL`）、默认值补全、注册全部在 Java 侧完成。

所有注册必须在 `onInitialize` 窗口内完成——注册表之后会冻结。

## 3.1 Registration.registerBlock(path, options)

自动连带注册同名 BlockItem。

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| hardness | float | 1.0 | 挖掘硬度 |
| resistance | float | =hardness | 爆炸抗性 |
| sound | string | "stone" | stone/metal/wood/gravel/glass/sand/snow/wool/slime |
| requiresTool | bool | false | 空手不掉落 |
| miningTool | string | — | pickaxe/axe/shovel/hoe，仅校验；标签由数据包提供 |
| miningLevel | string | — | hand/wood/stone/iron/diamond/netherite，仅校验 |
| blockEntity | Python 类 | — | 给了就用 PythonBlock 宿主并注册 BlockEntityType |
| ticking | bool | false | 每 tick 回调 Python `tick`（需配 blockEntity） |
| sync | bool | true | 方块实体数据同步客户端（需配 blockEntity） |
| facing | bool | false | 水平朝向属性，放置朝玩家；使用 PythonFacingBlock 宿主 |
| size | int | — | 方块实体物品栏槽数，配合 PyMenus.openBlock 做容器 |

挖掘等级是数据驱动的：运行时代码只校验字符串，实际效果由
`data/minecraft/tags/block/mineable/<tool>.json` 与 `needs_<level>_tool.json` 承载。

## 3.2 Registration.registerItem(path, options)

**基础属性**：`maxStackSize`（默认 64）、`rarity`（common/uncommon/rare/epic）、
`group`（创造栏：building_blocks/colored_blocks/natural_blocks/functional_blocks/
redstone_blocks/tools_and_utilities/combat/food_and_drinks/ingredients/spawn_eggs/op_blocks）、
`maxDamage`（耐久；设置后自动不可堆叠）、`fireResistant`、`enchantability`。

**food（dict）**：`nutrition`、`saturation`（系数）、`alwaysEat`、
`returns`（食用后返回的物品 id，必须先注册）、
`effects`（列表：`{id, duration=200, amplifier=0, probability=1.0}`）；
Consumable 子字段（给任意一个才覆盖原版默认进食表现）：
`consumeSeconds`（默认 1.6）、`animation`（none/eat/drink/block/bow/trident/crossbow/
spyglass/toot_horn/brush/bundle/spear）、`sound`（音效 id）、`particles`。

**tool（dict）**：`kind`（pickaxe/axe/hoe/shovel/sword）、
`material`（wood/stone/copper/iron/diamond/gold/netherite）或
`customMaterial`（durability/speed/attackDamageBonus/enchantmentValue/incorrectForDrops(tag)/repairItems(tag)）、
`attackDamage`、`attackSpeed`、`mineableTag`（覆盖默认挖掘标签，sword 不适用）。

**armor（dict）**：`type`（helmet/chestplate/leggings/boots/body）、
`material`（leather/copper/chainmail/iron/gold/diamond/turtle_scute/netherite/armadillo_scute）或
`customMaterial`（**必须给 assetId**；defense(部位→值)/durability/enchantmentValue/equipSound/
toughness/knockbackResistance/repairIngredient(tag)）。

**equippable（dict）**：`slot`（必填：mainhand/offhand/feet/legs/chest/head/body/saddle）、
`equipSound`、`assetId`、`cameraOverlay`、`shearingSound`、
`dispensable/swappable/damageOnHurt/equipOnInteract/canBeSheared`（bool）、
`allowedEntities`（实体 id 列表）。

**内容增强**：`customName`（直接覆盖显示名）、`lore`（字符串列表）、
`enchantments`（附魔 id→等级，数据驱动注册表用延迟 holder）、
`attributeModifiers`（列表：`{attribute, amount, operation=add_value, slot=any, id?}`）、
`unbreakable`、`glintOverride`。

**行为**：`behavior`（Python 对象）→ 物品改用 DelegatingItem 转发宿主，
可定义：`use / useOn / interactLivingEntity / finishUsingItem / releaseUsing /
getUseDuration / getUseAnimation / onUseTick / hurtEnemy / postHurtEnemy / mineBlock /
getDestroySpeed / inventoryTick / onCraftedBy / isFoil`，未定义的走原版行为。

**特殊场景**：`recipeRemainder`（合成剩余物，必须先注册）、`repairCost`、
`canBreak`/`canPlaceOn`（冒险模式方块 id 列表）、`container`（`{item, count=1}` 列表）、
`bannerPatterns`（`{pattern, color=white}`）、`dyedColor`（int 或 "#RRGGBB"）、`mapId`。

## 3.3 EntityRegistration.registerEntity(path, entityClass, options)

`entityClass` 是 Python **类**（不是实例）——每个实体实例化一份，状态天然隔离。

| 键 | 默认 | 说明 |
|---|---|---|
| category | "misc" | misc/monster/creature/ambient/axolotls/water_creature/water_ambient/underground_water_creature |
| width / height | 0.6 / 1.8 | 碰撞箱 |
| trackingRange | 5 | 客户端追踪范围（区块） |
| fireImmune | false | 免疫火焰 |
| attributes | — | 属性 id→基础值；给了才注册 LivingEntity 属性表 |

宿主钩子（PythonEntity）：`tick(self, entity)`、`interact(self, player, hand, location)`
→ 返回 InteractionResult 或 None。

客户端渲染器在 client 入口用 `ClientEntityRenderers` 单独注册。

## 3.4 方块实体宿主（registerBlock 的 blockEntity 类）

Python 类每个方块实体实例化一份。钩子：
`tick(self, blockEntity, level, pos, state)`、`saveAdditional(self, output)`、
`loadAdditional(self, input)`（input/output 是 NBT 读写视图，方法如 `putInt/getIntOr`）。
数据变更后调 `blockEntity.setChanged()`；`sync` 打开时会顺带推同步包。

## 3.5 CommandRegistrationCallback.register(fn)

Python 传一个函数 `(dispatcher, buildContext, selection) -> None`，内部挂到 Fabric
CommandRegistrationCallback.EVENT。命令树用 Brigadier 原生 API 直接搭
（`Commands.literal/argument`、`StringArgumentType` 等，都是可直接 import 的游戏类）。

## 3.6 ItemCallbacks（物品交互事件，B 方案）

按物品 id 分发；Python 回调返回 None 视为 `InteractionResult.PASS`：

- `onUseItem(id, fn)` → `(player, level, hand)`
- `onUseBlock(id, fn)` → `(player, level, hand, hitResult)`
- `onUseEntity(id, fn)` → `(player, level, hand, entity, hitResult)`
- `onAttackEntity(id, fn)` → 同上
- `onAttackBlock(id, fn)` → `(player, level, hand, pos, direction)`

## 3.7 GameEvents（世界/服务器/玩家/实体事件，B 方案）

全部在服务端主线程回调，Python 只传函数：

- 服务器：`onServerStarting/Started/Stopping/Stopped(server)`、
  `onBeforeSave/onAfterSave(server, flush, force)`
- tick：`onServerTickStart/End(server)`、`onLevelTickStart/End(level)`（每维度各一次）
- 实体：`onEntityLoad/Unload(entity, level)`、
  `onEquipmentChange(entity, slot, previous, current)`
- 受伤/死亡：`onLivingAllowDamage(entity, source, amount) -> bool`（false 取消）、
  `onLivingAfterDamage(entity, source, baseDamage, damage, blocked)`、
  `onLivingAfterDeath(entity, source)`、
  `onLivingAllowDeath(entity, source, amount) -> bool`
- 玩家：`onPlayerJoin(player)`、`onPlayerLeave(player)`、
  `onPlayerRespawn(oldPlayer, newPlayer, alive)`、
  `onPlayerAllowDeath(entity, source, amount) -> bool`（instanceof 过滤玩家）
- 方块：`onBlockBreakBefore(level, player, pos, state, be) -> bool`（false 取消）、
  `onBlockBreakAfter/onBlockBreakCanceled(level, player, pos, state, be)`、
  `onBlockPlace(level, player, pos, state)`（BlockItemMixin 提供）
- 物品：`onItemPickup(player, stack, amount)`（ItemEntityMixin 提供）
- 睡觉：`onStartSleeping/onStopSleeping(entity, pos)`、
  `onAllowSleep(player, pos) -> String 失败原因或 None 放行`
- 跨维度：`onEntityChangeLevel(originalEntity, newEntity, origin, destination)`、
  `onPlayerChangeLevel(player, origin, destination)`

## 3.8 PyNetworking（P11，双端）

频道 id 不带命名空间时默认本模组。数据体是扁平 dict
（bool/int/long/float/double/String）。

- 主入口声明：`PyNetworking.onC2S("channel", fn)`（顺带挂服务端接收
  `fn(player, data)`）、`PyNetworking.declareS2C("channel")`（类型必须双端注册，
  所以声明放主入口）
- 服务端发：`PyNetworking.sendToPlayer(player, "channel", {...})`
- 客户端侧（client 入口）：`ClientNetworking.onS2C("channel", fn)`、`sendToServer(...)`

## 3.9 菜单 / GUI（P10b）

Python 声明槽位布局与面板绘制，坐标为面板局部坐标（与原版/Mekanism 一致）。

- `PyMenus.register(path, opts)`（主入口）：opts 为
  `menu`(Python 类，宿主打开时实例化)/`title`(翻译键)/`size`(槽总数)；
  注册 MenuType。
- `PyMenus.openBlock(player, path, level, pos)`：在方块实体物品栏上打开（物品随方块持久化）。
- 菜单类钩子：`initSlots(self, SlotBuilder)`；
  builder：`slot(index, x, y)`、`playerInventory(x, y)`。
- `PythonContainerMenu`（main）：标准容器槽位 + Shift 转移；
  拿到 Python 类先 `__call__()` 实例化（否则 unbound method 异常被吞、无界面）。
- `PyMenuScreens.bind(path, opts)`（client）：opts `{screen: Python 类}`。
- `PythonMenuScreen`（client）：`extractContents` 平移 pose 到面板原点后，
  调 Python `draw(mouseX, mouseY, partialTick)` 取回 UiDraw 回放；不调 super
  （跳过原版默认背景与标签）。
- `UiDraw`（client）：`fill/outline/bar/item/text/textCentered/textArgs`；
  构建低频、回放每帧且不跨语言。

## 3.10 世界修饰门面群（P11b）

全部主入口调用，且在所引用内容注册之后。

| 门面 | Fabric 模块 | 能力 |
|---|---|---|
| PyBiomes | biome-api-v1 | addFeature/addCarver/addSpawn；选择器 all/vanilla/overworld/nether/end/`#tag`/单 id |
| PyDimensions | dimensions-v1 | onModifyAttributes + setAttribute（26.1 自定义维度纯数据包） |
| PyLoot | loot-api-v3 | addDrop(表id,item,min,max,chance)、onModifyDrops(fn) |
| PyMessages | message-api-v1 | 聊天/命令/系统消息 之后+Allow 共 6 个 |
| PyGameRules | game-rule-api-v1 | boolean/int/double 规则；get/set/onChange |
| PyContentRegistries | content-registries-v0 | 堆肥/燃料/易燃/去皮/耕地/踩平/氧化/涂蜡/振动/村民 |
| PyLookups | api-lookup-api-v1 | 只查：hasBlockItemStorage/blockItemStorageSides/hasItemStorage |
| PyTransfers | transfer-api-v1 | extractAt/insertAt/move/peekAt，全事务 |
| PySounds（client） | sound-api-v1 | play(id, source, behavior类) 返回句柄；stop/isPlaying |
| PyModels（client） | model-loading-api-v1 | registerBlockModel 声明 + blockModel 取烘焙模型 |

Mixin：`BlockItemMixin`（放置成功事件）、`ItemEntityMixin`（拾取事件）。

---

# 4. 资源文件约定

```
src/main/resources/
├── fabric.mod.json
├── assets/jython_language_runtime/
│   ├── blockstates/<id>.json        # 无 FACING 属性时用 {"variants":{"":{...}}}
│   ├── items/<id>.json              # 1.21.4+ 物品模型定义，缺它物品紫黑/无模型
│   ├── models/block/<id>.json
│   ├── models/item/<id>.json
│   ├── textures/block|item/<id>.png
│   └── lang/en_us.json, zh_cn.json  # UTF-8 无 BOM
└── data/
    ├── minecraft/tags/block/mineable/pickaxe.json   # 挖掘标签（单数目录）
    └── minecraft/tags/block/needs_iron_tool.json    # 挖掘等级
```

贴图引用写 `jython_language_runtime:block/xxx` 对应 `textures/block/xxx.png`，路径不要带多余子目录。
模型带 `facing` 变体的前提是方块注册时给了 `facing: True`（blockstates 按水平四向写 variants）。

---

# 5. 编码与 i18n 规范

- 所有 `.py` 首行 `# -*- coding: utf-8 -*-`，文件保存为 UTF-8 无 BOM
- lang JSON 同 UTF-8 无 BOM
- **用户可见文本一律 `Component.translatable("key", args...)` + lang 文件**，
  不要在 Python 里写字面量中文——Python 2 的 str 是字节串，进 JVM 必乱码
- Python 2 语法：print 语句、无 f-string、旧式除法；类必须继承 `object`（新式类）

---

# 6. 26.1.2 API 差异速查（相对 Yarn/旧版）

- `ResourceLocation` → `net.minecraft.resources.Identifier`
- 方块/物品构造前必须 `Properties.setId(ResourceKey)`，否则 NPE
- 标签目录为单数：`tags/block/`、`tags/item/`
- 1.21.4+ 物品模型定义在 `assets/<mod>/items/`，方块物品不需要 `models/item/` 转发
- `World`→`Level`、`PlayerEntity`→`Player`、`isClient`→`isClientSide()`
- `ItemStack.decrement`→`shrink(int)`；`getUseAction`→`getUseAnimation`（返回 ItemUseAnimation）
- 生成实体：服务端 `ServerLevel.addFreshEntity` / 客户端 `ClientLevel.addEntity`
- `inventoryTick` 只在服务端调用
- GUI 渲染：`GuiGraphics`→`GuiGraphicsExtractor`；Screen 渲染拆为 `extractRenderState`/`extractContents`；
  pose 是 JOML `Matrix3x2fStack`（`pushMatrix()/translate(x,y)/popMatrix()`）
- `outline(x, y, width, height)` 参数为宽/高；`fill(x0,y0,x1,y1)` 为四角坐标，两者勿混
- `AbstractContainerScreen.imageWidth/imageHeight` 为 final（默认 176/166，其他尺寸走 5 参构造）；
  每 tick 逻辑写 `containerTick()`（`tick()` 为 final）
- Loom 1.18 无 `modImplementation`、无 `remapJar`（`jar` 直接产出最终包，`include` 直接生效）

---

# 7. 作为 Runtime 被其他 Mod 依赖

JythonAdapter 的 `mod.findPath` 找的是**调用方 mod 自己的根**，天然支持跨 mod：

```jsonc
// 新 mod 的 fabric.mod.json
{
  "depends": { "jython_language_runtime": "*" },
  "entrypoints": {
    "main": [{ "adapter": "jython", "value": "com.example.mymod.MyMod" }]
  }
  // 不需要自己声明 languageAdapters
}
```

新 mod 工程只需：`implementation` 依赖本 mod 的 jar（编译期看门面签名）、
`resources.srcDir 'src/main/python'`、Python 里 `from com.AlerCello86767.jython_language_runtime import Registration`
（Knot 对所有 mod 的类全局可见）。runtime 的 jython jar-in-jar 随本 mod 提供，不要重复打包。

**已知缺口**：`ModIds.MOD_ID` 硬编码 `"jython_language_runtime"`，其他 mod 注册的内容会落到 jython_language_runtime
命名空间下——多 mod 共存前需要把 modId 参数化。
