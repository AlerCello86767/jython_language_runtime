package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 转发宿主：把 {@link Item} 的可重写方法转发给一个 Python 对象。
 *
 * <p>Python 侧定义与 Java 方法同名的函数即可；未定义的方法走 super 的原版行为，
 * 因此不必一次实现全部方法。判定方式是 {@link PyObject#__findattr__(String)} 是否返回非 null。
 *
 * <p>返回值必须是 Java 类型：Python 侧可 {@code from net.minecraft.world import InteractionResult}
 * 后返回 {@code InteractionResult.SUCCESS}。返回 {@code None} 视为「未处理」，回退到 super。
 * 这些 net.minecraft 类型由 Knot 定义，不受 loader 包的父加载器委托问题影响。
 *
 * <p>已知语义约束：{@code inventoryTick} 在 26.1 只在服务端（ServerLevel）被调用。
 */
public class DelegatingItem extends Item {
    private final PyHandles handles;

    public DelegatingItem(Item.Properties properties, PyObject handler) {
        super(properties);
        // H1/H3：构造期一次性预解析全部可重写方法。物品每种只建一个宿主，
        // 之后每个高频钩子（inventoryTick / onUseTick …）只剩一次 map 查找；
        // 未实现的方法同样被缓存，不会再跨界。
        this.handles = new PyHandles(handler);
        this.handles.preload("use", "useOn", "interactLivingEntity", "finishUsingItem",
                "releaseUsing", "getUseDuration", "getUseAnimation", "onUseTick",
                "hurtEnemy", "postHurtEnemy", "mineBlock", "getDestroySpeed",
                "inventoryTick", "onCraftedBy", "isFoil");
    }

    // ---------- 右键使用 ----------

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        InteractionResult result = forward("use", InteractionResult.class, level, player, hand);
        return result != null ? result : super.use(level, player, hand);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = forward("useOn", InteractionResult.class, context);
        return result != null ? result : super.useOn(context);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        InteractionResult result = forward("interactLivingEntity", InteractionResult.class,
                stack, player, target, hand);
        return result != null ? result : super.interactLivingEntity(stack, player, target, hand);
    }

    // ---------- 持续使用 ----------

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack result = forward("finishUsingItem", ItemStack.class, stack, level, entity);
        return result != null ? result : super.finishUsingItem(stack, level, entity);
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        Boolean result = forward("releaseUsing", Boolean.class, stack, level, entity, timeLeft);
        return result != null ? result : super.releaseUsing(stack, level, entity, timeLeft);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        Integer result = forward("getUseDuration", Integer.class, stack, entity);
        return result != null ? result : super.getUseDuration(stack, entity);
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        ItemUseAnimation result = forward("getUseAnimation", ItemUseAnimation.class, stack);
        return result != null ? result : super.getUseAnimation(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!call("onUseTick", level, entity, stack, remaining)) {
            super.onUseTick(level, entity, stack, remaining);
        }
    }

    // ---------- 攻击 / 挖掘 ----------

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (!call("hurtEnemy", stack, target, attacker)) {
            super.hurtEnemy(stack, target, attacker);
        }
    }

    @Override
    public void postHurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (!call("postHurtEnemy", stack, target, attacker)) {
            super.postHurtEnemy(stack, target, attacker);
        }
    }

    @Override
    public boolean mineBlock(ItemStack stack, Level level, BlockState state,
                             BlockPos pos, LivingEntity miner) {
        Boolean result = forward("mineBlock", Boolean.class, stack, level, state, pos, miner);
        return result != null ? result : super.mineBlock(stack, level, state, pos, miner);
    }

    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        Float result = forward("getDestroySpeed", Float.class, stack, state);
        return result != null ? result : super.getDestroySpeed(stack, state);
    }

    // ---------- 其它 ----------

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (!call("inventoryTick", stack, level, entity, slot)) {
            super.inventoryTick(stack, level, entity, slot);
        }
    }

    @Override
    public void onCraftedBy(ItemStack stack, Player player) {
        if (!call("onCraftedBy", stack, player)) {
            super.onCraftedBy(stack, player);
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        Boolean result = forward("isFoil", Boolean.class, stack);
        return result != null ? result : super.isFoil(stack);
    }

    // ---------- 转发内核（调用与返回值约定见 PyHandles） ----------

    private <T> T forward(String name, Class<T> type, Object... args) {
        return handles.forward(name, type, args);
    }

    private boolean call(String name, Object... args) {
        return handles.call(name, args);
    }
}
