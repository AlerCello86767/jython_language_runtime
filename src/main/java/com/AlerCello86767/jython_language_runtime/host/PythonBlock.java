package com.AlerCello86767.jython_language_runtime.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.python.core.Py;
import org.python.core.PyObject;
import org.python.core.PySequence;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 方块宿主（P10 / P1.5）：承载 Python 行为类，并在需要时提供方块实体。
 *
 * <p>{@code behaviorClass} 是 Python 类。**方块实体钩子**每方块实体实例化一份
 * （实例状态天然隔离），宿主可用钩子见 {@link PythonBlockEntity}；**方块级钩子**则只按
 * 方块类型实例化一份，用于没有位置状态的逻辑（作物生长、形状、邻接更新等）。
 *
 * <p>{@code behaviorClass} 可以为 {@code null}：此时方块不创建方块实体，也没有 Python 行为，
 * 只作为普通方块使用（但若声明了自定义状态属性，仍会用本宿主承载 StateDefinition）。
 *
 * <p><b>方块级钩子</b>（在行为类里定义同名函数即可，未定义即保留原版）：
 * <ul>
 *   <li>{@code randomTick(state, level, pos, random)}：随机 tick；实现了它才会参与随机调度</li>
 *   <li>{@code getDrops(state, params)}：返回物品 id 列表或 {@code (id, count)} 列表；
 *       返回 None 走原版掉落表</li>
 *   <li>{@code getShape(state, level, pos, context)}：返回 {@code [x0,y0,z0,x1,y1,z1]}
 *       （0..1 相对坐标）；返回 None 走原版满方块形状</li>
 *   <li>{@code canSurvive(state, level, pos)}：返回 bool；None 走原版</li>
 *   <li>{@code neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston)}：无返回值</li>
 *   <li>{@code useItemOn(stack, state, level, pos, player, hand, hit)}：手持物品右键；返回 bool，None 走原版</li>
 *   <li>{@code playerDestroy(level, player, pos, state, blockEntity, tool)}：无返回值</li>
 *   <li>{@code onPlace(state, level, pos, oldState, movedByPiston)}：无返回值</li>
 * </ul>
 *
 * <p><b>性能</b>：所有钩子在构造期一次性探测「Python 是否实现」，未实现直接短路回原版，
 * 不分配 Python 实例；已实现则惰性建一份 {@link PyHandles} 句柄缓存，避免热路径
 * （{@code getShape} / {@code canSurvive} / {@code neighborChanged}）上做 {@code __findattr__}。
 *
 * <p><b>资源侧要求（带状态属性的方块）</b>：声明了状态属性后，除了方块模型
 * {@code assets/<modid>/models/block/<name>.json} 与物品定义
 * {@code assets/<modid>/items/<name>.json} 外，还必须给出方块状态定义
 * {@code assets/<modid>/blockstates/<name>.json}，把所有属性组合映射到模型变体。例如带
 * {@code age}(0..7) 的作物：
 * <pre>
 * { "variants": {
 *     "age=0": { "model": "mymod:block/ruby_crop_stage0" },
 *     "age=1": { "model": "mymod:block/ruby_crop_stage1" },
 *     ... "age=7": { "model": "mymod:block/ruby_crop_stage7" }
 * } }
 * </pre>
 * 缺省状态的组合（未在 variants 里列出的）不会渲染；缺失条目会在客户端日志里报
 * “missing variant”。多属性时用逗号连接，如 {@code "age=3,lit=true"}。
 */
public class PythonBlock extends Block implements EntityBlock {
    /**
     * 构造期传递「Python 声明的状态属性」的通道。
     *
     * <p>{@code createBlockStateDefinition} 是在 {@code Block} 构造函数（即 {@code super()}）
     * 期间被调用的，那时本类实例字段尚未赋值，因此只能用构造期可见的 ThreadLocal 传递。
     * 门面 {@code Registration} 在 {@code new PythonBlock(...)} 之前 set，构造完成后 remove。
     */
    public static final ThreadLocal<List<Property<?>>> PENDING_PROPERTIES = new ThreadLocal<>();

