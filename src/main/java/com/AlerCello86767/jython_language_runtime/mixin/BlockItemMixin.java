package com.AlerCello86767.jython_language_runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.AlerCello86767.jython_language_runtime.GameEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「方块被放置」事件的最小挂钩点。
 *
 * <p>Fabric / 原版都没有该事件，只能注入 {@link BlockItem#place}：它在放置成功时返回
 * {@link InteractionResult#SUCCESS}（{@code consumesAction()} 为真），失败返回 {@code FAIL}。
 * 这里只把成功的情况转给 {@link GameEvents}，服务端才触发，避免双端回调两次。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void pyModAfterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;
        }
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || level.isClientSide()) {
            return;
        }
        // BlockPlaceContext.getClickedPos() 已是放置位置（构造时按可替换性/朝向算好了）
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        GameEvents.fireBlockPlaced(level, player, pos, state);
    }
}
