package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asBoolean;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asFloat;
import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.requireString;

import java.util.Collections;
import java.util.Map;

import org.python.core.PyObject;
import org.python.core.PyType;

import com.AlerCello86767.jython_language_runtime.host.PythonGoal;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.goal.target.ResetUniversalAngerTargetGoal;
import net.minecraft.world.entity.player.Player;

/**
 * AI Goal 注册 adder 门面（L4.2）：Python 的 {@code goals(self, adder)} 钩子收到的就是这个对象。
 *
 * <pre>{@code
 * class RubyGolem(object):
 *     def goals(self, adder):
 *         adder.goal(0, "float")
 *         adder.goal(2, "melee_attack", {"speed": 1.0, "pauseWhenIdle": True})
 *         adder.goal(5, "look_at_player", {"range": 8.0})
 *         adder.target(1, "nearest", {"type": "minecraft:zombie"})
 *         adder.custom(3, FollowOwnerGoal)   # 自定义 goal：Python 行为类（也可传实例）
 * }</pre>
 *
 * <p>它是一个普通 Java 对象（公开方法 + 无参/多参重载），Jython 可当作普通 Java 对象调用。
 * 生命周期只在 {@code registerGoals()} 期，不需要热路径优化。
 */
public final class PyGoals {
    private static final String GOAL_NAMES =
            "float, melee_attack, look_at_player, random_look_around, random_stroll, "
            + "panic, water_avoiding_random_stroll, open_door";
    private static final String TARGET_NAMES =
            "nearest, nearest_attackable, hurt_by_target, owner_hurt_by_target, "
            + "owner_hurt_target, reset_universal_anger";

    private final Mob mob;
    private final GoalSelector goalSelector;
    private final GoalSelector targetSelector;

    public PyGoals(Mob mob, GoalSelector goalSelector, GoalSelector targetSelector) {
        this.mob = mob;
        this.goalSelector = goalSelector;
        this.targetSelector = targetSelector;
    }

    // ---------- goal ----------

    public void goal(int priority, String name) {
        goal(priority, name, null);
    }

    public void goal(int priority, String name, Map<String, Object> options) {
        if (name == null) {
            throw new IllegalArgumentException("goal name is required; supported: " + GOAL_NAMES);
        }
        goalSelector.addGoal(priority, buildGoal(name, options == null ? Collections.emptyMap() : options));
    }

    private Goal buildGoal(String name, Map<String, Object> options) {
        switch (name) {
        case "float":
            return new FloatGoal(mob);
        case "melee_attack":
            return new MeleeAttackGoal(pathfinderMob(name), asDouble(options.get("speed"), 1.0d),
                    asBoolean(options.get("pauseWhenIdle"), true));
        case "look_at_player":
            return new LookAtPlayerGoal(mob, lookAtType(options), asFloat(options.get("range"), 8.0f),
                    asFloat(options.get("probability"), LookAtPlayerGoal.DEFAULT_PROBABILITY));
        case "random_look_around":
            return new RandomLookAroundGoal(mob);
        case "random_stroll":
            return new RandomStrollGoal(pathfinderMob(name), asDouble(options.get("speed"), 1.0d),
                    asInt(options.get("interval"), RandomStrollGoal.DEFAULT_INTERVAL),
                    asBoolean(options.get("checkNoActionTime"), false));
        case "panic":
            return new PanicGoal(pathfinderMob(name), asDouble(options.get("speed"), 1.25d));
        case "water_avoiding_random_stroll":
            return new WaterAvoidingRandomStrollGoal(pathfinderMob(name),
                    asDouble(options.get("speed"), 1.0d),
                    asFloat(options.get("probability"), WaterAvoidingRandomStrollGoal.PROBABILITY));
        case "open_door":
            return new OpenDoorGoal(mob, asBoolean(options.get("closeDoor"), true));
        default:
            throw new IllegalArgumentException("Unknown goal: '" + name + "'; supported: " + GOAL_NAMES);
        }
    }

    // ---------- target ----------

    public void target(int priority, String name) {
        target(priority, name, null);
    }

    public void target(int priority, String name, Map<String, Object> options) {
        if (name == null) {
            throw new IllegalArgumentException("target name is required; supported: " + TARGET_NAMES);
        }
        targetSelector.addGoal(priority, buildTarget(name, options == null ? Collections.emptyMap() : options));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Goal buildTarget(String name, Map<String, Object> options) {
        switch (name) {
        case "nearest":
        case "nearest_attackable":
            Class<? extends LivingEntity> targetType =
                    resolveLivingClass(requireString(options.get("type"), "type"), name);
            return new NearestAttackableTargetGoal(mob, targetType,
                    asInt(options.get("interval"), 10),
                    asBoolean(options.get("mustSee"), true),
                    asBoolean(options.get("mustReach"), false),
                    null);
        case "hurt_by_target":
            return new HurtByTargetGoal(pathfinderMob(name));
        case "owner_hurt_by_target":
            return new OwnerHurtByTargetGoal(tamableAnimal(name));
        case "owner_hurt_target":
            return new OwnerHurtTargetGoal(tamableAnimal(name));
        case "reset_universal_anger":
            if (!(mob instanceof NeutralMob)) {
                throw new IllegalArgumentException(
                        "Target goal 'reset_universal_anger' requires a NeutralMob entity, but got "
                        + mob.getClass().getName());
            }
            return new ResetUniversalAngerTargetGoal((Mob & NeutralMob) mob,
                    asBoolean(options.get("alertOthers"), true));
        default:
            throw new IllegalArgumentException("Unknown target goal: '" + name + "'; supported: " + TARGET_NAMES);
        }
    }

    // ---------- custom ----------

    /** 传 Python 行为类（或实例）：类会先实例化（Python 2 不能直接调类上的实例方法）。 */
    public void custom(int priority, PyObject behavior) {
        goalSelector.addGoal(priority, new PythonGoal(mob, instantiate(behavior)));
    }

    private static PyObject instantiate(PyObject candidate) {
        // Python 2 新式类（class X(object)）在 Jython 里是 PyType：需要 __call__() 实例化
        if (candidate instanceof PyType) {
            return candidate.__call__();
        }
        return candidate;
    }

    // ---------- 取值工具 ----------

    private PathfinderMob pathfinderMob(String goalName) {
        if (mob instanceof PathfinderMob pathfinderMob) {
            return pathfinderMob;
        }
        throw new IllegalArgumentException(
                "Goal '" + goalName + "' requires a PathfinderMob entity, but got "
                + mob.getClass().getName());
    }

    private TamableAnimal tamableAnimal(String goalName) {
        if (mob instanceof TamableAnimal tamableAnimal) {
            return tamableAnimal;
        }
        throw new IllegalArgumentException(
                "Target goal '" + goalName + "' requires a TamableAnimal entity, but got "
                + mob.getClass().getName());
    }

    private Class<? extends LivingEntity> lookAtType(Map<String, Object> options) {
        Object raw = options.get("type");
        if (raw == null) {
            return Player.class;
        }
        return resolveLivingClass(requireString(raw, "type"), "look_at_player");
    }

    private static Class<? extends LivingEntity> resolveLivingClass(String entityId, String goalName) {
        EntityType<?> type = EntityRegistration.requireEntityType(entityId);
        Class<? extends Entity> base = type.getBaseClass();
        if (!LivingEntity.class.isAssignableFrom(base)) {
            throw new IllegalArgumentException(
                    "Entity '" + entityId + "' is not a LivingEntity; cannot be used as '"
                    + goalName + "' target");
        }
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> living = (Class<? extends LivingEntity>) base;
        return living;
    }
}
