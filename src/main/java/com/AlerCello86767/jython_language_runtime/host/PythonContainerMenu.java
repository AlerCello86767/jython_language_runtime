package com.AlerCello86767.jython_language_runtime.host;

import java.util.Map;

import org.python.core.PyObject;
import org.python.core.PyType;

import com.AlerCello86767.jython_language_runtime.core.Params;
import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 容器菜单宿主（服务端与客户端共用）：把槽位布局交给 Python，其余走原版。
 *
 * <p>Python 实现 {@code initSlots(builder)} 声明槽位（未实现即无槽位），可选实现
 * {@code stillValid(player)}、{@code onCreateContainer(container)}（仅服务端打开时调用，用于预填充）。
 *
 * <p><b>shift 点击搬运（quickMoveStack）</b>：Java 侧有默认实现——按「机器槽位数 + 玩家背包分区」
 * 自动做机器↔背包搬运，必要时尊重槽位语义（输出槽不会被塞东西）。原版这个契约要求返回被移动的堆叠，
 * 让 Python 插手很容易写坏/复制物品，所以**不**开放给 Python。
 *
 * <p>玩家背包自己持有而不用父类字段——26.1.2 的 {@code AbstractContainerMenu.playerInventory}
 * 在本映射下取不到。
 */
public class PythonContainerMenu extends AbstractContainerMenu {
    private final SimpleContainer container;
    private final Inventory playerInventory;
    private final PyHandles handles;

    /** 机器自身槽位数（0..machineSlotCount-1）；quickMoveStack 的默认搬运按它分区。 */
    private int machineSlotCount;
    /** 玩家背包分区下标，由 {@link SlotBuilder#playerInventory(int, int)} 记录；-1 表示没有。 */
    private int playerMainStart = -1;
    private int playerMainEnd = -1;
    private int hotbarStart = -1;
    private int hotbarEnd = -1;

    public PythonContainerMenu(MenuType<?> type, int containerId, SimpleContainer container, Inventory playerInventory,
                               PyObject behavior) {
        this(type, containerId, container, playerInventory, behavior, null, false);
    }

    /**
     * 完整构造。
     *
     * @param data    数据槽来源。服务端是世界里的方块实体；客户端传 {@code SimpleContainerData} 占位
     *                （真正的值由同步包写入）。null 表示这个菜单没有数据槽
     * @param prefill 是否调用 Python 的 {@code onCreateContainer(container)}。**只有服务端**打开界面时
     *                才是 true——客户端跳过，否则会凭空造出物品
     */
    public PythonContainerMenu(MenuType<?> type, int containerId, SimpleContainer container, Inventory playerInventory,
                               PyObject behavior, ContainerData data, boolean prefill) {
        super(type, containerId);
        this.container = container;
        this.playerInventory = playerInventory;
        // 传入的是 Python 类时先实例化：每份菜单持有独立行为对象（与界面同生命周期）。
        // 不能在类上直接调实例方法——Python 2 会报 unbound method
        PyObject instance = behavior instanceof PyType ? behavior.__call__() : behavior;
        // H1：每份菜单独立的行为实例，构造期预解析钩子
        this.handles = new PyHandles(instance);
        this.handles.preload("initSlots", "stillValid", "onCreateContainer");
        this.handles.call("initSlots", new SlotBuilder());
        if (data != null) {
            // 数据槽按索引同步到客户端：进度条 / 能量 / 温度靠它
            addDataSlots(data);
        }
        if (prefill) {
            this.handles.call("onCreateContainer", container);
        }
    }

    /** 机器自身的物品栏。 */
    public SimpleContainer container() {
        return container;
    }

    /** 槽位声明器：{@code initSlots(builder)} 的参数。 */
    public final class SlotBuilder {
        /** 机器槽位（普通槽）：{@code index} 是容器下标。 */
        public SlotBuilder slot(int index, int x, int y) {
            return slot(index, x, y, null);
        }

