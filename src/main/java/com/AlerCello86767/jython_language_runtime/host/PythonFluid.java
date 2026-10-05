package com.AlerCello86767.jython_language_runtime.host;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

/**
 * 通用流体宿主：原版 {@link FlowingFluid} 的抽象方法收在 Java 侧，Python 只声明数值与行为钩子。
 *
 * <p>结构照抄原版 {@code WaterFluid}：{@link Source} 是静止源（`isSource` 为 true、量恒为 8、
 * 状态里没有 LEVEL），{@link Flowing} 带 LEVEL 属性。这个划分不能改——原版
 * {@code LiquidBlock} 在**构造期**就会读这两条流体去预建 {@code FluidState} 缓存。
 *
 * <p><b>不做桶装容器</b>：{@link #getBucket()} 返回空气，{@code getPickupSound} 走默认空值。
 * 放置只能靠 `/setblock`、`/fill` 或流体扩散。
 *
 * <p>源 / 流动 / 方块三者互相引用，只能注册后回填：{@link #attach(Fluid, Fluid)} 必须在
 * 造方块**之前**调用（方块构造期要用），{@link #attachBlock(Block)} 在造完之后调用。
 *
 * <p><b>行为钩子</b>（由门面 {@code PyFluids} 传入一份**源与流动共用**的句柄缓存；Python 在
 * {@code behavior} 类里定义同名函数即可，未定义即短路回原版）：
 * <ul>
 *   <li>{@code entityInside(level, pos, entity, effectApplier)}：实体处于本流体格内。
 *       伤害型/减速型/点燃型流体都写这里（注意自行用 {@code level.isClientSide()} 分流）。</li>
 *   <li>{@code randomTick(level, pos, fluidState, random)}：随机 tick，需要
 *       {@code isRandomlyTicking} 为真才会被调度（默认实现了它就返回真）</li>
 *   <li>{@code animateTick(level, pos, fluidState, random)}：客户端每帧随机粒子/音效</li>
 *   <li>{@code isRandomlyTicking()}：返回 bool，显式覆盖「是否参与随机调度」</li>
 * </ul>
 *
 * <p><b>性能</b>：{@code behavior} 为 null（Python 一个钩子都没实现）时全部钩子只剩一次
 * 空判断，不跨界、不分配。
 */
public abstract class PythonFluid extends FlowingFluid {
    /** 声明式参数（源与流动共用一份）。 */
    public record Options(int tickDelay, int slopeFindDistance, int dropOff, double explosionResistance,
                          boolean canConvertToSource, ParticleOptions dripParticle, int light,
                          double viscosity, double push, boolean resetFall) {
    }

    private final Options options;
    /** 行为钩子的句柄缓存（源与流动共用一份 Python 实例）；没有行为时为 null。 */
    private final PyHandles behavior;
    /** 构造期探测：Python 是否实现了 {@code randomTick}，作为 {@link #isRandomlyTicking()} 的兜底。 */
    private final boolean hasRandomTick;
    private Fluid still;
    private Fluid flowing;
    private Block fluidBlock;

    protected PythonFluid(Options options, PyHandles behavior) {
        this.options = options;
        this.behavior = behavior;
        this.hasRandomTick = behavior != null && behavior.has("randomTick");
    }

    /** 回填源 / 流动（**必须在造方块之前**——方块构造期会读它们）。 */
    public void attach(Fluid still, Fluid flowing) {
        this.still = still;
        this.flowing = flowing;
    }

    /** 回填对应方块，使流体能被 `createLegacyBlock` 放置成方块。 */
    public void attachBlock(Block fluidBlock) {
        this.fluidBlock = fluidBlock;
    }

    /**
     * 同一流体判定：原版 {@code Fluid.isSame} 是身份比较（{@code fluid == this}），
     * 而 {@code WaterFluid}/{@code LavaFluid} 都会重写成「源或流动均为同一流体」。
     * {@code FlowingFluid} 的扩散（{@code affectsFlow} / {@code isSourceBlockOfThisType} /
     * {@code sourceNeighborCount}）全靠它认亲，缺了这个重写源与流动互不相认，流动直接错乱。
     */
    @Override
    public boolean isSame(Fluid fluid) {
        return fluid == still || fluid == flowing;
    }

    @Override
    public Fluid getFlowing() {
        return flowing;
    }

    @Override
    public Fluid getSource() {
        return still;
    }

    /** 不做桶装容器：与 {@code Fluids.EMPTY} 一致。 */
    @Override
    public Item getBucket() {
        return Items.AIR;
    }

