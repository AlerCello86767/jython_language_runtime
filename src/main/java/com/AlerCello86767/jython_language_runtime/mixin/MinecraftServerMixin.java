package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.server.MinecraftServer;

/**
 * 「天气变化」事件的挂钩点（目标 MinecraftServer）。
 *
 * <p>{@code setWeatherParameters(int, int, boolean, boolean)} 只在显式改天气时被调用
 * （/weather 命令），自然天气循环的起止不走这里。HEAD 时转发 rain/thunder。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "setWeatherParameters", at = @At("HEAD"))
    private void pyModOnWeatherChange(int clearTime, int weatherTime,
                                      boolean rain, boolean thunder, CallbackInfo ci) {
        if (GameEvents.hasWeatherChangeHandlers()) {
            GameEvents.fireWeatherChange((MinecraftServer) (Object) this, rain, thunder);
        }
    }
}
