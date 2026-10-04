package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
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
    private final PyObject behaviorClass;
    private final boolean hasHooks;
    private final boolean sync;
    private final SimpleContainer container;
    /** H8：上次同步出去的 tag，用来跳过「内容没变」的重复同步包。 */
    private CompoundTag lastSynced;
    /** H11：行为实例与句柄缓存都惰性创建；{@code hasHooks} 为 false 时永不创建。 */
    private PyObject behavior;
    private PyHandles handles;

    /** 该方块实体对应的 Python 实例（首次访问时惰性创建）；无 Python 行为时为 {@code null}。 */
    public PyObject behavior() {
        handlesOrNull();
        return behavior;
    }

    /**
     * 该行为实例的方法句柄缓存；无 Python 行为时为 {@code null}。
     *
     * <p>方块级钩子（如右键 use）复用它，避免每次调用都做 __findattr__。
     */
    public PyHandles handles() {
        return handlesOrNull();
    }

    /** 惰性实例化行为对象并建句柄缓存；{@code hasHooks} 为 false 时永不实例化。 */
    private PyHandles handlesOrNull() {
        PyHandles current = handles;
        if (current != null) {
            return current;
        }
        if (!hasHooks) {
            return null;
        }
        return createHandles();
    }

    private synchronized PyHandles createHandles() {
        if (handles == null) {
            PyObject instance = behaviorClass.__call__();
            PyHandles created = new PyHandles(instance);
            created.preload("tick", "saveAdditional", "loadAdditional", "use");
            behavior = instance;
            handles = created;
        }
        return handles;
    }

    /**
     * 方块自己的物品栏。菜单的机器槽位直接读写它，所以**物品随方块实体一起持久化**，
     * 关掉界面不会丢。
     */
    public SimpleContainer container() {
        return container;
    }

    /**
     * @param behaviorClass Python 行为类（每个方块实体惰性实例化一份）
     * @param hasHooks      该行为类是否实现了任何方块实体钩子；false 时永不创建 Python 对象
     */
    public PythonBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                             PyObject behaviorClass, boolean hasHooks, boolean sync, int containerSize) {
        super(type, pos, state);
        this.behaviorClass = behaviorClass;
        this.hasHooks = hasHooks;
        this.sync = sync;
        this.container = createContainer(containerSize);
    }

    /**
     * 构造物品栏。若依赖方通过 {@code PyStorage.itemSides} 给本方块实体类型（id 与方块同名）
     * 声明过按面存取规则，就用 {@link SidedSimpleContainer}（WorldlyContainer），
     * Fabric 的 {@code ContainerStorage.of} 会自动按面过滤；否则保持普通 {@link SimpleContainer}
     * （所有面、所有槽位全开，历史行为不变）。
     */
    private SimpleContainer createContainer(int size) {
        if (size <= 0) {
            return new SimpleContainer(size);
        }
        Identifier typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(getType());
        java.util.Map<Direction, SidedSimpleContainer.SideRules> rules = SidedSimpleContainer.rulesFor(typeId);
        if (rules == null || rules.isEmpty()) {
            return new SimpleContainer(size);
        }
        return new SidedSimpleContainer(size, rules, this::onContainerChanged);
    }

    /**
     * 物品栏被外部改动（漏斗/管道/菜单）时由 {@link SidedSimpleContainer} 回调：
     * 走标准 setChanged 链路——标脏持久化，{@code sync} 打开时按 H8 去重后推同步包。
     */
    void onContainerChanged() {
        setChanged();
    }

    /** 由 {@link PythonBlock#getTicker} 调用——只有行为类实现了 tick 才会挂 ticker，故这里必有句柄。 */
    void tickBehavior(Level level, BlockPos pos, BlockState state) {
        handlesOrNull().call("tick", this, level, pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // 物品栏由 Java 侧负责存读、不经 Python：ItemStack 的数据结构容易写坏
        ContainerHelper.saveAllItems(output, container.getItems());
        PyHandles current = handlesOrNull();
        if (current != null) {
            current.call("saveAdditional", output);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ContainerHelper.loadAllItems(input, container.getItems());
        PyHandles current = handlesOrNull();
        if (current != null) {
            current.call("loadAdditional", input);
        }
    }

    /**
     * 数据变更。{@code sync} 打开时额外推一份同步包给追踪该区块的客户端；
     * 关闭时只走原版（服务端标记脏块），适用于纯服务端数据。
     *
     * <p><b>H8：</b>不少实现会每 tick 调 {@code setChanged()}，但内容其实没变。
     * 这里先比较「本次要同步的数据」与上次同步的是否一致，一致就不发包——
     * 避免把 per-tick 的调用变成 per-tick 的网络包。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        Level current = getLevel();
        if (!sync || current == null || current.isClientSide()) {
            return;
        }
        CompoundTag tag = saveWithoutMetadata(current.registryAccess());
        if (tag.equals(lastSynced)) {
            return;
        }
        lastSynced = tag;
        current.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_ALL);
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
