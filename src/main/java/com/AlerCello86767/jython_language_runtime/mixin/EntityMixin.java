package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.world.entity.Entity;

/**
 * 「骑乘 / 脱离骑乘」事件的挂钩点（目标 Entity）。
 *
 * <p>骑乘：三参 {@code startRiding(Entity, boolean, boolean)} 是真实实现
 * （一参 final 版本只是转发），成功返回 true，RETURN 时转发。
 *
 * <p>脱离：所有脱离路径（主动下坐骑、载具销毁 ejectPassengers、跨维度）
 * 最终都汇聚到 {@code removeVehicle()}，HEAD 时 getVehicle() 仍指向旧载具。
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("RETURN"))
    private void pyModAfterStartRiding(Entity vehicle, boolean force, boolean riders,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue()) && GameEvents.hasStartRidingHandlers()) {
            GameEvents.fireStartRiding((Entity) (Object) this, vehicle);
        }
    }

    @Inject(method = "removeVehicle", at = @At("HEAD"))
    private void pyModOnRemoveVehicle(CallbackInfo ci) {
        if (!GameEvents.hasStopRidingHandlers()) {
            return;
        }
        Entity vehicle = ((Entity) (Object) this).getVehicle();
        if (vehicle != null) {
            GameEvents.fireStopRiding((Entity) (Object) this, vehicle);
        }
    }
}
