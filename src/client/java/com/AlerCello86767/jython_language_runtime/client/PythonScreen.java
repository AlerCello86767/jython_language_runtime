package com.AlerCello86767.jython_language_runtime.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.python.core.PyObject;

import com.AlerCello86767.jython_language_runtime.core.PyHandles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 自定义界面宿主（client 侧）：把 {@link Screen} 的覆写点转发给一个 Python 对象。
 *
 * <p>Python 侧实现同名方法，全部可选；未实现即走原版行为。输入事件已把 record
 * （{@code MouseButtonEvent} / {@code KeyEvent} / {@code CharacterEvent}）**解包成基础类型**，
 * Python 侧不需要认识 record。
 *
 * <p><b>处理顺序</b>：输入事件**先问 Python**，返回 True 表示已处理（不再交给原版）；
 * 返回 False / None 才落到原版——原版会负责命中自己的控件，所以正常做法是
 * <b>不要在 Python 里对着自己的按钮返回 True</b>。
 *
 * <p><b>绘制</b>：原版先画（背景 + 控件），随后回放 Python 的 {@link UiDraw} 指令列表。
 * Python 拿不到 {@code GuiGraphicsExtractor}，只产出指令。
 *
 * <p>窗口尺寸变化会重调 {@link #init()}，因此控件每次都会重建，Python 的对象状态不应依赖控件实例。
 */
public class PythonScreen extends Screen {
    private final PyHandles handles;
    private final Map<String, AbstractWidget> widgets = new LinkedHashMap<>();
    private Screen parent;

    public PythonScreen(Component title, PyObject behavior) {
        super(title);
        // H1：一份界面一个行为实例，构造期预解析全部钩子（draw 是每帧路径）
        this.handles = new PyHandles(behavior);
        this.handles.preload("init", "draw", "tick", "removed", "isPauseScreen",
                "onButton", "onText", "onClick", "onRelease", "onDrag", "onScroll",
                "onKey", "onKeyRelease", "onChar");
    }

    /**
     * 记录「打开这个界面的来源界面」，关闭时回到它。
     *
     * <p>原版没有界面栈，{@code Screen.onClose()} 默认是 {@code setScreen(null)}——
     * 从标题界面点进来的界面若直接关掉，玩家会被丢到「没有界面也没有世界」的状态。
     */
    public void setParent(Screen parent) {
        this.parent = parent;
    }

    /** 来源界面；没用 {@link #setParent} 设过时为 null。 */
    public Screen parent() {
        return parent;
    }

    /** 关闭（Esc 或 {@code PyScreens.back()}）都回到来源界面。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** Python 侧可用的控件建造器：{@code init(builder)} 的参数。 */
    public final class WidgetBuilder {
        /** 普通按钮；点击回调 {@code onButton(id)}。 */
        public WidgetBuilder button(String id, int x, int y, int w, int h, String labelKey) {
            Button button = Button.builder(Component.translatable(labelKey),
                    pressed -> handles.call("onButton", id))
                    .bounds(x, y, w, h)
                    .build();
            addRenderableWidget(button);
            widgets.put(id, button);
            return this;
        }

        /** 文本输入框；内容变化回调 {@code onText(id, value)}。 */
        public WidgetBuilder editBox(String id, int x, int y, int w, int h, String initial, int maxLength) {
            EditBox box = new EditBox(getFont(), x, y, w, h, Component.empty());
            box.setMaxLength(maxLength);
            box.setValue(initial == null ? "" : initial);
            box.setResponder(text -> handles.call("onText", id, text));
            addRenderableWidget(box);
            widgets.put(id, box);
            return this;
        }

        /** 读当前值：输入框返回文本，按钮返回标签。 */
        public String getValue(String id) {
            AbstractWidget widget = widgets.get(id);
            if (widget instanceof EditBox box) {
                return box.getValue();
            }
            return widget == null ? null : widget.getMessage().getString();
        }
    }

    // ---------- 生命周期 ----------

    @Override
    protected void init() {
        widgets.clear();
        handles.call("init", new WidgetBuilder());
    }

    @Override
    public void tick() {
        super.tick();
        handles.call("tick");
    }

    @Override
    public void removed() {
        super.removed();
        handles.call("removed");
    }

    @Override
    public boolean isPauseScreen() {
        Boolean value = handles.forward("isPauseScreen", Boolean.class);
        return value != null ? value : super.isPauseScreen();
    }

    // ---------- 绘制 ----------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        PyObject result = handles.forward("draw", PyObject.class, mouseX, mouseY, partialTick);
        if (result != null) {
            Object converted = result.__tojava__(UiDraw.class);
            if (converted instanceof UiDraw ui) {
                ui.replay(graphics);
            }
        }
    }

    // ---------- 输入（先问 Python，未处理再走原版） ----------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        Boolean handled = handles.forward("onClick", Boolean.class,
                event.x(), event.y(), event.button(), doubleClick);
        return Boolean.TRUE.equals(handled) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Boolean handled = handles.forward("onRelease", Boolean.class,
                event.x(), event.y(), event.button());
        return Boolean.TRUE.equals(handled) || super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        Boolean handled = handles.forward("onDrag", Boolean.class,
                event.x(), event.y(), event.button(), dragX, dragY);
        return Boolean.TRUE.equals(handled) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        Boolean handled = handles.forward("onScroll", Boolean.class,
                mouseX, mouseY, scrollX, scrollY);
        return Boolean.TRUE.equals(handled) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        Boolean handled = handles.forward("onKey", Boolean.class,
                event.key(), event.scancode(), event.modifiers());
        return Boolean.TRUE.equals(handled) || super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        Boolean handled = handles.forward("onKeyRelease", Boolean.class,
                event.key(), event.scancode(), event.modifiers());
        return Boolean.TRUE.equals(handled) || super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        Boolean handled = handles.forward("onChar", Boolean.class, event.codepoint());
        return Boolean.TRUE.equals(handled) || super.charTyped(event);
    }
}
