package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块宿主（P10）：承载 Python 行为类，并在需要时提供方块实体。
 *
 * <p>{@code behaviorClass} 是 Python 类，**每个方块实体实例化一份**，所以实例状态天然隔离
 * （不会像共享单例那样多个方块互相串数据）。宿主可用钩子见 {@link PythonBlockEntity}。
 *
 * <p>{@code behaviorClass} 可以为 {@code null}：此时方块不创建方块实体，
 * 只作为普通方块使用（带朝向的方块走这条路时仍需本宿主，见 {@link PythonFacingBlock}）。
 *
 * <p>时序说明：BlockEntityType 需要方块实例才能构建，而方块又要能创建自己的方块实体，
 * 两者互相依赖。这里由门面在建好类型后经 {@link #attachEntityType} 回填——
 * {@code newBlockEntity} 只在世界加载后才会被调用，因此回填一定早于使用。
 */
public class PythonBlock extends Block implements EntityBlock {
    private final PyObject behaviorClass;
    private final boolean ticking;
    /** H2：行为类是否真的实现了 {@code tick}（构造期探测一次）。未实现就不挂 ticker，避免每 tick 空跑。 */
    private final boolean hasTick;
    /** H11：行为类是否实现了任何方块实体钩子；都没有就不创建 Python 对象（纯占位/纯容器方块）。 */
    private final boolean hasHooks;
    private final boolean sync;
    private final int containerSize;
    private BlockEntityType<?> entityType;

    public PythonBlock(BlockBehaviour.Properties properties, PyObject behaviorClass,
                       boolean ticking, boolean sync, int containerSize) {
        super(properties);
        this.behaviorClass = behaviorClass;
        this.ticking = ticking;
        this.hasTick = behaviorClass != null && behaviorClass.__findattr__("tick") != null;
        this.hasHooks = behaviorClass != null
                && PyHandles.implementsAny(behaviorClass, "tick", "saveAdditional", "loadAdditional", "use");
        this.sync = sync;
        this.containerSize = containerSize;
    }

    /** 由门面在 BlockEntityType 建好后回填。 */
    public void attachEntityType(BlockEntityType<?> entityType) {
        this.entityType = entityType;
    }

    /** 是否有 Python 行为对象；false 表示该方块不做方块实体。 */
    public boolean hasBehavior() {
        return behaviorClass != null;
    }

    /**
     * 右键方块：转发给 Python 的 {@code use(state, level, pos, player, hit)}，返回 True 表示已处理。
     *
     * <p>用**方块实体里的 Python 实例**调用，而不是行为类本身——行为传的是类，直接
     * {@code __findattr__("use")} 拿到的是未绑定函数，调用会因缺 self 报参数数量不符。
     *
     * <p>双端都会触发，Python 侧用 {@code level.isClientSide()} 分流。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        // H1：复用方块实体行为实例的方法句柄缓存，右键不再每次做 __findattr__
        if (blockEntity instanceof PythonBlockEntity pythonBlockEntity) {
            PyHandles pythonHandles = pythonBlockEntity.handles();
            if (pythonHandles != null && Boolean.TRUE.equals(
                    pythonHandles.forward("use", Boolean.class, state, level, pos, player, hit))) {
                return InteractionResult.SUCCESS;
            }
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (behaviorClass == null) {
            return null;
        }
        // H11：把「类 + 是否带钩子」交给方块实体，由它惰性实例化；
        // 没有任何钩子的方块（纯占位/纯容器）永远不会创建 Python 对象
        return new PythonBlockEntity(entityType, pos, state, behaviorClass, hasHooks, sync, containerSize);
    }

    /**
     * 双端都会拿到 ticker，Python 侧可用 {@code level.isClientSide()} 自行分流；
     * {@code ticking} 为 false、没有行为对象、或行为类未实现 {@code tick} 时返回 null（该方块不参与 tick）。
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (!ticking || behaviorClass == null || !hasTick) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof PythonBlockEntity pythonBlockEntity) {
                pythonBlockEntity.tickBehavior(tickLevel, tickPos, tickState);
            }
        };
    }
}
