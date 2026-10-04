package com.AlerCello86767.jython_language_runtime;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Python 专用的命令注册门面。
 *
 * <p>本类不是 Fabric 的 {@code net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback}，
 * 内部持有真正的 {@code CommandRegistrationCallback.EVENT}，并暴露一个对 Jython 友好的
 * 静态 {@link #register(Registrar)} 方法：Python 侧只需传入一个普通函数，无需接触泛型事件接口。
 */
public final class CommandRegistrationCallback {
    private CommandRegistrationCallback() {
    }

    /**
     * Python 回调函数要实现的形状（单方法接口，Jython 可直接传入普通函数）：
     * {@code f(dispatcher, buildContext, selection)}。
     */
    @FunctionalInterface
    public interface Registrar {
        void register(CommandDispatcher<CommandSourceStack> dispatcher,
                      CommandBuildContext buildContext,
                      Commands.CommandSelection selection);
    }

    /**
     * 注册一个 Python 命令回调。
     *
     * @param registrar Python 函数 {@code (dispatcher, buildContext, selection) -> None}
     */
    public static void register(Registrar registrar) {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) ->
                        registrar.register(dispatcher, buildContext, selection)
        );
    }
}
