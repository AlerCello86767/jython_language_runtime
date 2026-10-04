package com.AlerCello86767.jython_language_runtime.client;

import org.joml.Matrix3x2fStack;
import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyForwarder;
import com.AlerCello86767.jython_language_runtime.host.PythonContainerMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 容器界面宿主（client 侧）：{@code AbstractContainerScreen} 的覆写点转发给 Python。
 *
 * <p>Python 侧方法全部可选：{@code init(screen)}（可读 {@code leftPos}/{@code topPos} 定位）、
 * {@code draw(mouseX, mouseY, partialTick)}（返回 {@link UiDraw}）、{@code tick()}、
 * {@code removed()}。输入事件请用 {@code PyScreens} 那套宿主，本类暂不转发。
 *
 * <p><b>版本注意</b>：26.1.2 把原版 {@code renderBg} 改名为 {@link #extractContents}；
 * 且 {@code tick()} 是 {@code public final}，每 tick 逻辑只能写在 {@code containerTick()}。
 */
public class PythonMenuScreen extends AbstractContainerScreen<PythonContainerMenu> {
    private final PyObject behavior;

    public PythonMenuScreen(PythonContainerMenu menu, Inventory inventory, Component title, PyObject behavior) {
        super(menu, inventory, title);
        this.behavior = behavior;
    }

    @Override
    protected void init() {
        super.init();
        PyForwarder.call(behavior, "init", this);
    }

    /**
     * 面板绘制顺序（由 26.1.2 字节码确认）：自定义面板先画，再交给原版。
     *
     * <p>原版 {@code extractContents} 的内部流程是「底层背景 → translate(leftPos, topPos) →
     * {@code extractLabels}（标题/物品栏文字）→ 槽位高亮 → {@code extractSlots}（槽位与物品）→
     * 高亮前置」，**全部在同一个局部坐标系里**，而且它自己**不画任何面板贴图**。
     * 所以物品和文字必须在它之前的语义位置画好——反过来（原版先画、我们再盖）会把它们遮掉。
     *
     * <p>因此这里先在自己压入的同一套局部坐标系里回放 Python 指令，再调 super。
     * Python 用的是**面板局部坐标**，与 Mekanism 源码里的槽位坐标（如 (17,35)）一致；
     * 传入的 mouseX/mouseY 也换算成局部坐标。
     */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(leftPos, topPos);
        PyObject result = PyForwarder.forward(behavior, "draw", PyObject.class,
                mouseX - leftPos, mouseY - topPos, partialTick);
        if (result != null) {
            Object converted = result.__tojava__(UiDraw.class);
            if (converted instanceof UiDraw ui) {
                ui.replay(graphics);
            }
        }
        pose.popMatrix();
        // 原版负责：底层背景 + 标题/物品栏文字 + 槽位与物品 + 悬停高亮
        super.extractContents(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        PyForwarder.call(behavior, "tick");
    }

    @Override
    public void removed() {
        super.removed();
        PyForwarder.call(behavior, "removed");
    }
}
