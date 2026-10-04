package com.AlerCello86767.jython_language_runtime;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 世界 / 服务器 / 玩家 / 实体事件门面（B 方案）。
 *
 * <p>Python 直接丢一个函数即可，不需要继承任何 Java 类型。函数在**服务端主线程**被回调。
 *
 * <p>设计说明：门面内部统一用方法引用（{@code h::handle}）适配 Fabric 自己的函数式接口，
 * 避免让 Jython 去为 Fabric 的接口生成代理——这是本项目验证过的做法（见 CommandRegistrationCallback）。
 *
 * <p>同形签名的事件共用同一个接口，所以 {@link ServerHandler} / {@link AllowDamageHandler}
 * 之类会被多个注册方法复用。
 */
public final class GameEvents {
    private GameEvents() {
    }

    // ---------- 回调接口（按签名形状分组，同形的复用） ----------

    /** {@code (server) -> void} */
    public interface ServerHandler {
        void handle(MinecraftServer server);
    }

    /** {@code (level) -> void} */
    public interface LevelHandler {
        void handle(ServerLevel level);
    }

    /** {@code (entity, level) -> void} */
    public interface EntityLevelHandler {
        void handle(Entity entity, ServerLevel level);
    }

    /** {@code (server, flush, force) -> void} */
    public interface SaveHandler {
        void handle(MinecraftServer server, boolean flush, boolean force);
    }

    /** {@code (player) -> void} */
    public interface PlayerHandler {
        void handle(ServerPlayer player);
    }

    /** {@code (oldPlayer, newPlayer, alive) -> void} */
    public interface RespawnHandler {
        void handle(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive);
    }

    /** {@code (entity, source, baseDamage, damage, blocked) -> void} */
    public interface DamageHandler {
        void handle(LivingEntity entity, DamageSource source,
                    float baseDamage, float damage, boolean blocked);
    }

    /** {@code (entity, source) -> void} */
    public interface DeathHandler {
        void handle(LivingEntity entity, DamageSource source);
    }

    /** {@code (entity, source, amount) -> boolean}；返回 false 取消这次伤害/死亡。 */
    public interface AllowDamageHandler {
        boolean allow(LivingEntity entity, DamageSource source, float amount);
    }

    /** {@code (entity, slot, previous, current) -> void} */
    public interface EquipmentHandler {
        void handle(LivingEntity entity, EquipmentSlot slot, ItemStack previous, ItemStack current);
    }

    // ---------- 服务器生命周期 / 保存 ----------

    public static void onServerStarting(ServerHandler handler) {
        ServerLifecycleEvents.SERVER_STARTING.register(handler::handle);
    }

    public static void onServerStarted(ServerHandler handler) {
        ServerLifecycleEvents.SERVER_STARTED.register(handler::handle);
    }

    public static void onServerStopping(ServerHandler handler) {
        ServerLifecycleEvents.SERVER_STOPPING.register(handler::handle);
    }

    public static void onServerStopped(ServerHandler handler) {
        ServerLifecycleEvents.SERVER_STOPPED.register(handler::handle);
    }

    public static void onBeforeSave(SaveHandler handler) {
        ServerLifecycleEvents.BEFORE_SAVE.register(handler::handle);
    }

    public static void onAfterSave(SaveHandler handler) {
        ServerLifecycleEvents.AFTER_SAVE.register(handler::handle);
    }

    // ---------- tick ----------

    public static void onServerTickStart(ServerHandler handler) {
        ServerTickEvents.START_SERVER_TICK.register(handler::handle);
    }

    public static void onServerTickEnd(ServerHandler handler) {
        ServerTickEvents.END_SERVER_TICK.register(handler::handle);
    }

    /** 每个已加载维度各回调一次（相当于"世界 tick"）。 */
    public static void onLevelTickStart(LevelHandler handler) {
        ServerTickEvents.START_LEVEL_TICK.register(handler::handle);
    }

    public static void onLevelTickEnd(LevelHandler handler) {
        ServerTickEvents.END_LEVEL_TICK.register(handler::handle);
    }

    // ---------- 实体加载 / 卸载 / 装备变化 ----------

