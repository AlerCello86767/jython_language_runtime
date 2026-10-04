package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 实体转发宿主：把 {@link LivingEntity} 的可重写方法转发给一个 Python 对象。
 *
 * <p>Python 侧定义与 Java 方法同名的函数即可，未定义就保留原版行为。可用名：
 * {@code tick} / {@code hurtServer} / {@code interact} / {@code getMainArm} /
 * {@code readAdditionalSaveData} / {@code addAdditionalSaveData}。
 *
 * <p>与物品宿主的关键差异：实体的原版逻辑（物理、AI、血量持久化）是必需的，
 * 因此 {@code tick} 与存档读写都是**先跑 super 再回调 Python**（作为额外钩子），
 * 只有 {@code hurtServer} / {@code interact} / {@code getMainArm} 是「Python 优先、返回 None 才回退」。
 */
public class PythonEntity extends LivingEntity {
    /** H11：行为类一个钩子都没实现时为 null——不为每个实体创建 Python 对象，各钩子直接走原版。 */
    private final PyHandles handles;

    public PythonEntity(EntityType<? extends PythonEntity> type, Level level, PyObject behavior) {
        super(type, level);
        // H1：构造期预解析全部钩子（每个实体一个行为实例）
        this.handles = behavior == null ? null : new PyHandles(behavior);
        if (handles != null) {
            handles.preload("tick", "hurtServer", "interact", "getMainArm",
                    "readAdditionalSaveData", "addAdditionalSaveData");
        }
    }

    @Override
    public HumanoidArm getMainArm() {
        HumanoidArm arm = forward("getMainArm", HumanoidArm.class);
        return arm != null ? arm : HumanoidArm.RIGHT;
    }

    /** 原版 tick 先执行（保留物理/AI），再回调 Python 作为额外 tick 钩子。 */
    @Override
    public void tick() {
        super.tick();
        call("tick", this);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        Boolean result = forward("hurtServer", Boolean.class, level, source, amount);
        return result != null ? result : super.hurtServer(level, source, amount);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        InteractionResult result = forward("interact", InteractionResult.class, player, hand, location);
        return result != null ? result : super.interact(player, hand, location);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        call("readAdditionalSaveData", input);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        call("addAdditionalSaveData", output);
    }

    // ---------- 转发内核（调用与返回值约定见 PyHandles） ----------

    private <T> T forward(String name, Class<T> type, Object... args) {
        return handles == null ? null : handles.forward(name, type, args);
    }

    private boolean call(String name, Object... args) {
        return handles != null && handles.call(name, args);
    }
}
