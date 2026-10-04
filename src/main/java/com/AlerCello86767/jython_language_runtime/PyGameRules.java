package com.AlerCello86767.jython_language_runtime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * 自定义游戏规则门面（{@code fabric-game-rule-api-v1}）：注册自己的 {@code /gamerule}，并在改动时收通知。
 *
 * <pre>
 * PyGameRules.intRule("ruby_yield", 1, 0, 64, "drops")     # 注册 /gamerule ruby_yield
 * PyGameRules.onChange("ruby_yield", fn)                    # fn(value, server)
 * n = PyGameRules.get(server, "ruby_yield")                 # 读当前值
 * PyGameRules.set(server, "ruby_yield", 8)                  # 改（等价于玩家执行 /gamerule）
 * </pre>
 *
 * <p>{@code path} 省略命名空间时默认 {@code jython_language_runtime:}（见 {@link ModIds#parse}）。注册与写值都必须在
 * {@code onInitialize} 阶段完成——游戏规则是注册表项，注册表冻结后就晚了。
 *
 * <p>{@code category} 决定这条规则出现在 {@code /gamerule} 列表的哪一组，取
 * {@code player / mobs / spawning / drops / updates / chat / misc}，传 {@code None} 按 {@code misc} 处理。
 */
public final class PyGameRules {
    private PyGameRules() {
    }

    /** 已注册的规则，按解析后的完整 id 索引（{@code "ruby_yield"} 与 {@code "jython_language_runtime:ruby_yield"} 都能查到）。 */
    private static final Map<String, GameRule<?>> RULES = new LinkedHashMap<>();

    /** {@code (newValue, server) -> void} */
    public interface ChangeHandler {
        void handle(Object value, MinecraftServer server);
    }

    /** 布尔规则，对应 {@code /gamerule <path> true|false}。 */
    public static void booleanRule(String path, boolean defaultValue, String category) {
        keep(path, GameRuleBuilder.forBoolean(defaultValue)
                .category(category(category))
                .buildAndRegister(id(path)));
    }

    /** 整数规则；{@code min}~{@code max} 是命令补全与校验的区间。 */
    public static void intRule(String path, int defaultValue, int min, int max, String category) {
        keep(path, GameRuleBuilder.forInteger(defaultValue)
                .range(min, max)
                .category(category(category))
                .buildAndRegister(id(path)));
    }

    /** 浮点规则；{@code min}~{@code max} 是命令补全与校验的区间。 */
    public static void doubleRule(String path, double defaultValue, double min, double max, String category) {
        keep(path, GameRuleBuilder.forDouble(defaultValue)
                .range(min, max)
                .category(category(category))
                .buildAndRegister(id(path)));
    }

    /** 读当前值；返回 {@code Boolean} / {@code Integer} / {@code Double}。 */
    public static Object get(MinecraftServer server, String path) {
        return read(server, require(path));
    }

    /** 改值，等价于玩家执行 {@code /gamerule}；字符串会自动转成规则对应的类型。 */
    public static void set(MinecraftServer server, String path, Object value) {
        GameRule<?> rule = require(path);
        write(server, rule, coerce(rule, value));
    }

    /** 规则值被改动后回调（包括玩家用命令改、以及 {@link #set}）。 */
    public static void onChange(String path, ChangeHandler handler) {
        GameRule<Object> rule = requireTyped(path);
        GameRuleEvents.changeCallback(rule).register((value, server) -> handler.handle(value, server));
    }

    // ---------- 内部 ----------

    private static Identifier id(String path) {
        return ModIds.parse(path);
    }

    private static void keep(String path, GameRule<?> rule) {
        if (RULES.putIfAbsent(id(path).toString(), rule) != null) {
            throw new IllegalStateException("游戏规则重复注册: " + path);
        }
    }

    private static GameRule<?> require(String path) {
        GameRule<?> rule = RULES.get(id(path).toString());
        if (rule == null) {
            throw new IllegalArgumentException("未注册的游戏规则: " + path
                    + "（先用 booleanRule / intRule / doubleRule 注册）");
        }
        return rule;
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Object> requireTyped(String path) {
        return (GameRule<Object>) require(path);
    }

    @SuppressWarnings("unchecked")
    private static Object read(MinecraftServer server, GameRule<?> rule) {
        return server.getGameRules().get((GameRule<Object>) rule);
    }

    @SuppressWarnings("unchecked")
    private static void write(MinecraftServer server, GameRule<?> rule, Object value) {
        server.getGameRules().set((GameRule<Object>) rule, value, server);
    }

    /** Python 传进来的可能是 bool / int / float / str，按规则的值类型收口。 */
    private static Object coerce(GameRule<?> rule, Object value) {
        Class<?> type = rule.valueClass();
        if (type == Boolean.class) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == Integer.class) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (type == Double.class) {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        return value;
    }

    /** 只支持原版这七个分类——自定义分类还要配套翻译键，性价比不高，不做。 */
    private static GameRuleCategory category(String name) {
        if (name == null) {
            return GameRuleCategory.MISC;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "player" -> GameRuleCategory.PLAYER;
            case "mobs" -> GameRuleCategory.MOBS;
            case "spawning" -> GameRuleCategory.SPAWNING;
            case "drops" -> GameRuleCategory.DROPS;
            case "updates" -> GameRuleCategory.UPDATES;
            case "chat" -> GameRuleCategory.CHAT;
            case "misc" -> GameRuleCategory.MISC;
            default -> throw new IllegalArgumentException("未知规则分类: " + name
                    + "（可用 player/mobs/spawning/drops/updates/chat/misc，或传 None）");
        };
    }
}