    public static void onEntityLoad(EntityLevelHandler handler) {
        ServerEntityEvents.ENTITY_LOAD.register(handler::handle);
    }

    public static void onEntityUnload(EntityLevelHandler handler) {
        ServerEntityEvents.ENTITY_UNLOAD.register(handler::handle);
    }

    public static void onEquipmentChange(EquipmentHandler handler) {
        ServerEntityEvents.EQUIPMENT_CHANGE.register(handler::handle);
    }

    // ---------- 生物受伤 / 死亡 ----------

    public static void onLivingAllowDamage(AllowDamageHandler handler) {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(handler::allow);
    }

    public static void onLivingAfterDamage(DamageHandler handler) {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(handler::handle);
    }

    public static void onLivingAfterDeath(DeathHandler handler) {
        ServerLivingEntityEvents.AFTER_DEATH.register(handler::handle);
    }

    /** 返回 false 取消该生物的死亡。 */
    public static void onLivingAllowDeath(AllowDamageHandler handler) {
        ServerLivingEntityEvents.ALLOW_DEATH.register(handler::allow);
    }

    // ---------- 玩家 ----------

    public static void onPlayerJoin(PlayerHandler handler) {
        ServerPlayerEvents.JOIN.register(handler::handle);
    }

    public static void onPlayerLeave(PlayerHandler handler) {
        ServerPlayerEvents.LEAVE.register(handler::handle);
    }

    public static void onPlayerRespawn(RespawnHandler handler) {
        ServerPlayerEvents.AFTER_RESPAWN.register(handler::handle);
    }

