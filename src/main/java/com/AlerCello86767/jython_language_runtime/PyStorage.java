package com.AlerCello86767.jython_language_runtime;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.host.SidedSimpleContainer;
import com.AlerCello86767.jython_language_runtime.host.SidedSimpleContainer.SideRules;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * 存储声明门面（L2.1）：给带方块实体物品栏的方块声明「哪个面能进、哪个面能出」。
 *
 * <p>方块实体的物品栏默认<b>已经</b>通过 {@code ItemStorage.SIDED} 暴露给漏斗 / 管道
 * （见 {@code Registration.registerBlock}），但默认是「所有面、所有槽位全开」。
 * 本门面把它收紧成机器语义：上面进原料、侧面进燃料、下面只出产物。
 *
 * <pre>
 * # 与 Registration.registerBlock 同窗口调用，id 必须与方块同名（入口脚本内可写短名）
 * PyStorage.itemSides("metallurgic_infuser", {
 *     "up":   {"insert":  [0]},      # 漏斗从上面往 0 号槽灌原料
 *     "down": {"extract": [1, 2]},   # 产物只能从 1/2 号槽向下抽走
 * })
 * </pre>
 *
 * <p><b>规则：</b>
 * <ul>
 *   <li>面名取 {@code up/down/north/south/west/east}；参数包里<b>没出现的面完全封闭</b>；</li>
 *   <li>每个面给 {@code insert}（可放入槽位）和/或 {@code extract}（可抽出槽位），值为槽位下标列表；</li>
 *   <li>槽位下标在方块实体首次创建时按容器大小做越界校验，非法配置世界加载即快速失败；</li>
 *   <li>本门面只管「按面过滤」。主动去操作<b>别人</b>的存储用 {@link PyTransfers}。</li>
 * </ul>
 */
public final class PyStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/storage");

    private PyStorage() {
    }

    /**
     * 声明方块实体物品栏的按面存取规则。
     *
     * @param blockId  方块 / 方块实体类型 id（同名）；入口脚本内可写短名
     * @param sidesArg 面规则参数包（Jython dict）
     */
    public static void itemSides(String blockId, PyObject sidesArg) {
        Identifier id = ModIds.parse(blockId);
        Object converted = sidesArg == null ? null : sidesArg.__tojava__(Map.class);
        if (!(converted instanceof Map)) {
            throw new IllegalArgumentException("PyStorage.itemSides 第二个参数必须是面规则参数包，收到: " + sidesArg);
        }
        Map<?, ?> raw = (Map<?, ?>) converted;
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("PyStorage.itemSides(" + id + ") 面规则为空；"
                    + "不需要按面过滤就不要调用本方法（默认全槽位开放）");
        }

        EnumMap<Direction, SideRules> rules = new EnumMap<>(Direction.class);
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Direction direction = parseDirection(asString(entry.getKey()));
            Object sideConverted = entry.getValue() instanceof PyObject
                    ? ((PyObject) entry.getValue()).__tojava__(Map.class)
                    : entry.getValue();
            if (!(sideConverted instanceof Map)) {
                throw new IllegalArgumentException(id + " 的面 " + direction.getName()
                        + " 规则必须是参数包，如 {\"insert\": [0], \"extract\": [1]}");
            }
            int[] insert = slotList(id, direction, "insert", ((Map<?, ?>) sideConverted).get("insert"));
            int[] extract = slotList(id, direction, "extract", ((Map<?, ?>) sideConverted).get("extract"));
            if (insert.length == 0 && extract.length == 0) {
                throw new IllegalArgumentException(id + " 的面 " + direction.getName()
                        + " 既无 insert 也无 extract，这是个死面，请删除或补全");
            }
            rules.put(direction, new SideRules(insert, extract, union(insert, extract)));
        }

        SidedSimpleContainer.register(id, rules);
        LOGGER.info("Registered sided item storage for {} (sides={})", id, rules.keySet());
    }

    /** 取槽位列表；缺省/None 视为空（该面不做此方向），其他类型快速失败。 */
    private static int[] slotList(Identifier id, Direction direction, String key, Object raw) {
        if (raw == null) {
            return new int[0];
        }
        Object list = raw instanceof PyObject ? ((PyObject) raw).__tojava__(List.class) : raw;
        if (!(list instanceof List)) {
            throw new IllegalArgumentException(id + " 的面 " + direction.getName()
                    + " 的 " + key + " 必须是槽位下标列表，收到: " + raw);
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Object value : (List<?>) list) {
            if (!(value instanceof Number)) {
                throw new IllegalArgumentException(id + " 的面 " + direction.getName()
                        + " 的 " + key + " 含非数字槽位: " + value);
            }
            int slot = ((Number) value).intValue();
            if (slot < 0) {
                throw new IllegalArgumentException(id + " 的面 " + direction.getName()
                        + " 出现负槽位下标: " + slot);
            }
            sorted.add(slot);
        }
        int[] result = new int[sorted.size()];
        int i = 0;
        for (int slot : sorted) {
            result[i++] = slot;
        }
        return result;
    }

    /** 两个有序数组的并集（去重、有序），供 getSlotsForFace 使用。 */
    private static int[] union(int[] a, int[] b) {
        TreeSet<Integer> merged = new TreeSet<>();
        for (int v : a) {
            merged.add(v);
        }
        for (int v : b) {
            merged.add(v);
        }
        int[] result = new int[merged.size()];
        int i = 0;
        for (int v : merged) {
            result[i++] = v;
        }
        return result;
    }

    private static Direction parseDirection(String name) {
        if (name == null) {
            throw new IllegalArgumentException("面名不能为 None；可用 up/down/north/south/west/east");
        }
        try {
            return Direction.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知面: " + name
                    + "（可用 up/down/north/south/west/east）");
        }
    }

    private static String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
