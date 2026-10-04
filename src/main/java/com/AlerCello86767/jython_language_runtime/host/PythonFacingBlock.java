package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 带水平朝向的方块宿主：声明 {@code facing} 属性，放置时正面朝向玩家。
 *
 * <p><b>为什么必须是独立子类</b>：{@code createBlockStateDefinition} 是在 {@code Block}
 * 构造函数（即 {@code super()}）期间被调用的，那时子类字段还没赋值。所以「是否声明朝向」
 * 不能靠构造参数判断——只能靠覆写方法本身来区分。这也是原版每个带朝向的方块都单独
 * 覆写它的原因。
 *
 * <p>默认朝向为北（与原版熔炉一致），放置时取玩家水平朝向的反方向（正面朝人）。
 *
 * <p><b>资源侧要求</b>：方块状态 JSON 必须给出 4 个朝向的变体，否则缺失的朝向渲染不出来：
 * <pre>
 * { "variants": {
 *     "facing=north": { "model": "jython_language_runtime:block/your_block" },
 *     "facing=east":  { "model": "jython_language_runtime:block/your_block", "y": 90 },
 *     "facing=south": { "model": "jython_language_runtime:block/your_block", "y": 180 },
 *     "facing=west":  { "model": "jython_language_runtime:block/your_block", "y": 270 }
 * } }
 * </pre>
 */
public class PythonFacingBlock extends PythonBlock {
    public PythonFacingBlock(BlockBehaviour.Properties properties, PyObject behaviorClass,
                             boolean ticking, boolean sync, int containerSize) {
        super(properties, behaviorClass, ticking, sync, containerSize);
        // super() 期间属性已注册完成，此时才能把它写进默认状态
        registerDefaultState(defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, context.getHorizontalDirection().getOpposite());
    }
}
