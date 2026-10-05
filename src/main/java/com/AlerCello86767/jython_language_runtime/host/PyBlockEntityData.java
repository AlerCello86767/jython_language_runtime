package com.AlerCello86767.jython_language_runtime.host;

import net.minecraft.world.inventory.ContainerData;

/**
 * 把方块实体的 Python 数据（{@code getData(index)} / {@code setData(index, value)}）适配成
 * 菜单的「数据槽」来源。
 *
 * <p>原版 {@code AbstractContainerMenu.addDataSlots(ContainerData)} 会按索引每隔一段时间
 * 把服务端的整数值同步到客户端——机器界面上的进度条 / 能量条 / 温度就是靠它。
 *
 * <p>{@code count} 由菜单注册时的 {@code dataCount} 决定，必须与 Python 声明的数据个数一致：
 * 客户端用 {@code SimpleContainerData(count)} 占位，靠索引对齐接收同步值。
 */
public final class PyBlockEntityData implements ContainerData {
    private final PythonBlockEntity blockEntity;
    private final int count;

    public PyBlockEntityData(PythonBlockEntity blockEntity, int count) {
        this.blockEntity = blockEntity;
        this.count = count;
    }

    @Override
    public int get(int index) {
        return blockEntity.getData(index);
    }

    @Override
    public void set(int index, int value) {
        blockEntity.setData(index, value);
    }

    @Override
    public int getCount() {
        return count;
    }
}
