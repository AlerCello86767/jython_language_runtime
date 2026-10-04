package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * AI Goal 转发宿主（L4.2）：把 {@link Goal} 的行为转发给一个 Python 对象。
 *
 * <p>Python 侧定义与 Java 方法同名的函数即可，都会以 {@code (mob)} 作为唯一参数：
 * {@code canUse} / {@code canContinueToUse} / {@code isInterruptable} /
 * {@code start} / {@code stop} / {@code tick} / {@code requiresUpdateEveryTick}。
 *
 * <p>Python 未实现的方法回落到原版默认：{@code canUse} 返回 {@code False}、
 * {@code canContinueToUse} / {@code isInterruptable} 返回 {@code True}，其余是空实现。
 * 句柄在构造期预解析（{@link PyHandles}），热路径不再做 {@code __findattr__}。
 */
public class PythonGoal extends Goal {
    private final Mob mob;
    private final PyHandles handles;

    public PythonGoal(Mob mob, PyObject behavior) {
        this.mob = mob;
        this.handles = new PyHandles(behavior);
        handles.preload("canUse", "canContinueToUse", "isInterruptable",
                "start", "stop", "tick", "requiresUpdateEveryTick");
    }

    @Override
    public boolean canUse() {
        Boolean result = handles.forward("canUse", Boolean.class, mob);
        return result != null && result;
    }

    @Override
    public boolean canContinueToUse() {
        Boolean result = handles.forward("canContinueToUse", Boolean.class, mob);
        return result == null || result;
    }

    @Override
    public boolean isInterruptable() {
        Boolean result = handles.forward("isInterruptable", Boolean.class, mob);
        return result == null || result;
    }

    @Override
    public void start() {
        handles.call("start", mob);
    }

    @Override
    public void stop() {
        handles.call("stop", mob);
    }

    @Override
    public void tick() {
        handles.call("tick", mob);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        Boolean result = handles.forward("requiresUpdateEveryTick", Boolean.class, mob);
        return result != null && result;
    }
}
