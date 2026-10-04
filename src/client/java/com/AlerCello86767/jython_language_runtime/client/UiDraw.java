package com.AlerCello86767.jython_language_runtime.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.Params;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * UI 绘制指令的**建造器 + 回放器**（client 侧）。
 *
 * <p>设计取舍：Python 不是返回一个「指令 dict 列表」，而是调本类的方法把指令**建起来**，
 * 最后把这个对象交回 Java。原因有两条：
 * <ul>
 *   <li>Jython 把 Python 的 dict/list 传进 Java 时的类型转换有不确定性；调 Java 方法则没有。</li>
 *   <li>方法名写错会立刻抛错，不用等界面画不出来才发现 op 拼错了。</li>
 * </ul>
 *
 * <p>每条指令在构造时就捕获自己的参数（闭包），因此回放只是一次线性遍历，
 * 没有 switch、没有字符串解析——天然不存在「未知 op」。
 *
 * <p>构建在 Python 侧低频发生（注册时 / 数据变化时），<b>回放每帧发生且完全不跨语言</b>。
 */
public final class UiDraw {
    private final List<BiConsumer<GuiGraphicsExtractor, Font>> ops = new ArrayList<>();

    private UiDraw() {
    }

    /** Python 侧入口：{@code d = UiDraw.begin()}。 */
    public static UiDraw begin() {
        return new UiDraw();
    }

    /** 实心矩形。{@code x,y} 为左上角，{@code w,h} 为宽高。 */
    public UiDraw fill(int x, int y, int w, int h, String color) {
        int argb = Params.asColor(color);
        ops.add((graphics, font) -> graphics.fill(x, y, x + w, y + h, argb));
        return this;
    }

    /**
     * 空心矩形边框。
     * <p>⚠️ 26.1 的 {@code GuiGraphicsExtractor.outline} 签名是 {@code (x, y, width, height)}
     * （宽高，不是右下角坐标），别传 x+w/y+h，否则边框会从原点向右下多伸出一截。
     */
    public UiDraw outline(int x, int y, int w, int h, String color) {
        int argb = Params.asColor(color);
        ops.add((graphics, font) -> graphics.outline(x, y, w, h, argb));
        return this;
    }

    /**
     * 进度条。{@code ratio} 取 0.0–1.0，**由 Python 侧算好**（避免把数值查询搬进每帧路径）。
     */
    public UiDraw bar(int x, int y, int w, int h, float ratio, String color, String background) {
        int fg = Params.asColor(color);
        int bg = Params.asColor(background);
        float clamped = Math.max(0.0f, Math.min(1.0f, ratio));
        ops.add((graphics, font) -> {
            graphics.fill(x, y, x + w, y + h, bg);
            int filled = Math.round(w * clamped);
            if (filled > 0) {
                graphics.fill(x, y, x + filled, y + h, fg);
            }
        });
        return this;
    }

    /** 物品图标（含原版物品堆叠装饰，如数量、耐久条）。 */
    public UiDraw item(String itemId, int x, int y) {
        Identifier id = ModIds.parse(itemId);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null) {
            throw new IllegalArgumentException("Unknown item: " + id);
        }
        ItemStack stack = new ItemStack(item);
        ops.add((graphics, font) -> graphics.item(stack, x, y));
        return this;
    }

    /** 居中的翻译键文本（带阴影）。 */
    public UiDraw textCentered(String key, int x, int y, String color) {
        int argb = Params.asColor(color);
        Component text = Component.translatable(key);
        ops.add((graphics, font) -> graphics.centeredText(font, text, x, y, argb));
        return this;
    }

    /** 翻译键文本（带阴影）。 */
    public UiDraw text(String key, int x, int y, String color) {
        return textArgs(key, null, x, y, color);
    }

    /** 翻译键文本 + 占位符实参（带阴影）。{@code args} 可为 null。 */
    public UiDraw textArgs(String key, List<?> args, int x, int y, String color) {
        return text(key, args, x, y, color, true);
    }

    /** 翻译键文本 + 占位符实参，不带阴影。 */
    public UiDraw textPlain(String key, List<?> args, int x, int y, String color) {
        return text(key, args, x, y, color, false);
    }

    private UiDraw text(String key, List<?> args, int x, int y, String color, boolean shadow) {
        int argb = Params.asColor(color);
        Component text = args == null || args.isEmpty()
                ? Component.translatable(key)
                : Component.translatable(key, args.toArray());
        ops.add((graphics, font) -> graphics.text(font, text, x, y, argb, shadow));
        return this;
    }

    /** 每帧回放。**不跨语言**。 */
    public void replay(GuiGraphicsExtractor graphics) {
        Font font = Minecraft.getInstance().font;
        for (BiConsumer<GuiGraphicsExtractor, Font> op : ops) {
            op.accept(graphics, font);
        }
    }

    /** 指令条数，供日志/自检用。 */
    public int size() {
        return ops.size();
    }
}
