package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;

/**
 * {@link MenuProvider} 宿主：{@code ServerPlayer.openMenu(provider)} 需要一个接口实例，
 * Python 实现不了，由这个类承载。
 *
 * <p>服务端打开菜单时 {@code createMenu} 被调用（**带 player**，这是唯一能拿到玩家上下文的位置），
 * 此时才创建容器并让 Python 有机会预填充：{@code onCreateContainer(container)}。
 *
 * <p>客户端不走这里——客户端由 {@code MenuType} 的 supplier 直接造菜单，拿不到 provider。
 */
public class PyMenuProvider implements MenuProvider {
    private final MenuType<PythonContainerMenu> type;
    private final PyObject behavior;
    private final SimpleContainer container;
    private final String titleKey;
    private final ContainerData data;

    public PyMenuProvider(MenuType<PythonContainerMenu> type, PyObject behavior,
                          SimpleContainer container, String titleKey) {
        this(type, behavior, container, titleKey, null);
    }

    /**
     * @param data 数据槽来源（带方块实体的机器传方块实体适配器；null 表示这个菜单没有数据槽）
     */
    public PyMenuProvider(MenuType<PythonContainerMenu> type, PyObject behavior,
                          SimpleContainer container, String titleKey, ContainerData data) {
        this.type = type;
        this.behavior = behavior;
        this.container = container;
        this.titleKey = titleKey;
        this.data = data;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        // 容器由调用方给出：从方块打开时是方块实体的物品栏（物品持久化），
        // 否则是临时容器（关掉界面物品即丢）。
        // prefill=true：只有服务端会走到这里，允许 Python 用 onCreateContainer 预填充
        return new PythonContainerMenu(type, containerId, container, inventory, behavior, data, true);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(titleKey);
    }
}
