package com.AlerCello86767.jython_language_runtime.host;

import org.python.core.PyObject;
import org.python.core.PyType;

import com.AlerCello86767.jython_language_runtime.core.PyForwarder;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 容器菜单宿主（服务端与客户端共用）：把槽位布局交给 Python，其余走原版。
 *
 * <p>Python 实现 {@code initSlots(builder)} 声明槽位（未实现即无槽位），可选实现
 * {@code stillValid(player)}。
 *
 * <p>玩家背包自己持有而不用父类字段——26.1.2 的 {@code AbstractContainerMenu.playerInventory}
 * 在本映射下取不到。
 *
 * <p><b>已知限制</b>：{@code quickMoveStack}（shift 点击转移）未实现，返回空即不搬运。
 * 原版契约要求返回被移动的堆叠，让 Python 插手容易写坏物品；等有明确需求再接。
 */
public class PythonContainerMenu extends AbstractContainerMenu {
    private final SimpleContainer container;
    private final Inventory playerInventory;
    private final PyObject behavior;

    public PythonContainerMenu(MenuType<?> type, int containerId, SimpleContainer container, Inventory playerInventory,
                               PyObject behavior) {
        super(type, containerId);
        this.container = container;
        this.playerInventory = playerInventory;
        // 传入的是 Python 类时先实例化：每份菜单持有独立行为对象（与界面同生命周期）。
        // 不能在类上直接调实例方法——Python 2 会报 unbound method
        this.behavior = behavior instanceof PyType ? behavior.__call__() : behavior;
        PyForwarder.call(this.behavior, "initSlots", new SlotBuilder());
    }

    /** 机器自身的物品栏；Python 可通过 {@code onCreateContainer(container)} 预先填充。 */
    public SimpleContainer container() {
        return container;
    }

    /** 槽位声明器：{@code initSlots(builder)} 的参数。 */
    public final class SlotBuilder {
        /** 机器槽位：{@code index} 是容器下标。 */
        public SlotBuilder slot(int index, int x, int y) {
            addSlot(new Slot(container, index, x, y));
            return this;
        }

        /** 玩家背包 3x9 + 快捷栏 1x9，共 36 格；{@code y} 是背包首行位置。 */
        public SlotBuilder playerInventory(int x, int y) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    addSlot(new Slot(playerInventory, col + row * 9 + 9, x + col * 18, y + row * 18));
                }
            }
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col, x + col * 18, y + 58));
            }
            return this;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        Boolean value = PyForwarder.forward(behavior, "stillValid", Boolean.class, player);
        return value == null || value;
    }
}
