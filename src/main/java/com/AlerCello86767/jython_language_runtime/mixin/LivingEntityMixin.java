package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家「丢弃物品」与「跳跃」两个事件的挂钩点（同一目标类 LivingEntity）。
 *
 * <p>丢弃：{@code drop(ItemStack, boolean, boolean)} 是所有「实体生成掉落物」的统一入口，
 * 成功时在 RETURN 返回 ItemEntity；这里只对玩家转发，且只在服务端。
 *
 * <p>跳跃：{@code jumpFromGround()} HEAD。1.21 起玩家输入会同步到服务端，
 * 服务端 travel 中会对玩家调该方法，所以事件在服务端也能触发。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "drop", at = @At("RETURN"))
    private void pyModAfterDrop(ItemStack stack, boolean randomly, boolean thrownFromHand,
                                CallbackInfoReturnable<ItemEntity> cir) {
        ItemEntity dropped = cir.getReturnValue();
        if (dropped != null && (Object) this instanceof Player player
                && !player.level().isClientSide()) {
            GameEvents.fireItemDrop(player, dropped.getItem());
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"))
    private void pyModOnJump(CallbackInfo ci) {
        if ((Object) this instanceof Player player && !player.level().isClientSide()) {
            GameEvents.firePlayerJump(player);
        }
    }
}
