package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「玩家拾取掉落物」事件的最小挂钩点。
 *
 * <p>原版拾取全部走 {@link ItemEntity#playerTouch(Player)}：把物品塞进背包、可能整堆移除。
 * 该方法内部逻辑随版本变动，所以不猜测实现——进入前记下堆叠数，返回后比差值即为本次拾取量。
 * 全拾完时实体已被移除，剩余量按 0 算。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Unique
    private int pyModCountBeforeTouch;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void pyModBeforeTouch(Player player, CallbackInfo ci) {
        if (!GameEvents.hasItemPickupHandlers()) {
            return;
        }
        pyModCountBeforeTouch = ((ItemEntity) (Object) this).getItem().getCount();
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void pyModAfterTouch(Player player, CallbackInfo ci) {
        if (!GameEvents.hasItemPickupHandlers()) {
            return;
        }
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        int remaining = self.isRemoved() ? 0 : self.getItem().getCount();
        int picked = pyModCountBeforeTouch - remaining;
        if (picked > 0) {
            GameEvents.fireItemPickup(player, self.isRemoved() ? ItemStack.EMPTY : self.getItem(), picked);
        }
    }
}
