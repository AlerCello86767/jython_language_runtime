package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;

/**
 * 「时间变化」事件的挂钩点（目标 ServerClockManager）。
 *
 * <p>26.1 的昼夜时间已改由时钟系统（WorldClock / Timeline，数据驱动）管理，
 * 显式改时间的入口只有三个：{@code setTotalTicks}（/time set）、
 * {@code addTicks}（/time add）、{@code moveToTimeMarker}（/time set day/night/...）。
 * 三处 RETURN 后统一转发当前时钟 id 与最新总刻度；自然流逝不在此列。
 */
@Mixin(ServerClockManager.class)
public abstract class ServerClockManagerMixin {
    @Inject(method = "setTotalTicks", at = @At("RETURN"))
    private void pyModOnSetTotalTicks(Holder<WorldClock> clock, long totalTicks, CallbackInfo ci) {
        if (GameEvents.hasTimeChangeHandlers()) {
            GameEvents.fireTimeChange(clock.getRegisteredName(), totalTicks);
        }
    }

    @Inject(method = "addTicks", at = @At("RETURN"))
    private void pyModOnAddTicks(Holder<WorldClock> clock, int ticks, CallbackInfo ci) {
        if (!GameEvents.hasTimeChangeHandlers()) {
            return;
        }
        ServerClockManager self = (ServerClockManager) (Object) this;
        GameEvents.fireTimeChange(clock.getRegisteredName(), self.getTotalTicks(clock));
    }

    @Inject(method = "moveToTimeMarker", at = @At("RETURN"))
    private void pyModOnMoveToTimeMarker(Holder<WorldClock> clock,
                                         ResourceKey<?> timeMarker, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) && GameEvents.hasTimeChangeHandlers()) {
            ServerClockManager self = (ServerClockManager) (Object) this;
            GameEvents.fireTimeChange(clock.getRegisteredName(), self.getTotalTicks(clock));
        }
    }
}