    private final PyObject behaviorClass;
    private final boolean ticking;
    /** H2：行为类是否真的实现了 {@code tick}（构造期探测一次）。未实现就不挂 ticker，避免每 tick 空跑。 */
    private final boolean hasTick;
    /** H11：行为类是否实现了任何方块实体钩子；都没有就不创建 Python 对象（纯占位/纯容器方块）。 */
    private final boolean hasHooks;
    private final boolean sync;
    private final int containerSize;
    /** 是否创建方块实体；只给 {@code behavior} 而不给 {@code blockEntity} 的方块（作物）为 false。 */
    private final boolean hasBlockEntity;
    private BlockEntityType<?> entityType;

    /** Python 声明的状态属性（构造期从 {@link #PENDING_PROPERTIES} 捕获）：属性名 → 属性。 */
    private final Map<String, Property<?>> declaredProperties;
    /** 行为类是否实现了 {@code randomTick}；决定 {@link #isRandomlyTicking} 是否参与随机调度。 */
    private final boolean hasRandomTick;
    /** 行为类是否实现了任意方块级钩子；false 时永不创建方块级 Python 实例。 */
    private final boolean hasBlockHooks;
    /** 方块级行为的句柄缓存（每个方块类型一份，惰性创建）。 */
    private volatile PyHandles blockHandles;

    /** 保留旧签名：默认「给了行为类就带方块实体」。 */
    public PythonBlock(BlockBehaviour.Properties properties, PyObject behaviorClass,
                       boolean ticking, boolean sync, int containerSize) {
        this(properties, behaviorClass, ticking, sync, containerSize, behaviorClass != null);
    }

    /**
     * 完整构造。
     *
     * @param hasBlockEntity 是否创建方块实体。只给 {@code behavior}（方块级钩子）而不给
     *                       {@code blockEntity} 的方块（如作物）传 false——否则每个作物方块都会
     *                       白挂一个带物品栏的方块实体
     */
    public PythonBlock(BlockBehaviour.Properties properties, PyObject behaviorClass,
                       boolean ticking, boolean sync, int containerSize, boolean hasBlockEntity) {
        super(properties);
        this.behaviorClass = behaviorClass;
        this.ticking = ticking;
        this.hasTick = behaviorClass != null && behaviorClass.__findattr__("tick") != null;
        this.hasHooks = behaviorClass != null
                && PyHandles.implementsAny(behaviorClass, "tick", "saveAdditional", "loadAdditional", "use",
                        "getData", "setData");
        this.sync = sync;
        this.containerSize = containerSize;
        this.hasBlockEntity = hasBlockEntity;

        // 捕获构造期声明的状态属性（createBlockStateDefinition 已从同一份列表读过）
        List<Property<?>> pending = PENDING_PROPERTIES.get();
        if (pending == null || pending.isEmpty()) {
            this.declaredProperties = Map.of();
        } else {
            Map<String, Property<?>> byName = new LinkedHashMap<>();
            for (Property<?> property : pending) {
                byName.put(property.getName(), property);
            }
            this.declaredProperties = byName;
        }

        this.hasRandomTick = behaviorClass != null && behaviorClass.__findattr__("randomTick") != null;
        this.hasBlockHooks = behaviorClass != null && PyHandles.implementsAny(behaviorClass,
                "randomTick", "getDrops", "getShape", "canSurvive",
                "neighborChanged", "useItemOn", "playerDestroy", "onPlace");
    }

    /** 由门面在 BlockEntityType 建好后回填。 */
    public void attachEntityType(BlockEntityType<?> entityType) {
        this.entityType = entityType;
    }

    /** 是否有 Python 行为对象；false 表示该方块不做方块实体。 */
    public boolean hasBehavior() {
        return behaviorClass != null;
    }

    /** 该方块是否会创建方块实体（只给 behavior 的方块为 false）。 */
    public boolean hasBlockEntity() {
        return hasBlockEntity;
    }