    /**
     * 返回 false 取消该玩家的死亡（仅对玩家生效，其它生物不受影响）。
     *
     * <p>实现走通用的 {@link ServerLivingEntityEvents#ALLOW_DEATH} 并做 instanceof 过滤：
     * {@code ServerPlayerEvents.ALLOW_DEATH} 在本版本已标记 {@code @Deprecated}，不再使用。
     */
    public static void onPlayerAllowDeath(AllowDamageHandler handler) {
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                !(entity instanceof ServerPlayer) || handler.allow(entity, source, amount));
    }

    // ---------- 方块破坏 ----------

    /** {@code (level, player, pos, state, blockEntity) -> void}；blockEntity 可能为 null。 */
    public interface BlockBreakHandler {
        void handle(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity);
    }

    /** 同形，但返回 boolean；返回 false 取消破坏。 */
    public interface AllowBlockBreakHandler {
        boolean allow(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity);
    }

    /** 破坏**之前**回调；返回 false 可取消破坏。 */
    public static void onBlockBreakBefore(AllowBlockBreakHandler handler) {
        PlayerBlockBreakEvents.BEFORE.register(handler::allow);
    }

    /** 破坏完成后回调（方块已移除）。 */
    public static void onBlockBreakAfter(BlockBreakHandler handler) {
        PlayerBlockBreakEvents.AFTER.register(handler::handle);
    }

    /** 破坏被取消后回调（例如 BEFORE 里返回了 false）。 */
    public static void onBlockBreakCanceled(BlockBreakHandler handler) {
        PlayerBlockBreakEvents.CANCELED.register(handler::handle);
    }

    // ---------- 方块放置 ----------

    /** {@code (level, player, pos, state) -> void}；pos 是方块实际放置的位置。 */
    public interface BlockPlaceHandler {
        void handle(Level level, Player player, BlockPos pos, BlockState state);
    }

    private static final List<BlockPlaceHandler> BLOCK_PLACE_HANDLERS = new ArrayList<>();

    /**
     * 方块被玩家放置后回调。
     *
     * <p>Fabric 没有提供该事件，由 {@code mixin/BlockItemMixin} 在 {@code BlockItem#place}
     * 成功返回后触发，只对「放置成功」回调。发射器之类的非玩家放置不会进入这里。
     */
    public static void onBlockPlace(BlockPlaceHandler handler) {
        BLOCK_PLACE_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireBlockPlaced(Level level, Player player, BlockPos pos, BlockState state) {
        for (BlockPlaceHandler handler : BLOCK_PLACE_HANDLERS) {
            handler.handle(level, player, pos, state);
        }
    }

    // ---------- 物品拾取 ----------

    /** {@code (player, stack, amount) -> void}；stack 是拾取后场上剩余的那堆，全拾完则为空堆。 */
    public interface ItemPickupHandler {
        void handle(Player player, ItemStack stack, int amount);
    }

    private static final List<ItemPickupHandler> ITEM_PICKUP_HANDLERS = new ArrayList<>();

    /**
     * 玩家拾取掉落物后回调；一次拾取只回调一次，{@code amount} 为本次实际收到的数量。
     *
     * <p>同样由 {@code mixin/ItemEntityMixin} 挂钩，Fabric 没有现成事件。
     */
    public static void onItemPickup(ItemPickupHandler handler) {
        ITEM_PICKUP_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireItemPickup(Player player, ItemStack stack, int amount) {
        for (ItemPickupHandler handler : ITEM_PICKUP_HANDLERS) {
            handler.handle(player, stack, amount);
        }
    }

    // ---------- 睡觉 ----------

    /** {@code (entity, pos) -> void} */
    public interface SleepHandler {
        void handle(LivingEntity entity, BlockPos pos);
    }

    /**
     * {@code (player, pos) -> str}；返回 {@code None} 允许入睡，
     * 返回 {@code "too_far_away" / "obstructed" / "other_problem" / "not_safe"} 之一则拒绝并提示对应原因。
     */
    public interface AllowSleepHandler {
        String allow(Player player, BlockPos pos);
    }

    /** 开始睡觉（已躺下）后回调。 */
    public static void onStartSleeping(SleepHandler handler) {
        EntitySleepEvents.START_SLEEPING.register(handler::handle);
    }

    /** 起床（不再处于睡眠状态）后回调。 */
    public static void onStopSleeping(SleepHandler handler) {
        EntitySleepEvents.STOP_SLEEPING.register(handler::handle);
    }

    /** 入睡前回调，可拒绝。 */
    public static void onAllowSleep(AllowSleepHandler handler) {
        EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> sleepProblem(handler.allow(player, pos)));
    }

    /** {@code null} 表示放行；未知名称按「其它原因」处理。 */
    private static Player.BedSleepingProblem sleepProblem(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "too_far_away" -> Player.BedSleepingProblem.TOO_FAR_AWAY;
            case "obstructed" -> Player.BedSleepingProblem.OBSTRUCTED;
            case "not_safe" -> Player.BedSleepingProblem.NOT_SAFE;
            default -> Player.BedSleepingProblem.OTHER_PROBLEM;
        };
    }

    // ---------- 跨维度传送 ----------

    /** {@code (originalEntity, newEntity, origin, destination) -> void}；玩家不走这个，见 onPlayerChangeLevel。 */
    public interface EntityChangeLevelHandler {
        void handle(Entity originalEntity, Entity newEntity, ServerLevel origin, ServerLevel destination);
    }

    /** {@code (player, origin, destination) -> void} */
    public interface PlayerChangeLevelHandler {
        void handle(ServerPlayer player, ServerLevel origin, ServerLevel destination);
    }

    /**
     * 非玩家实体跨维度后回调：原实体在旧维度被移除、新实体在新维度重建。
     *
     * <p>同类名实体不触发（例如主世界↔下界之外的普通移动）。
     */
    public static void onEntityChangeLevel(EntityChangeLevelHandler handler) {
        ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(handler::handle);
    }

    /** 玩家跨维度后回调（含重生到其它维度；此时传入的是新玩家实例）。 */
    public static void onPlayerChangeLevel(PlayerChangeLevelHandler handler) {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(handler::handle);
    }

    // ---------- 世界加载 / 卸载 ----------

    /** {@code (server, level) -> void}；每个服务端世界首次加载 / 卸载时各一次。 */
    public interface ServerLevelLoadHandler {
        void handle(MinecraftServer server, ServerLevel level);
    }

    /**
     * 服务端世界加载完成后回调（{@code ServerLevelEvents.LOAD}；
     * 旧文档里的 {@code ServerWorldEvents} 在本版本已随 World→Level 改名）。
     */
    public static void onLevelLoad(ServerLevelLoadHandler handler) {
        ServerLevelEvents.LOAD.register(handler::handle);
    }

    /** 服务端世界卸载前回调（世界保存并卸载时）。 */
    public static void onLevelUnload(ServerLevelLoadHandler handler) {
        ServerLevelEvents.UNLOAD.register(handler::handle);
    }

    // ---------- 方块实体加载 / 卸载 ----------

    /** {@code (blockEntity, level) -> void}；随区块加载 / 卸载触发。 */
    public interface BlockEntityLoadHandler {
        void handle(BlockEntity blockEntity, ServerLevel level);
    }

    /** 方块实体随区块进入可服务状态时回调（区块加载）。 */
    public static void onBlockEntityLoad(BlockEntityLoadHandler handler) {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(handler::handle);
    }

    /** 方块实体随区块卸载时回调（此时还能读到它最后的数据）。 */
    public static void onBlockEntityUnload(BlockEntityLoadHandler handler) {
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(handler::handle);
    }

    // ---------- 玩家丢弃物品 ----------

    /** {@code (player, stack) -> void}；stack 是被丢到场上的物品（完整一份）。 */
    public interface ItemDropHandler {
        void handle(Player player, ItemStack stack);
    }

    private static final List<ItemDropHandler> ITEM_DROP_HANDLERS = new ArrayList<>();

    /**
     * 玩家丢弃物品后回调（Q 键整丢/单丢均触发）。
     *
     * <p>由 {@code mixin/LivingEntityMixin} 在 {@code LivingEntity.drop} 返回后触发，
     * 只对玩家且成功生成掉落物的情况回调；死亡掉落走另一条路径，不在此列。
     */
    public static void onItemDrop(ItemDropHandler handler) {
        ITEM_DROP_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireItemDrop(Player player, ItemStack stack) {
        for (ItemDropHandler handler : ITEM_DROP_HANDLERS) {
            handler.handle(player, stack);
        }
    }

    // ---------- 玩家跳跃 ----------

    /** {@code (player) -> void} */
    public interface PlayerJumpHandler {
        void handle(Player player);
    }

    private static final List<PlayerJumpHandler> PLAYER_JUMP_HANDLERS = new ArrayList<>();

    /**
     * 玩家起跳时回调（{@code LivingEntity.jumpFromGround} HEAD）。
     *
     * <p>1.21 起玩家输入（含跳跃）会同步给服务端，所以该事件在服务端也会触发；
     * 只对玩家回调，其它生物的跳跃不触发。
     */
    public static void onPlayerJump(PlayerJumpHandler handler) {
        PLAYER_JUMP_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void firePlayerJump(Player player) {
        for (PlayerJumpHandler handler : PLAYER_JUMP_HANDLERS) {
            handler.handle(player);
        }
    }

    // ---------- 骑乘 / 取消骑乘 ----------

    /** {@code (entity, vehicle) -> void}；实体不做玩家过滤，生物被塞进载具也会触发。 */
    public interface RidingHandler {
        void handle(Entity entity, Entity vehicle);
    }

    private static final List<RidingHandler> START_RIDING_HANDLERS = new ArrayList<>();
    private static final List<RidingHandler> STOP_RIDING_HANDLERS = new ArrayList<>();

    /** 实体成功骑上载具后回调（{@code startRiding} 返回 true）。 */
    public static void onStartRiding(RidingHandler handler) {
        START_RIDING_HANDLERS.add(handler);
    }

    /** 实体脱离载具时回调；所有脱离路径最终都走 {@code removeVehicle}。 */
    public static void onStopRiding(RidingHandler handler) {
        STOP_RIDING_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireStartRiding(Entity entity, Entity vehicle) {
        for (RidingHandler handler : START_RIDING_HANDLERS) {
            handler.handle(entity, vehicle);
        }
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireStopRiding(Entity entity, Entity vehicle) {
        for (RidingHandler handler : STOP_RIDING_HANDLERS) {
            handler.handle(entity, vehicle);
        }
    }

    // ---------- 钓鱼 ----------

    /** {@code (player, luck, lure) -> void}；luck/lure 来自鱼竿附魔。 */
    public interface FishCastHandler {
        void handle(Player player, int luck, int lure);
    }

    /** {@code (player, caught) -> void}；收杆时回调，caught 为钓上的物品，空杆时为空堆。 */
    public interface FishRetrieveHandler {
        void handle(Player player, ItemStack caught);
    }

    private static final List<FishCastHandler> FISH_CAST_HANDLERS = new ArrayList<>();
    private static final List<FishRetrieveHandler> FISH_RETRIEVE_HANDLERS = new ArrayList<>();

    /** 玩家抛竿后回调（钩子实体生成时）。 */
    public static void onFishCast(FishCastHandler handler) {
        FISH_CAST_HANDLERS.add(handler);
    }

    /** 玩家收杆时回调（无论是否钓上东西）。 */
    public static void onFishRetrieve(FishRetrieveHandler handler) {
        FISH_RETRIEVE_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireFishCast(Player player, int luck, int lure) {
        for (FishCastHandler handler : FISH_CAST_HANDLERS) {
            handler.handle(player, luck, lure);
        }
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireFishRetrieve(Player player, ItemStack caught) {
        for (FishRetrieveHandler handler : FISH_RETRIEVE_HANDLERS) {
            handler.handle(player, caught);
        }
    }

    // ---------- 切换主手槽位 ----------

    /** {@code (player, oldSlot, newSlot) -> void}；槽位 0–8。 */
    public interface HeldSlotChangeHandler {
        void handle(ServerPlayer player, int oldSlot, int newSlot);
    }

    private static final List<HeldSlotChangeHandler> HELD_SLOT_HANDLERS = new ArrayList<>();

    /**
     * 玩家切换手持槽位（滚轮/数字键）后回调，槽位真的变化时才触发。
     *
     * <p>由 {@code mixin/ServerGamePacketListenerMixin} 在 {@code handleSetCarriedItem} 处触发。
     */
    public static void onHeldSlotChange(HeldSlotChangeHandler handler) {
        HELD_SLOT_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireHeldSlotChange(ServerPlayer player, int oldSlot, int newSlot) {
        for (HeldSlotChangeHandler handler : HELD_SLOT_HANDLERS) {
            handler.handle(player, oldSlot, newSlot);
        }
    }

    // ---------- 天气变化 ----------

    /** {@code (server, rain, thunder) -> void} */
    public interface WeatherChangeHandler {
        void handle(MinecraftServer server, boolean rain, boolean thunder);
    }

    private static final List<WeatherChangeHandler> WEATHER_HANDLERS = new ArrayList<>();

    /**
     * 天气被改变后回调（{@code /weather} 命令；自然天气循环的起止不走这里）。
     *
     * <p>由 {@code mixin/MinecraftServerMixin} 在 {@code setWeatherParameters} HEAD 触发。
     */
    public static void onWeatherChange(WeatherChangeHandler handler) {
        WEATHER_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireWeatherChange(MinecraftServer server, boolean rain, boolean thunder) {
        for (WeatherChangeHandler handler : WEATHER_HANDLERS) {
            handler.handle(server, rain, thunder);
        }
    }

    // ---------- 时间变化 ----------

    /** {@code (clockId, totalTicks) -> void}；clockId 如 "minecraft:overworld"。 */
    public interface TimeChangeHandler {
        void handle(String clockId, long totalTicks);
    }

    private static final List<TimeChangeHandler> TIME_HANDLERS = new ArrayList<>();

    /**
     * 世界时钟时间被改变后回调（{@code /time set|add|set <marker>}）。
     *
     * <p>26.1 的昼夜时间已改由 {@code ServerClockManager} + WorldClock/Timeline 数据驱动，
     * 由 {@code mixin/ServerClockManagerMixin} 在三处修改入口返回后触发。
     * 自然流逝不在此列。
     */
    public static void onTimeChange(TimeChangeHandler handler) {
        TIME_HANDLERS.add(handler);
    }

    /** 内部：由 mixin 调用，服务端主线程。 */
    public static void fireTimeChange(String clockId, long totalTicks) {
        for (TimeChangeHandler handler : TIME_HANDLERS) {
            handler.handle(clockId, totalTicks);
        }
    }
}
