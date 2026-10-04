package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyForwarder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 方块实体转发宿主（P10）：把可重写方法转发给一个 Python 对象。
 *
 * <p>可用钩子（未定义即保留原版行为）：
 * <ul>
 *   <li>{@code tick(blockEntity, level, pos, state)}：由 {@link PythonBlock} 的 ticker 每 tick 调用；
 *       首个参数就是宿主自身，便于 Python 调 {@code setChanged()} 等原生方法</li>
 *   <li>{@code saveAdditional(output)} / {@code loadAdditional(input)}：持久化。先跑 super
 *       再回调，因此原版字段不受影响</li>
 * </ul>
 *
 * <p>Python 侧读写数据用 {@code ValueOutput} / {@code ValueInput} 的原生方法：
 * 写 {@code output.putInt("k", v)} / {@code putString} / {@code putBoolean} / {@code putDouble} …；
 * 读 {@code input.getIntOr("k", 0)} / {@code getStringOr} / {@code getBooleanOr} / {@code getDoubleOr} …
 */
public class PythonBlockEntity extends BlockEntity {
    private final PyObject behavior;
    private final boolean sync;
    private final SimpleContainer container;

    /** 该方块实体对应的 Python 实例：方块级钩子（如右键）复用它，不再重复实例化。 */
    public PyObject behavior() {
        return behavior;
    }

    /**
     * 方块自己的物品栏。菜单的机器槽位直接读写它，所以**物品随方块实体一起持久化**，
     * 关掉界面不会丢。
     */
    public SimpleContainer container() {
        return container;
    }

    public PythonBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                             PyObject behavior, boolean sync, int containerSize) {
        super(type, pos, state);
        this.behavior = behavior;
        this.sync = sync;
        this.container = new SimpleContainer(containerSize);
    }

    /** 由 {@link PythonBlock#getTicker} 调用。 */
    void tickBehavior(Level level, BlockPos pos, BlockState state) {
        PyForwarder.call(behavior, "tick", this, level, pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // 物品栏由 Java 侧负责存读、不经 Python：ItemStack 的数据结构容易写坏
        ContainerHelper.saveAllItems(output, container.getItems());
        PyForwarder.call(behavior, "saveAdditional", output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ContainerHelper.loadAllItems(input, container.getItems());
        PyForwarder.call(behavior, "loadAdditional", input);
    }

    /**
     * 数据变更。{@code sync} 打开时额外推一份同步包给追踪该区块的客户端；
     * 关闭时只走原版（服务端标记脏块），适用于纯服务端数据。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        Level current = getLevel();
        if (sync && current != null && !current.isClientSide()) {
            current.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 同步标签：{@code sync} 打开时带上自定义数据（{@code saveWithoutMetadata} 含方块实体 id，
     * 客户端要靠它解析类型）。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return sync ? saveWithoutMetadata(registries) : super.getUpdateTag(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
