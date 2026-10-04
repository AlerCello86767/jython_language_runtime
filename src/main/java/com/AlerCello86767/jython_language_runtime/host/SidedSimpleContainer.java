package com.AlerCello86767.jython_language_runtime.host;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;

/**
 * 按面过滤槽位的容器（L2.1）：在 {@link SimpleContainer} 之上实现 {@link WorldlyContainer}，
 * 让 Fabric 的 {@code ContainerStorage.of(container, direction)} 自动按面裁剪——
 * 上面只进原料、下面只出产物这种机器语义不需要 Python 碰任何 Transfer API。
 *
 * <p>规则不在构造参数里传入（{@code Registration} 不感知存储配置），而是按
 * <b>方块实体类型 id（与方块同名）</b>从静态规则表查询，由 {@code PyStorage.itemSides} 填充。
 * 因此依赖方声明存储面与注册方块互不耦合，只要两边 id 一致。
 *
 * <p><b>语义约定：</b>
 * <ul>
 *   <li>参数包里出现的面才暴露存储；未出现的面 {@code getSlotsForFace} 返回空数组，
 *       该面完全不可存取（漏斗贴上来不工作）；</li>
 *   <li>面内 {@code insert}/extract 槽位列表均为并集关系；槽位可以同时出现在两边；</li>
 *   <li>无面查询（{@code direction == null}）由 Fabric 侧短路为全槽位视图，本类不会收到 null。</li>
 * </ul>
 */
public class SidedSimpleContainer extends SimpleContainer implements WorldlyContainer {

    /** 单一面的存取规则；槽位列表在构造时已去重、排序。 */
    public static final class SideRules {
        private final int[] insertSlots;
        private final int[] extractSlots;
        /** insert 与 extract 的并集（去重、有序），{@link #getSlotsForFace} 直接返回。 */
        private final int[] faceSlots;

        /** insert/extract/并集均为去重有序数组；由 {@code PyStorage} 门面构造。 */
        public SideRules(int[] insertSlots, int[] extractSlots, int[] faceSlots) {
            this.insertSlots = insertSlots;
            this.extractSlots = extractSlots;
            this.faceSlots = faceSlots;
        }

        boolean allowsInsert(int slot) {
            return contains(insertSlots, slot);
        }

        boolean allowsExtract(int slot) {
            return contains(extractSlots, slot);
        }

        int[] faceSlots() {
            return faceSlots;
        }

        private static boolean contains(int[] sorted, int value) {
            return java.util.Arrays.binarySearch(sorted, value) >= 0;
        }
    }

    /** 类型 id → 六个面的规则；mod init 窗口写入，世界加载后只读。 */
    private static final Map<Identifier, Map<Direction, SideRules>> RULES = new ConcurrentHashMap<>();

    /** 注册/覆盖某方块实体类型的面规则；由 {@code PyStorage} 门面调用。 */
    public static void register(Identifier typeId, Map<Direction, SideRules> rules) {
        RULES.put(typeId, Map.copyOf(rules));
    }

    /** 取某类型的面规则；未声明过返回 {@code null}（此时用普通 SimpleContainer）。 */
    static Map<Direction, SideRules> rulesFor(Identifier typeId) {
        return RULES.get(typeId);
    }

    private final Map<Direction, SideRules> rules;

    /**
     * @param size  槽位总数（与菜单声明一致）
     * @param rules 面规则，调用方已保证非空；构造时做槽位越界校验
     * @param owner 所属方块实体；外部（漏斗/管道/菜单）改动物品栏时回调它的 {@code setChanged}，
     *              保证持久化脏标记与同步不丢失。可为 {@code null}（测试用）
     */
    public SidedSimpleContainer(int size, Map<Direction, SideRules> rules, Runnable owner) {
        super(size);
        // EnumMap(Map) 构造器从非空 map 推断键类型；调用方（PyStorage）已保证 rules 非空
        this.rules = new EnumMap<>(rules);
        this.owner = owner;
        for (Map.Entry<Direction, SideRules> entry : this.rules.entrySet()) {
            for (int slot : entry.getValue().faceSlots()) {
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("面规则槽位越界: slot=" + slot + "，容器大小=" + size
                            + "（面 " + entry.getKey() + "）");
                }
            }
        }
    }

    private final Runnable owner;

    @Override
    public int[] getSlotsForFace(Direction direction) {
        SideRules side = rules.get(direction);
        return side == null ? EMPTY : side.faceSlots();
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        SideRules side = rules.get(direction);
        return side != null && side.allowsInsert(slot);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        SideRules side = rules.get(direction);
        return side != null && side.allowsExtract(slot);
    }

    /**
     * 外部改动物品栏时通知宿主方块实体：漏斗/管道经 Transfer API 的写入、
     * 以及菜单 Slot 的拿放最终都会走到这里。让机器像被手动操作一样标脏 + 同步。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        if (owner != null) {
            owner.run();
        }
    }

    private static final int[] EMPTY = new int[0];
}
