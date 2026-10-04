package com.AlerCello86767.jython_language_runtime;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Python 专用的物品交互事件门面。
 *
 * <p>与命令门面同一模式：Python 只传「物品 id + 一个函数」，内部按物品 id 建分发表，
 * 并向 Fabric 事件注册唯一监听器。事件触发时用当前手持物品的 id 查表，命中才回调 Python。
 *
 * <p>用 id 查表而非缓存 Item 引用，因此不要求先注册物品；但物品不存在时会告警，
 * 且该条事件永远不会触发（多半是 id 写错了）。
 */
public final class ItemCallbacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/ItemCallbacks");

    private ItemCallbacks() {
    }

    /** 右键物品：{@code (player, level, hand) -> InteractionResult}。 */
    @FunctionalInterface
    public interface UseItemHandler {
        InteractionResult interact(Player player, Level level, InteractionHand hand);
    }

    /** 右键方块：{@code (player, level, hand, hitResult) -> InteractionResult}。 */
    @FunctionalInterface
    public interface UseBlockHandler {
        InteractionResult interact(Player player, Level level, InteractionHand hand, BlockHitResult hitResult);
    }

    /** 右键实体：{@code (player, level, hand, entity, hitResult) -> InteractionResult}。 */
    @FunctionalInterface
    public interface UseEntityHandler {
        InteractionResult interact(Player player, Level level, InteractionHand hand,
                                   Entity entity, EntityHitResult hitResult);
    }

    /** 左键实体：{@code (player, level, hand, entity, hitResult) -> InteractionResult}。 */
    @FunctionalInterface
    public interface AttackEntityHandler {
        InteractionResult interact(Player player, Level level, InteractionHand hand,
                                   Entity entity, EntityHitResult hitResult);
    }

    /** 左键方块：{@code (player, level, hand, pos, direction) -> InteractionResult}。 */
    @FunctionalInterface
    public interface AttackBlockHandler {
        InteractionResult interact(Player player, Level level, InteractionHand hand,
                                   BlockPos pos, Direction direction);
    }

    private static final Map<Identifier, UseItemHandler> USE_ITEM = new HashMap<>();
    private static final Map<Identifier, UseBlockHandler> USE_BLOCK = new HashMap<>();
    private static final Map<Identifier, UseEntityHandler> USE_ENTITY = new HashMap<>();
    private static final Map<Identifier, AttackEntityHandler> ATTACK_ENTITY = new HashMap<>();
    private static final Map<Identifier, AttackBlockHandler> ATTACK_BLOCK = new HashMap<>();

    static {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            UseItemHandler handler = lookup(USE_ITEM, player, hand);
            return handler == null ? InteractionResult.PASS : nullToPass(handler.interact(player, level, hand));
        });
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            UseBlockHandler handler = lookup(USE_BLOCK, player, hand);
            return handler == null ? InteractionResult.PASS : nullToPass(handler.interact(player, level, hand, hitResult));
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            UseEntityHandler handler = lookup(USE_ENTITY, player, hand);
            return handler == null ? InteractionResult.PASS : nullToPass(handler.interact(player, level, hand, entity, hitResult));
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            AttackEntityHandler handler = lookup(ATTACK_ENTITY, player, hand);
            return handler == null ? InteractionResult.PASS : nullToPass(handler.interact(player, level, hand, entity, hitResult));
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            AttackBlockHandler handler = lookup(ATTACK_BLOCK, player, hand);
            return handler == null ? InteractionResult.PASS : nullToPass(handler.interact(player, level, hand, pos, direction));
        });
    }

    /** 注册「右键物品」回调。 */
    public static void onUseItem(String itemId, UseItemHandler handler) {
        USE_ITEM.put(validate(itemId, "onUseItem"), handler);
    }

    /** 注册「右键方块」回调。 */
    public static void onUseBlock(String itemId, UseBlockHandler handler) {
        USE_BLOCK.put(validate(itemId, "onUseBlock"), handler);
    }

    /** 注册「右键实体」回调。 */
    public static void onUseEntity(String itemId, UseEntityHandler handler) {
        USE_ENTITY.put(validate(itemId, "onUseEntity"), handler);
    }

    /** 注册「左键实体」回调。 */
    public static void onAttackEntity(String itemId, AttackEntityHandler handler) {
        ATTACK_ENTITY.put(validate(itemId, "onAttackEntity"), handler);
    }

    /** 注册「左键方块」回调。 */
    public static void onAttackBlock(String itemId, AttackBlockHandler handler) {
        ATTACK_BLOCK.put(validate(itemId, "onAttackBlock"), handler);
    }

    private static Identifier validate(String itemId, String event) {
        Identifier id = ModIds.parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            LOGGER.warn("ItemCallbacks.{} 注册的物品 {} 尚未注册，该回调不会触发", event, id);
        }
        return id;
    }

    private static <H> H lookup(Map<Identifier, H> table, Player player, InteractionHand hand) {
        Identifier id = heldItemId(player, hand);
        return id == null ? null : table.get(id);
    }

    private static Identifier heldItemId(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    /** Python 回调返回 None 时视为 PASS。 */
    private static InteractionResult nullToPass(InteractionResult result) {
        return result == null ? InteractionResult.PASS : result;
    }
}
