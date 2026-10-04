package com.AlerCello86767.jython_language_runtime;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 聊天 / 系统消息门面（{@code fabric-message-api-v1}）。
 *
 * <p>Python 只丢函数，函数在服务端主线程回调，文本已经转成纯字符串（聊天原文、系统消息纯文本），
 * 需要样式或转发时自己 {@code Component.literal(text)} 重建即可。
 *
 * <p>四条链路的关系（原版行为）：
 * <ul>
 *   <li>玩家在聊天框发言 → {@code ALLOW_CHAT_MESSAGE} → {@code CHAT_MESSAGE}</li>
 *   <li>玩家执行 {@code /me}、{@code /say} 这类广播 → {@code ALLOW_COMMAND_MESSAGE} → {@code COMMAND_MESSAGE}
 *       → 若来自玩家，还会继续走上面那条链路</li>
 *   <li>服务器广播系统消息（死亡、进出、成就）→ {@code ALLOW_GAME_MESSAGE} → {@code GAME_MESSAGE}</li>
 * </ul>
 *
 * <p>{@code Allow*} 系列返回 {@code False} 即拦下该消息，后续回调不再触发。
 */
public final class PyMessages {
    private PyMessages() {
    }

    /** {@code (player, text) -> void} */
    public interface ChatHandler {
        void handle(ServerPlayer player, String text);
    }

    /** {@code (player, text) -> bool}；返回 False 拦下这条消息。 */
    public interface AllowChatHandler {
        boolean allow(ServerPlayer player, String text);
    }

    /** {@code (source, text) -> void}；{@code source} 是 CommandSourceStack，可从中取执行者。 */
    public interface CommandHandler {
        void handle(CommandSourceStack source, String text);
    }

    /** {@code (source, text) -> bool}；返回 False 拦下。 */
    public interface AllowCommandHandler {
        boolean allow(CommandSourceStack source, String text);
    }

    /** {@code (server, text, overlay) -> void}；{@code overlay} 为 True 表示是动作栏提示。 */
    public interface GameHandler {
        void handle(MinecraftServer server, String text, boolean overlay);
    }

    /** {@code (server, text, overlay) -> bool}；返回 False 拦下。 */
    public interface AllowGameHandler {
        boolean allow(MinecraftServer server, String text, boolean overlay);
    }

    /** 玩家发言**之后**回调（已被 Allow 放行）。 */
    public static void onChatMessage(ChatHandler handler) {
        ServerMessageEvents.CHAT_MESSAGE.register(
                (message, sender, bound) -> handler.handle(sender, plainText(message)));
    }

    /** 玩家发言**之前**回调，可拦下。 */
    public static void onAllowChatMessage(AllowChatHandler handler) {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(
                (message, sender, bound) -> handler.allow(sender, plainText(message)));
    }

    /** 全服广播型命令消息（{@code /me}、{@code /say}）之后回调；{@code /msg} 这类定向消息不触发。 */
    public static void onCommandMessage(CommandHandler handler) {
        ServerMessageEvents.COMMAND_MESSAGE.register(
                (message, source, bound) -> handler.handle(source, plainText(message)));
    }

    /** 全服广播型命令消息之前回调，可拦下。 */
    public static void onAllowCommandMessage(AllowCommandHandler handler) {
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register(
                (message, source, bound) -> handler.allow(source, plainText(message)));
    }

    /** 服务器广播系统消息（死亡 / 进出 / 成就）之后回调。 */
    public static void onGameMessage(GameHandler handler) {
        ServerMessageEvents.GAME_MESSAGE.register(
                (server, message, overlay) -> handler.handle(server, message.getString(), overlay));
    }

    /** 服务器广播系统消息之前回调，可拦下。 */
    public static void onAllowGameMessage(AllowGameHandler handler) {
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register(
                (server, message, overlay) -> handler.allow(server, message.getString(), overlay));
    }

    /**
     * 取玩家输入的原文：改造过内容（unsigned content，例如其它 mod 重写过）时优先用它，
     * 否则退回签名原文。不要用 {@code decoratedContent()}——那会把「玩家名: 」这类装饰一起带进来。
     */
    private static String plainText(PlayerChatMessage message) {
        Component unsigned = message.unsignedContent();
        return unsigned != null ? unsigned.getString() : message.signedContent();
    }
}
