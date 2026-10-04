package com.AlerCello86767.jython_language_runtime;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 伤害类型（L4.6）门面：用 {@code "ns:path"} 便捷构造 {@link DamageSource}，并做只读查询。
 *
 * <p>自定义伤害类型**完全数据驱动**：内容定义在 {@code data/<ns>/damage_type/<path>.json}
 * （datagen 入口见 PyDatagen.damageTypes），运行时没有「注册伤害类型」这一步——数据包加载后
 * {@code level.damageSources().damageTypes} 里就会出现该键。
 *
 * <pre>{@code
 * source = PyDamage.source(level, "mymod:overheat")                  # 无来源
 * source = PyDamage.source(level, "mymod:overheat", attacker)        # 带造成伤害的实体
 * source = PyDamage.source(level, "mymod:overheat", arrow, shooter)  # 直接实体 + 造成实体
 * ids    = PyDamage.types(level)                                     # 只读：当前注册表里的全部键
 * ok     = PyDamage.exists(level, "mymod:overheat")
 * }</pre>
 *
 * <p>id 省略命名空间时按「当前命名空间」补全——因此只在入口脚本窗口内可用；事件/命令等回调里
 * 请写全限定 id（如 {@code "mymod:overheat"}）。
 *
 * <p>26.1.2 实地核实（javap）：
 * <ul>
 *   <li>{@code Level.damageSources()} → {@link net.minecraft.world.damagesource.DamageSources}
 *       （{@code ServerLevel}/{@code ClientLevel} 都继承自 {@code Level}）</li>
 *   <li>{@code DamageSources} 有 {@code source(ResourceKey)}、{@code source(ResourceKey, Entity)}、
 *       {@code source(ResourceKey, Entity, Entity)} 三个重载；前者的实现内部是
 *       {@code damageTypes.getOrThrow(key)}，键缺失时抛的是底层异常，故本门面先自行检查并给可读报错</li>
 *   <li>公开字段 {@code DamageSources.damageTypes} 是 {@code Registry<DamageType>}，可用
 *       {@code containsKey(ResourceKey)} 判断键是否存在</li>
 *   <li>{@code DamageSource(Holder, Entity)} 会把「直接实体」与「造成实体」都设成同一个实体</li>
 * </ul>
 */
public final class PyDamage {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyDamage");

    private PyDamage() {
    }

    /** 无实体来源：{@code PyDamage.source(level, "mymod:overheat")}。 */
    public static DamageSource source(Object level, String damageTypeId) {
        return source(level, damageTypeId, null, null);
    }

    /** 带造成伤害的实体：{@code PyDamage.source(level, "mymod:overheat", attacker)}。 */
    public static DamageSource source(Object level, String damageTypeId, Object attacker) {
        return source(level, damageTypeId, attacker, null);
    }

    /**
     * 直接实体 + 造成实体：{@code PyDamage.source(level, "mymod:overheat", arrow, shooter)}。
     *
     * <p>对应原版 {@code DamageSources.source(key, directEntity, causingEntity)}：前者是「打中的东西」
     * （如箭），后者是「该负责的实体」（如射箭的玩家）。任一为 None/null 时自动降级到更简单的重载。
     */
    public static DamageSource source(Object level, String damageTypeId,
                                      Object directEntity, Object causingEntity) {
        Level lvl = requireLevel(level);
        Identifier id = ModIds.parse(damageTypeId);
        DamageSource damageSource = build(lvl, id, asEntity(directEntity, "directEntity"),
                asEntity(causingEntity, "causingEntity"));
        LOGGER.info("Built damage source {} for level {}", id, lvl.dimension().identifier());
        return damageSource;
    }

    /** 只读查询：当前 level 的伤害类型注册表里的全部键（已排序），形如 {@code "minecraft:generic"}。 */
    public static List<String> types(Object level) {
        return requireLevel(level).damageSources().damageTypes.keySet().stream()
                .map(Identifier::toString)
                .sorted()
                .toList();
    }

    /** 只读查询：给定伤害类型键在当前 level 是否已加载（数据包没加载时为 false）。 */
    public static boolean exists(Object level, String damageTypeId) {
        Level lvl = requireLevel(level);
        return lvl.damageSources().damageTypes
                .containsKey(ResourceKey.create(Registries.DAMAGE_TYPE, ModIds.parse(damageTypeId)));
    }

    // ---------- 内部 ----------

    private static DamageSource build(Level level, Identifier id, Entity direct, Entity causing) {
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, id);
        Registry<DamageType> registry = level.damageSources().damageTypes;
        if (!registry.containsKey(key)) {
            throw new IllegalArgumentException("未知伤害类型 '" + id + "'：该键不在当前 level 的伤害类型注册表里。"
                    + "自定义伤害类型是数据驱动的——PyDatagen.damageTypes 只生成 data/<ns>/damage_type/<path>.json，"
                    + "必须等数据包加载后才会出现在注册表中；请确认数据包已启用/已随模组打包，"
                    + "或改用已存在的原版伤害类型。当前可用键（前 8 个）：" + sample(registry));
        }
        if (direct == null && causing == null) {
            return level.damageSources().source(key);
        }
        if (direct != null && causing == null) {
            return level.damageSources().source(key, direct);
        }
        return level.damageSources().source(key, direct, causing);
    }

    private static Level requireLevel(Object level) {
        if (level instanceof Level lvl) {
            return lvl;
        }
        throw new IllegalArgumentException("PyDamage expects a Level (ServerLevel/ClientLevel), got: " + level);
    }

    private static Entity asEntity(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (value instanceof Entity entity) {
            return entity;
        }
        throw new IllegalArgumentException("PyDamage." + what + " must be an Entity or None, got: " + value);
    }

    private static String sample(Registry<DamageType> registry) {
        return registry.keySet().stream()
                .limit(8)
                .map(Identifier::toString)
                .collect(Collectors.joining(", "));
    }
}
