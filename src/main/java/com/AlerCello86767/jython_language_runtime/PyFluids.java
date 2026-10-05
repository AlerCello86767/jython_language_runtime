package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asBoolean;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import java.util.LinkedHashMap;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.PyHandles;
import com.AlerCello86767.jython_language_runtime.host.PythonFluid;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/**
 * 流体门面（main 侧）：注册一条自定义流体（源 + 流动 + 可选流体方块）。
 *
 * <pre>
 * PyFluids.register("test_liquid", {
 *     "tickDelay": 5,               # 流动一次间隔（tick），默认 5；扩散延迟默认等同该值
 *                                   # （原版 getSpreadDelay 直接返回 getTickDelay）
 *     "slopeFindDistance": 4,       # 下坡搜索距离，默认 4
 *     "dropOff": 1,                 # 每格衰减，默认 1
 *     "explosionResistance": 100.0, # 默认 100
 *     "canConvertToSource": False,  # 是否像水一样能无限生成，默认 False
 *     "dripParticle": "minecraft:dripping_water",  # 钟乳石滴水粒子 id，默认无
 *     "light": 0,                   # 流体方块发光等级 0..15（岩浆=15），默认 0
 *     "viscosity": 0.4,             # 实体阻尼：每 tick 速度保留比例 0..1（1=无阻尼，
 *                                   # 岩浆约 0.5；原版阻力按标签硬编码，这里宿主手动施加），默认 1.0
 *     "push": 0.0,                  # 水流推动强度（乘流向量），默认 0 不推
 *     "resetFall": False,           # 泡在其中清除摔落距离（粘液式防摔），默认 False
 *     "block": True,                # 是否注册同名流体方块（/setblock、/fill 用），默认 True
 *     "behavior": SomeClass,        # 可选：行为钩子类（见下）；钩子与上面三个物理参数叠加生效
 * })
 * </pre>
 *
 * <p><b>行为钩子</b>：{@code "behavior"} 传一个**类**（不是实例，与 {@code Registration} 的
 * {@code behavior} 同约定）。类里定义同名函数即可生效，未定义即走原版；源与流动**共用同一份
 * 实例**（一个流体一个行为对象，不给两类状态各造一份）：
 * <ul>
 *   <li>{@code entityInside(level, pos, entity, effectApplier)}：实体在本流体格内（伤害/减速/点燃）</li>
 *   <li>{@code randomTick(level, pos, fluidState, random)}：随机 tick</li>
 *   <li>{@code animateTick(level, pos, fluidState, random)}：客户端粒子/音效</li>
 *   <li>{@code isRandomlyTicking()}：显式覆盖「是否参与随机调度」（缺省：实现了 randomTick 即为真）</li>
 * </ul>
 * 一个钩子都没实现时不会实例化任何 Python 对象。
 *
 * <p><b>不做桶装容器</b>：流体自身 {@code getBucket()} 返回空气、无拾取音效；方块也不会给 BlockItem。
 * 放置只能靠 `/setblock`、`/fill` 或流体自然扩散。
 *
 * <p>注册会占用两个流体 id：{@code <ns>:<path>}（源）与 {@code <ns>:flowing_<path>}（流动），
 * 以及同名方块 id（当 {@code block} 为真）。
 *
 * <p><b>资源侧要求（流体方块）</b>：{@code block} 为真时还必须给方块状态与模型，否则客户端会刷
 * 「Missing model for variant」并把该格渲染成紫黑格。照原版 water 的写法——空模型（只挂粒子贴图）
 * + 通配变体，一条 {@code ""} 覆盖 level 0..15：
 * <pre>
 * assets/&lt;ns&gt;/blockstates/&lt;name&gt;.json  → { "variants": { "": { "model": "&lt;ns&gt;:block/&lt;name&gt;" } } }
 * assets/&lt;ns&gt;/models/block/&lt;name&gt;.json → { "textures": { "particle": "&lt;ns&gt;:liquid/liquid" } }
 * </pre>
 * 液体本身的外观仍由 {@code PyFluidsClient.registerModel} 决定，这个模型只提供破坏粒子贴图。
 *
 * <p><b>贴图与颜色在客户端侧</b>：26.1 的流体外观由 {@code FluidModel}（贴图 + 着色）决定，
 * 见 {@code PyFluidsClient.registerModel}。
 *
 * <p><b>标签</b>：流体标签（{@code #minecraft:water} 之类）是数据包驱动的，运行期注册不了，
 * 走 {@code PyDatagen.tags(pack, "fluid", ...)} 产出 {@code data/<ns>/tags/fluid/*.json}。
 */
public final class PyFluids {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyFluids");
    /** 流体行为钩子的方法名（构造期用于探测 Python 到底实现了哪些）。 */
    private static final String[] BEHAVIOR_HOOKS = {
            "entityInside", "randomTick", "animateTick", "isRandomlyTicking"
    };
    private static final Map<Identifier, Entry> REGISTRY = new LinkedHashMap<>();

    private PyFluids() {
    }

