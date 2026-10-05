package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;

import java.util.LinkedHashMap;
import java.util.Map;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.Params;
import com.AlerCello86767.jython_language_runtime.host.PyBlockEntityData;
import com.AlerCello86767.jython_language_runtime.host.PyMenuProvider;
import com.AlerCello86767.jython_language_runtime.host.PythonBlockEntity;
import com.AlerCello86767.jython_language_runtime.host.PythonContainerMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.level.Level;

/**
 * 容器菜单门面（main 侧）。
 *
 * <pre>
 * class RubyMachineMenu(object):
 *     def initSlots(self, builder):
 *         builder.slot(0, 62, 35)              # 机器自身槽位
 *         builder.playerInventory(8, 84)       # 玩家背包 3x9 + 快捷栏
 *
 *     def onCreateContainer(self, container):  # 服务端预填充
 *         pass
 *
 * PyMenus.register("machine", {
 *     "menu": RubyMachineMenu,
 *     "title": "block.jython_language_runtime.ruby_machine",
 *     "size": 9,
 * })
 * PyMenus.open(player, "machine")              # 服务端为玩家打开
 * </pre>
 *
 * <p><b>注册必须写在 main 入口</b>（MenuType 是双端注册表）。<b>客户端侧还需
 * {@code PyMenuScreens.bind} 绑定界面</b>，否则打开时客户端会报找不到 screen。
 */
public final class PyMenus {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/PyMenus");
    private static final Map<Identifier, Entry> REGISTRY = new LinkedHashMap<>();

    private PyMenus() {
    }

    private record Entry(MenuType<PythonContainerMenu> type, PyObject behavior, int containerSize, String titleKey,
                         int dataCount) {
    }

    /**
     * 注册菜单类型。
     *
     * <p>{@code options}：
     * <ul>
     *   <li>{@code menu}（必填，Python 类）</li>
     *   <li>{@code title}（必填，翻译键）</li>
     *   <li>{@code size}（容器格数，默认 9）</li>
     *   <li>{@code dataCount}（同步到客户端的数据槽个数，默认 0）。大于 0 时方块实体的
     *       {@code getData(index)} / {@code setData(index, value)} 会按索引同步到界面——
     *       进度条 / 能量条 / 温度靠它。**必须是 int 值**，每 tick 同步</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static void register(String id, Map<String, Object> options) {
        Identifier ident = ModIds.parse(id);
        Object raw = options.get("menu");
        if (!(raw instanceof PyObject behavior)) {
            throw new IllegalArgumentException("Menu '" + id + "' requires a 'menu' class");
        }
        String titleKey = Params.requireString(options.get("title"), "title");
        int size = asInt(options.get("size"), 9);
        int dataCount = asInt(options.get("dataCount"), 0);
        if (dataCount < 0) {
            throw new IllegalArgumentException("Menu '" + id + "' has a negative dataCount: " + dataCount);
        }

        // 客户端由 MenuSupplier 造菜单、拿不到 provider，所以 behavior 直接取闭包；
        // 而 MenuType 自身还要传给菜单构造函数——循环依赖，用单元素数组回填。
        // 客户端的数据槽是占位（SimpleContainerData），真正的值由同步包写入
        MenuType<PythonContainerMenu>[] holder = new MenuType[1];
        MenuType<PythonContainerMenu> type = new MenuType<>(
                (containerId, inventory) -> new PythonContainerMenu(holder[0], containerId,
                        new SimpleContainer(size), inventory, behavior,
                        dataCount > 0 ? new SimpleContainerData(dataCount) : null,
                        false),
                FeatureFlags.VANILLA_SET);
        holder[0] = type;

        Registry.register(BuiltInRegistries.MENU, ident, type);
        REGISTRY.put(ident, new Entry(type, behavior, size, titleKey, dataCount));
        LOGGER.info("Registered menu {} (size={}, title={}, dataCount={})", ident, size, titleKey, dataCount);
    }

    /** 服务端为玩家打开菜单：会同步到客户端并触发客户端界面。容器是临时的，物品不持久化。 */
    public static void open(PyObject playerArg, String id) {
        Entry entry = require(id);
        requirePlayer(playerArg).openMenu(new PyMenuProvider(entry.type(), entry.behavior(),
                new SimpleContainer(entry.containerSize()), entry.titleKey(),
                // 没有真实数据源，但仍要按同一数量挂数据槽，否则与客户端数量不一致
                entry.dataCount() > 0 ? new SimpleContainerData(entry.dataCount()) : null));
    }

    /**
     * 从方块打开菜单：用该方块实体的物品栏作为容器，因此**物品随方块持久化**；
     * 若菜单注册时声明了 {@code dataCount}，数据槽直接读该方块实体的 {@code getData}。
     *
     * <p>坐标必须由调用方给——原版 {@code openMenu} 只有一个重载、不传坐标。
     * Python 的 {@code use(state, level, pos, player, hit)} 里正好都有。
     *
     * <p>客户端菜单不需要坐标：物品靠槽位同步、数据靠数据槽同步，真实容器只有服务端需要。
     */
    public static void openBlock(PyObject playerArg, String id, PyObject levelArg, PyObject posArg) {
        ServerPlayer player = requirePlayer(playerArg);
        Level level = (Level) levelArg.__tojava__(Level.class);
        BlockPos pos = (BlockPos) posArg.__tojava__(BlockPos.class);
        PythonBlockEntity blockEntity = level != null && pos != null
                && level.getBlockEntity(pos) instanceof PythonBlockEntity found ? found : null;
        Entry entry = require(id);
        SimpleContainer container = blockEntity != null ? blockEntity.container() : null;
        ContainerData data = null;
        if (entry.dataCount() > 0) {
            data = blockEntity != null ? new PyBlockEntityData(blockEntity, entry.dataCount())
                    : new SimpleContainerData(entry.dataCount());
        }
        player.openMenu(new PyMenuProvider(entry.type(), entry.behavior(),
                container != null ? container : new SimpleContainer(entry.containerSize()),
                entry.titleKey(), data));
    }

    private static ServerPlayer requirePlayer(PyObject playerArg) {
        ServerPlayer player = (ServerPlayer) playerArg.__tojava__(ServerPlayer.class);
        if (player == null) {
            throw new IllegalArgumentException("PyMenus expects a ServerPlayer");
        }
        return player;
    }

    /** 供客户端门面按 id 取菜单类型。 */
    public static MenuType<PythonContainerMenu> type(Identifier id) {
        return requireById(id).type();
    }

    private static Entry require(String id) {
        return requireById(ModIds.parse(id));
    }

    private static Entry requireById(Identifier id) {
        Entry entry = REGISTRY.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Menu not registered: " + id);
        }
        return entry;
    }
}
