package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 钓鱼「抛竿」与「收杆」事件的挂钩点（目标 FishingHook）。
 *
 * <p>抛竿：4 参构造 (Player, Level, luck, lure) 只在服务端被 FishingRodItem 调用，
 * TAIL 时转发；luck/lure 直接来自鱼竿附魔。
 *
 * <p>收杆：{@code retrieve(ItemStack)} HEAD。此时 {@code getHookedIn()} 若为 ItemEntity
 * 就是即将到手的渔获；空杆 / 钩到实体时按空堆回调。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;II)V",
            at = @At("TAIL"))
    private void pyModOnCast(Player player, Level level, int luck, int lure, CallbackInfo ci) {
        if (GameEvents.hasFishCastHandlers() && !level.isClientSide()) {
            GameEvents.fireFishCast(player, luck, lure);
        }
    }

    @Inject(method = "retrieve", at = @At("HEAD"))
    private void pyModOnRetrieve(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        if (!GameEvents.hasFishRetrieveHandlers()) {
            return;
        }
        FishingHook self = (FishingHook) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        Player owner = self.getPlayerOwner();
        if (owner == null) {
            return;
        }
        ItemStack caught = ItemStack.EMPTY;
        Entity hooked = self.getHookedIn();
        if (hooked instanceof ItemEntity itemEntity) {
            caught = itemEntity.getItem();
        }
        GameEvents.fireFishRetrieve(owner, caught);
    }
}