    private record Entry(PythonFluid still, PythonFluid flowing, Block block) {
    }

    /** 注册一条流体。 */
    public static void register(String id, Map<String, Object> options) {
        Identifier ident = ModIds.parse(id);
        int light = asInt(options.get("light"), 0);
        if (light < 0 || light > 15) {
            throw new IllegalArgumentException("Fluid '" + id + "': light 必须在 0..15，got " + light);
        }
        PythonFluid.Options opts = new PythonFluid.Options(
                asInt(options.get("tickDelay"), 5),
                asInt(options.get("slopeFindDistance"), 4),
                asInt(options.get("dropOff"), 1),
                asDouble(options.get("explosionResistance"), 100.0),
                asBoolean(options.get("canConvertToSource"), false),
                dripParticleOf(asString(options.get("dripParticle"), ""), id),
                light,
                asDouble(options.get("viscosity"), 1.0),
                asDouble(options.get("push"), 0.0),
                asBoolean(options.get("resetFall"), false));
        boolean withBlock = asBoolean(options.get("block"), true);

        // 行为钩子：只在 Python 真的实现了至少一个钩子时才实例化（H11），且源/流动共用这一份
        PyHandles behavior = null;
        Object rawBehavior = options.get("behavior");
        if (rawBehavior instanceof PyObject behaviorClass) {
            if (PyHandles.implementsAny(behaviorClass, BEHAVIOR_HOOKS)) {
                behavior = new PyHandles(behaviorClass.__call__());
                behavior.preload(BEHAVIOR_HOOKS);
            }
        }

        PythonFluid.Source still = new PythonFluid.Source(opts, behavior);
        PythonFluid.Flowing flowing = new PythonFluid.Flowing(opts, behavior);
        // 先让两条流体互相认得对方：原版 LiquidBlock 在**构造期**就会读 getSource()/getFlowing()
        // 去预建 FluidState 缓存，顺序反了会 NPE
        still.attach(still, flowing);
        flowing.attach(still, flowing);

        Identifier flowingId = Identifier.fromNamespaceAndPath(ident.getNamespace(),
                "flowing_" + ident.getPath());
        Registry.register(BuiltInRegistries.FLUID, ident, still);
        Registry.register(BuiltInRegistries.FLUID, flowingId, flowing);

        Block block = null;
        if (withBlock) {
            // 与水的方块设置一致：可替换、无碰撞、极硬、被推动时破坏、不掉落、液体、静音
            BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, ident))
                    .replaceable()
                    .noCollision()
                    .strength(100.0F)
                    .pushReaction(PushReaction.DESTROY)
                    .noLootTable()
                    .liquid()
                    .sound(SoundType.EMPTY);
            if (light > 0) {
                // 发光流体（岩浆=15）：方块属性驱动，对所有 LEVEL 状态恒定发光
                int lightLevel = light;
                properties = properties.lightLevel(state -> lightLevel);
            }
            block = new LiquidBlock(still, properties);
            Registry.register(BuiltInRegistries.BLOCK, ident, block);
            still.attachBlock(block);
            flowing.attachBlock(block);
        }
        REGISTRY.put(ident, new Entry(still, flowing, block));
        LOGGER.info("Registered fluid {} (+{}, block={}, tickDelay={}, dropOff={}, behavior={})",
                ident, flowingId, block != null, opts.tickDelay(), opts.dropOff(), behavior != null);
    }

    /** 源流体（{@code PyFluidsClient} 用）。 */
    public static PythonFluid stillFluid(String id) {
        return require(id).still();
    }

    /** 流动流体（{@code PyFluidsClient} 用）。 */
    public static PythonFluid flowingFluid(String id) {
        return require(id).flowing();
    }

    /** 对应方块；注册时 {@code block=False} 则为 null。 */
    public static Block fluidBlock(String id) {
        return require(id).block();
    }

    private static Entry require(String id) {
        Identifier ident = ModIds.parse(id);
        Entry entry = REGISTRY.get(ident);
        if (entry == null) {
            throw new IllegalArgumentException("Fluid not registered: " + ident
                    + "（PyFluids.register 必须在主入口里调用）");
        }
        return entry;
    }

    /** 解析 {@code dripParticle} 粒子 id；空串表示无滴水粒子（原版默认）。 */
    private static ParticleOptions dripParticleOf(String raw, String fluidId) {
        if (raw.isEmpty()) {
            return null;
        }
        Identifier particleId = Identifier.tryParse(raw);
        if (particleId == null) {
            throw new IllegalArgumentException("Fluid '" + fluidId + "': 非法粒子 id '" + raw + "'");
        }
        // 滴水粒子必须是无参粒子：只有 SimpleParticleType 这类实现 ParticleOptions 的类型能用
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(particleId);
        if (!(type instanceof ParticleOptions options)) {
            throw new IllegalArgumentException("Fluid '" + fluidId + "': 粒子 '" + raw
                    + "' 不存在或不是无参粒子（dripParticle 只接受 SimpleParticleType 一类）");
        }
        return options;
    }
}