        /**
         * 带语义的机器槽位。{@code options} 可空，支持：
         * <ul>
         *   <li>{@code output} (boolean)：输出槽——不能放入，只能取出</li>
         *   <li>{@code readOnly} (boolean)：只读槽——既不能放入也不能取出（纯展示）</li>
         *   <li>{@code filter} (String)：只收某物品（{@code "mymod:ruby"}）或某标签（{@code "#c:ores"}）</li>
         *   <li>{@code maxStack} (int)：该槽的最大堆叠，默认 64</li>
         * </ul>
         */
        public SlotBuilder slot(int index, int x, int y, Map<String, Object> options) {
            if (options == null || options.isEmpty()) {
                addSlot(new Slot(container, index, x, y));
            } else {
                addSlot(new OptionSlot(container, index, x, y, options));
            }
            machineSlotCount++;
            return this;
        }

        /** 玩家背包 3x9 + 快捷栏 1x9，共 36 格；{@code y} 是背包首行位置。 */
        public SlotBuilder playerInventory(int x, int y) {
            playerMainStart = slots.size();
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    addSlot(new Slot(playerInventory, col + row * 9 + 9, x + col * 18, y + row * 18));
                }
            }
            playerMainEnd = slots.size();
            hotbarStart = slots.size();
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col, x + col * 18, y + 58));
            }
            hotbarEnd = slots.size();
            return this;
        }
    }

    /** 带语义的槽位：输出 / 只读 / 过滤 / 限堆叠。 */
    private static final class OptionSlot extends Slot {
        private final boolean output;
        private final boolean readOnly;
        private final Item filterItem;
        private final TagKey<Item> filterTag;
        private final int maxStack;

        OptionSlot(Container container, int index, int x, int y, Map<String, Object> options) {
            super(container, index, x, y);
            this.output = Params.asBoolean(options.get("output"), false);
            this.readOnly = Params.asBoolean(options.get("readOnly"), false);
            this.maxStack = Params.asInt(options.get("maxStack"), 64);
            Object rawFilter = options.get("filter");
            if (rawFilter == null) {
                this.filterItem = null;
                this.filterTag = null;
            } else if (rawFilter instanceof String text && text.startsWith("#")) {
                Identifier tagId = requireId(text.substring(1), "filter tag");
                this.filterTag = TagKey.create(Registries.ITEM, tagId);
                this.filterItem = null;
            } else {
                Identifier itemId = requireId(String.valueOf(rawFilter), "filter item");
                Item item = BuiltInRegistries.ITEM.getValue(itemId);
                if (item == null) {
                    throw new IllegalArgumentException("Unknown item in slot filter: " + itemId);
                }
                this.filterItem = item;
                this.filterTag = null;
            }
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !output && !readOnly && matches(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return !readOnly;
        }

        @Override
        public int getMaxStackSize() {
            return maxStack;
        }

        private boolean matches(ItemStack stack) {
            if (filterItem != null) {
                return stack.is(holder -> holder.value() == filterItem);
            }
            if (filterTag != null) {
                return stack.is(holder -> holder.is(filterTag));
            }
            return true;
        }

        private static Identifier requireId(String raw, String what) {
            Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                throw new IllegalArgumentException("Invalid " + what + ": " + raw);
            }
            return id;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = stack.copy();

        if (index < machineSlotCount) {
            // 机器槽 → 玩家背包（主背包 + 快捷栏）
            if (playerMainStart < 0 || !moveItemStackTo(stack, playerMainStart, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (machineSlotCount > 0 && moveItemStackTo(stack, 0, machineSlotCount, false)) {
            // 玩家背包 → 机器槽（第一个适用的槽，尊重输出/过滤语义）
        } else if (index < playerMainEnd && moveItemStackTo(stack, hotbarStart, hotbarEnd, false)) {
            // 主背包 → 快捷栏
        } else if (index >= hotbarStart && moveItemStackTo(stack, playerMainStart, playerMainEnd, false)) {
            // 快捷栏 → 主背包
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return stack;
    }

    @Override
    public boolean stillValid(Player player) {
        Boolean value = handles.forward("stillValid", Boolean.class, player);
        return value == null || value;
    }
}