    @Override
    protected boolean canConvertToSource(ServerLevel level) {
        return options.canConvertToSource();
    }

    /** 与水流一致：被流体替换的可掉落方块按正常掉落处理。 */
    @Override
    protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        Block.dropResources(state, level, pos, blockEntity);
    }

    @Override
    protected int getSlopeFindDistance(LevelReader level) {
        return options.slopeFindDistance();
    }

    @Override
    protected int getDropOff(LevelReader level) {
        return options.dropOff();
    }

    @Override
    public int getTickDelay(LevelReader level) {
        return options.tickDelay();
    }

    @Override
    protected float getExplosionResistance() {
        return (float) options.explosionResistance();
    }

    @Override
    protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid other,
                                        Direction direction) {
        // 与水流一致：只看下方，且不阻挡同类流体
        return direction == Direction.DOWN && !other.isSame(this);
    }

    /** 钟乳石滴水粒子（基类默认无粒子）；{@code dripParticle} 未声明时保持原版默认。 */
    @Override
    protected ParticleOptions getDripParticle() {
        return options.dripParticle();
    }

    // ---------- 行为钩子（未实现即短路回原版） ----------

    /**
     * 实体处于本流体格内：先应用声明式物理（减速 / 推动 / 消摔伤），再回调 Python 钩子。
     *
     * <p>原版的流体阻力/推动不在流体上，而在 {@code LivingEntity.travel} / {@code EntityFluidInteraction}
     * 里按 {@code FluidTags.WATER} / {@code LAVA} 硬编码分派，自定义流体沾不上边——所以这里的
     * 阻尼与水流推动由宿主在 entityInside 里手动施加（双端都跑，玩家移动客户端预测正好各管各的）。
     */
    @Override
    protected void entityInside(Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier) {
        if (options.viscosity() < 1.0) {
            entity.setDeltaMovement(entity.getDeltaMovement().scale(options.viscosity()));
        }
        if (options.push() != 0.0) {
            Vec3 flow = level.getFluidState(pos).getFlow(level, pos);
            entity.addDeltaMovement(flow.scale(options.push()));
        }
        if (options.resetFall()) {
            entity.fallDistance = 0.0F;
        }
        if (behavior != null && behavior.call("entityInside", level, pos, entity, effectApplier)) {
            return;
        }
        super.entityInside(level, pos, entity, effectApplier);
    }

    /** 随机 tick；只有 {@link #isRandomlyTicking()} 为真时才会被调度。 */
    @Override
    protected void randomTick(ServerLevel level, BlockPos pos, FluidState state, RandomSource random) {
        if (behavior != null && behavior.call("randomTick", level, pos, state, random)) {
            return;
        }
        super.randomTick(level, pos, state, random);
    }

    /** 客户端每帧随机动画（粒子/音效）。 */
    @Override
    protected void animateTick(Level level, BlockPos pos, FluidState state, RandomSource random) {
        if (behavior != null) {
            behavior.call("animateTick", level, pos, state, random);
        }
    }

    /**
     * 是否参与随机调度。Python 显式实现 {@code isRandomlyTicking} 时以它为准，
     * 否则「实现了 {@code randomTick}」即视为参与。
     */
    @Override
    protected boolean isRandomlyTicking() {
        if (behavior == null) {
            return super.isRandomlyTicking();
        }
        Boolean explicit = behavior.forward("isRandomlyTicking", Boolean.class);
        if (explicit != null) {
            return explicit;
        }
        return hasRandomTick;
    }

    /** 放置路径：`/setblock`、`/fill`、流体扩散都走这里把 FluidState 变成方块。 */
    @Override
    protected BlockState createLegacyBlock(FluidState state) {
        if (fluidBlock == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return fluidBlock.defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(state));
    }

    /** 源：静止、量恒为 8，状态里没有 LEVEL。 */
    public static final class Source extends PythonFluid {
        public Source(Options options, PyHandles behavior) {
            super(options, behavior);
        }

        @Override
        public boolean isSource(FluidState state) {
            return true;
        }

        @Override
        public int getAmount(FluidState state) {
            return 8;
        }
    }

    /** 流动：带 LEVEL 属性，量取自 LEVEL。 */
    public static final class Flowing extends PythonFluid {
        public Flowing(Options options, PyHandles behavior) {
            super(options, behavior);
        }

        @Override
        protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }

        @Override
        public boolean isSource(FluidState state) {
            return false;
        }

        @Override
        public int getAmount(FluidState state) {
            return state.getValue(LEVEL);
        }
    }
}
