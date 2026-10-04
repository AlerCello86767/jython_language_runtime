package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * 「切换主手槽位」事件的挂钩点（目标 ServerGamePacketListenerImpl）。
 *
 * <p>手持槽的服务端变更只可能来自 {@code handleSetCarriedItem}。
 * 注入点选在 {@code Inventory.setSelectedSlot} 调用之前：此时已通过
 * {@code PacketUtils.ensureRunningOnSameThread}（保证在主线程执行）且槽位范围已校验，
 * 旧槽位还未被覆盖。<b>不能用 HEAD</b>——数据包在 netty 线程的首次进入会先跑 HEAD，
 * 调度到主线程重入时再跑一次，导致事件双发。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(
            method = "handleSetCarriedItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"
            )
    )
    private void pyModOnHeldSlotChange(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (!GameEvents.hasHeldSlotChangeHandlers()) {
            return;
        }
        int oldSlot = this.player.getInventory().getSelectedSlot();
        int newSlot = packet.getSlot();
        if (newSlot != oldSlot) {
            GameEvents.fireHeldSlotChange(this.player, oldSlot, newSlot);
        }
    }
}