    /** 把 Python 声明的状态属性加入 StateDefinition（构造期会被调用，参数与朝向方块共用一份列表）。 */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        List<Property<?>> pending = PENDING_PROPERTIES.get();
        if (pending != null) {
            for (Property<?> property : pending) {
                builder.add(property);
            }
        }
    }

    /**
     * 写入 {@code defaults} 里的默认状态取值（由门面在构造完成后调用，此时 StateDefinition 才完整）。
     *
     * <p>取值类型须与属性类型匹配（int→数字、bool→布尔、enum→字符串），否则抛
     * {@link IllegalArgumentException}；引用了未声明的属性也抛错。
     */
    public void applyDefaultValues(Map<String, Object> defaults) {
        if (defaults == null || defaults.isEmpty()) {
            return;
        }
        BlockState state = defaultBlockState();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Property<?> property = declaredProperties.get(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException("defaults 引用了未声明的方块状态属性 \""
                        + entry.getKey() + "\"，已声明: " + declaredProperties.keySet());
            }
            state = applyDefault(state, property, entry.getValue());
        }
        registerDefaultState(state);
    }

    @SuppressWarnings("unchecked")
    private static BlockState applyDefault(BlockState state, Property<?> property, Object raw) {
        if (property instanceof IntegerProperty intProperty) {
            if (!(raw instanceof Number number)) {
                throw new IllegalArgumentException("int 属性 \"" + property.getName()
                        + "\" 的默认值必须是数字，收到: " + raw);
            }
            return state.setValue(intProperty, number.intValue());
        }
        if (property instanceof BooleanProperty boolProperty) {
            if (!(raw instanceof Boolean value)) {
                throw new IllegalArgumentException("bool 属性 \"" + property.getName()
                        + "\" 的默认值必须是布尔值，收到: " + raw);
            }
            return state.setValue(boolProperty, value);
        }
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException("枚举属性 \"" + property.getName()
                    + "\" 的默认值必须是字符串，收到: " + raw);
        }
        return state.setValue((Property<String>) property, value);
    }

    /**
     * 右键方块（空手 / 无物品语义）：转发给 Python 的
     * {@code use(state, level, pos, player, hit)}，返回 True 表示已处理。
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
        if (behaviorClass == null || !hasBlockEntity) {
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

    // ---------- 方块级行为钩子（构造期探测 + 句柄缓存，未实现即短路回原版） ----------

    /**
     * 随机 tick（作物生长、随机变化）。原版仅对 {@code isRandomlyTicking} 为真的方块调度，
     * 而未实现 {@code randomTick} 时该方法返回 false，因此不会白白调度。
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        PyHandles handles = blockHandles();
        if (handles != null && handles.call("randomTick", state, level, pos, random)) {
            return;
        }
        super.randomTick(state, level, pos, random);
    }

    /** 只有 Python 实现了 {@code randomTick} 才让原版把它纳入随机调度。 */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return hasRandomTick || super.isRandomlyTicking(state);
    }

    /**
     * 掉落物。Python 返回物品 id 列表或 {@code (id, count)} 列表；返回 None 走原版掉落表
     * （默认掉落本方块物品）。
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        PyHandles handles = blockHandles();
        if (handles != null) {
            PyObject method = handles.method("getDrops");
            if (method != null) {
                PyObject result = method.__call__(Py.javas2pys(state, params));
                if (result != null && result != Py.None) {
                    return parseDrops(result);
                }
            }
        }
        return super.getDrops(state, params);
    }

    /**
     * 方块形状（可视/轮廓形状，不是碰撞形状）。作物这类非满方块只改这里，碰撞形状仍取默认
     * （{@code getCollisionShape} 未覆写，非满方块默认无碰撞）。Python 返回
     * {@code [x0,y0,z0,x1,y1,z1]}（0..1 相对坐标）；返回 None 走原版满方块形状。
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        PyHandles handles = blockHandles();
        if (handles != null) {
            PyObject method = handles.method("getShape");
            if (method != null) {
                PyObject result = method.__call__(Py.javas2pys(state, level, pos, context));
                if (result != null && result != Py.None) {
                    return parseShape(result);
                }
            }
        }
        return super.getShape(state, level, pos, context);
    }

    /** 能否在当前环境存活（作物需要下方泥土等）。Python 返回 bool；None 走原版。 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        PyHandles handles = blockHandles();
        if (handles != null) {
            Boolean result = handles.forward("canSurvive", Boolean.class, state, level, pos);
            if (result != null) {
                return result;
            }
        }
        return super.canSurvive(state, level, pos);
    }

    /** 邻接更新（机器/热源检测）。{@code orientation} 是 26.1 新增的红石朝向参数。 */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   Orientation orientation, boolean movedByPiston) {
        PyHandles handles = blockHandles();
        if (handles != null && handles.call("neighborChanged",
                state, level, pos, neighborBlock, orientation, movedByPiston)) {
            return;
        }
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
    }

    /**
     * 手持物品右键方块（把种子种下去、把物品放到砧板）。Python 返回 False 表示已处理但无效果
     * （{@link InteractionResult#FAIL}），返回 None 走原版。
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        PyHandles handles = blockHandles();
        if (handles != null) {
            Boolean result = handles.forward("useItemOn", Boolean.class,
                    stack, state, level, pos, player, hand, hit);
            if (result != null) {
                return result ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /** 被玩家破坏后回调（此时方块已移除）。先跑原版掉落/统计，再通知 Python。 */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
        PyHandles handles = blockHandles();
        if (handles != null) {
            handles.call("playerDestroy", level, player, pos, state, blockEntity, tool);
        }
    }

    /** 放置后回调。先跑原版，再通知 Python。 */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        PyHandles handles = blockHandles();
        if (handles != null) {
            handles.call("onPlace", state, level, pos, oldState, movedByPiston);
        }
    }

    /**
     * 方块级行为句柄缓存（每个方块类型一份）。{@code hasBlockHooks} 为 false 时直接返回 null，
     * 热路径上只有一次布尔判断，不跨界、不分配。
     */
    private PyHandles blockHandles() {
        if (!hasBlockHooks) {
            return null;
        }
        PyHandles current = blockHandles;
        if (current != null) {
            return current;
        }
        return createBlockHandles();
    }

    private synchronized PyHandles createBlockHandles() {
        if (blockHandles == null) {
            PyObject instance = behaviorClass.__call__();
            PyHandles created = new PyHandles(instance);
            created.preload("randomTick", "getDrops", "getShape", "canSurvive",
                    "neighborChanged", "useItemOn", "playerDestroy", "onPlace");
            blockHandles = created;
        }
        return blockHandles;
    }

    // ---------- Python 返回值解析 ----------

    /** 解析 {@code getDrops} 的返回值：物品 id 列表，或 {@code (id, count)} 列表。 */
    private static List<ItemStack> parseDrops(PyObject result) {
        Object converted = result.__tojava__(List.class);
        if (converted == Py.NoConversion || !(converted instanceof List<?> entries)) {
            throw new IllegalArgumentException("getDrops 需要返回物品 id 列表，或 (id, count) 列表；收到: " + result);
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (Object entry : entries) {
            String id;
            int count = 1;
            Object asPair = entry instanceof PyObject py ? py.__tojava__(List.class) : Py.NoConversion;
            if (asPair instanceof List<?> pair && pair.size() == 2) {
                id = toIdString(pair.get(0));
                count = toInt(pair.get(1));
            } else {
                id = toIdString(entry);
            }
            stacks.add(new ItemStack(resolveDropItem(id), count));
        }
        return stacks;
    }

    /** 解析 {@code getShape} 的返回值：{@code [x0,y0,z0,x1,y1,z1]}（0..1 相对坐标）。 */
    private static VoxelShape parseShape(PyObject result) {
        if (!(result instanceof PySequence sequence) || sequence.__len__() != 6) {
            throw new IllegalArgumentException(
                    "getShape 需要返回 [x0,y0,z0,x1,y1,z1] 六个 0..1 的数，收到: " + result);
        }
        double[] box = new double[6];
        for (int i = 0; i < 6; i++) {
            PyObject element = sequence.__finditem__(i);
            Object converted = element == null ? null : element.__tojava__(Double.class);
            if (!(converted instanceof Number number)) {
                throw new IllegalArgumentException("getShape 第 " + i + " 个值不是数字，收到: " + result);
            }
            box[i] = number.doubleValue();
        }
        return Shapes.box(box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    private static String toIdString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof PyObject py) {
            Object converted = py.__tojava__(String.class);
            if (converted instanceof String text) {
                return text;
            }
        }
        throw new IllegalArgumentException("需要一个物品 id 字符串，收到: " + value);
    }

    private static int toInt(Object value) {
        Object converted = value instanceof PyObject py ? py.__tojava__(Integer.class) : value;
        if (converted instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("掉落数量需要整数，收到: " + value);
    }

    /** 按 id 解析掉落物品；未注册时给可读报错。 */
    private static Item resolveDropItem(String rawId) {
        Identifier key = Identifier.tryParse(rawId);
        if (key == null) {
            throw new IllegalArgumentException("掉落物 id 非法: \"" + rawId + "\"");
        }
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            throw new IllegalArgumentException("掉落物引用了未注册的物品: \"" + rawId
                    + "\"（回调里请写全限定 id，形如 \"mymod:ruby_seed\"）");
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }
}
