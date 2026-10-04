package com.AlerCello86767.jython_language_runtime;

import static com.AlerCello86767.jython_language_runtime.core.Params.asBoolean;
import static com.AlerCello86767.jython_language_runtime.core.Params.asColor;
import static com.AlerCello86767.jython_language_runtime.core.Params.asDouble;
import static com.AlerCello86767.jython_language_runtime.core.Params.asFloat;
import static com.AlerCello86767.jython_language_runtime.core.Params.asInt;
import static com.AlerCello86767.jython_language_runtime.core.Params.asList;
import static com.AlerCello86767.jython_language_runtime.core.Params.asMap;
import static com.AlerCello86767.jython_language_runtime.core.Params.asString;

import com.AlerCello86767.jython_language_runtime.core.ModIds;
import com.AlerCello86767.jython_language_runtime.core.PyForwarder;
import com.AlerCello86767.jython_language_runtime.host.DelegatingItem;
import com.AlerCello86767.jython_language_runtime.host.PythonBlock;
import com.AlerCello86767.jython_language_runtime.host.PythonBlockEntity;
import com.AlerCello86767.jython_language_runtime.host.PythonFacingBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.python.core.PyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.advancements.criterion.BlockPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.saveddata.maps.MapId;

/**
 * 注册门面：供 Python 侧以「声明式参数包」注册方块与物品。
 *
 * <p>Python 不需要接触 Block/Item/Registry 等原生类型，只需传 id 与一个由
 * 字符串、数字、布尔值组成的 Map；本门面负责语义翻译（字符串 → 游戏对象）、
 * 默认值补全、对象构造与注册表写入。所有调用必须发生在模组初始化阶段。
 */
public final class Registration {
    private static final Logger LOGGER = LoggerFactory.getLogger("jython_language_runtime/Registration");

    /** 序列化许可恒真的 HolderOwner，专供 {@link #lazyHolder(ResourceKey)} 使用。 */
    private static final HolderOwner<Object> PERMISSIVE_OWNER = new HolderOwner<Object>() {
        @Override
        public boolean canSerializeIn(HolderOwner<Object> owner) {
            return true;
        }
    };

    private Registration() {
    }

    /**
     * 注册一个方块，同时自动注册其同名 BlockItem（物品形态）。
     *
     * <p>参数包字段（均可省略，除无默认语义的说明外）：
     * <ul>
     *   <li>{@code hardness} (float)：挖掘硬度，默认 1.0</li>
     *   <li>{@code resistance} (float)：爆炸抗性，默认与 hardness 相同</li>
     *   <li>{@code sound} (String)：音效组，如 metal/stone/wood/glass/gravel，默认 stone</li>
     *   <li>{@code requiresTool} (boolean)：是否空手不掉落，默认 false</li>
     *   <li>{@code miningTool} (String)：挖掘工具类型 pickaxe/axe/shovel/hoe，仅校验</li>
     *   <li>{@code miningLevel} (String)：挖掘等级 hand/wood/stone/iron/diamond/netherite，
     *       仅校验；实际挖掘标签由数据包 tags 提供（游戏标签为数据驱动，运行时不可写入）</li>
     * </ul>
     *
     * <p>方块状态属性（自定义 {@code StateDefinition}，作物 age / 水位 / 开关等必需）：
     * <ul>
     *   <li>{@code properties} (Map)：属性名 → 描述 map。属性名须匹配 {@code [a-z0-9_]+}。
     *       描述里的 {@code type} 取值：
     *       <ul>
     *         <li>{@code "int"}：整型，需 {@code max}（可选 {@code min}，默认 0）→ {@code IntegerProperty}</li>
     *         <li>{@code "bool"}：布尔 → {@code BooleanProperty}</li>
     *         <li>{@code "enum"}：字符串枚举，需非空 {@code values} 字符串列表（取值须匹配
     *             {@code [a-z0-9_]+}）；用按字符串映射的自定义属性承载（Python 侧仍以字符串读写）</li>
     *       </ul>
     *   </li>
     *   <li>{@code defaults} (Map)：属性名 → 默认取值，用于生成默认 BlockState；属性名必须在
     *       {@code properties} 里声明过。取值类型须与属性类型匹配。</li>
     * </ul>
     * <p>Python 侧读写状态用本门面的静态便捷方法：{@code getInt/setInt}、{@code getBool/setBool}、
     * {@code getString/setString}、{@code hasProperty}（{@code set*} 返回新的 BlockState，
     * 交给 {@code level.setBlock} 写回）。
     *
     * <p>方块实体（P10）：
     * <ul>
     *   <li>{@code blockEntity} (Python 类)：给了就改用 {@link PythonBlock} 宿主，并连带注册
     *       BlockEntityType。Python 类**每个方块实体实例化一份**，可用钩子见
     *       {@link PythonBlockEntity}（{@code tick} / {@code saveAdditional} / {@code loadAdditional}）</li>
     *   <li>{@code ticking} (boolean)：是否每 tick 回调 Python 的 {@code tick}，默认 false</li>
     *   <li>{@code sync} (boolean)：是否把方块实体数据同步给客户端，默认 true</li>
     * </ul>
     * <p>{@code ticking} 与 {@code sync} 仅在同时给了 {@code blockEntity} 时生效。
     */
    public static void registerBlock(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        float hardness = asFloat(options.get("hardness"), 1.0f);
        float resistance = asFloat(options.get("resistance"), hardness);
        String soundName = asString(options.get("sound"), "stone");
        SoundType soundType = resolveSound(soundName);
        boolean requiresTool = asBoolean(options.get("requiresTool"), false);

        if (options.containsKey("miningTool")) {
            checkEnum(asString(options.get("miningTool"), ""),
                    "pickaxe", "axe", "shovel", "hoe");
        }
        if (options.containsKey("miningLevel")) {
            checkEnum(asString(options.get("miningLevel"), ""),
                    "hand", "wood", "stone", "iron", "diamond", "netherite");
        }

        // 方块状态属性声明：Python 用 properties 描述自定义 StateDefinition 属性
        List<Property<?>> declaredProperties = buildDeclaredProperties(asMap(options.get("properties")));
        Map<String, Property<?>> declaredByName = new LinkedHashMap<>();
        for (Property<?> property : declaredProperties) {
            declaredByName.put(property.getName(), property);
        }
        Map<String, Object> defaults = asMap(options.get("defaults"));
        if (defaults != null && !defaults.isEmpty() && declaredProperties.isEmpty()) {
            throw new IllegalArgumentException("方块 \"" + path + "\" 提供了 defaults 但没有声明 properties");
        }
        boolean facing = asBoolean(options.get("facing"), false);
        if (facing && declaredByName.containsKey("facing")) {
            throw new IllegalArgumentException("方块 \"" + path
                    + "\" 的 properties 不能声明 \"facing\"：它由 facing=true 自动提供");
        }

        // 构造方块设置 → 方块实例（26.1 要求 Properties 先绑定注册键）
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, id))
                .strength(hardness, resistance)
                .sound(soundType);
        if (requiresTool) {
            properties.requiresCorrectToolForDrops();
        }
        // 作物/非满方块：noCollision 关掉碰撞（getShape 只影响轮廓与可视形状），
        // noOcclusion 让方块不遮挡邻接面（玻璃/机器外壳之类需要）
        if (asBoolean(options.get("noCollision"), false)) {
            properties.noCollision();
        }
        if (asBoolean(options.get("noOcclusion"), false)) {
            properties.noOcclusion();
        }

        // blockEntity：给了 Python 类就用「带方块实体的方块」宿主
        // behavior：只给方块级行为钩子、不创建方块实体（作物这类用这个，别白挂一个 BE）
        Object blockEntity = options.get("blockEntity");
        Object blockBehavior = options.get("behavior");
        PyObject behavior = blockEntity instanceof PyObject py ? py
                : (blockBehavior instanceof PyObject plainBehavior ? plainBehavior : null);
        boolean hasBlockEntity = blockEntity instanceof PyObject;
        boolean ticking = asBoolean(options.get("ticking"), false);
        boolean sync = asBoolean(options.get("sync"), true);
        int containerSize = asInt(options.get("size"), 9);

        // 需要方块实体、需要声明朝向、或需要自定义方块状态属性时，都得用 Python 宿主——
        // 基础 Block 无法声明方块状态属性
        boolean usePythonHost = behavior != null || facing || !declaredProperties.isEmpty();
        // 动态属性必须在 Block 构造（super()）期间可见：createBlockStateDefinition 在构造期被调用，
        // 那时实例字段尚未赋值，因此用 ThreadLocal 传递，构造完成后立即清理
        if (!declaredProperties.isEmpty()) {
            PythonBlock.PENDING_PROPERTIES.set(declaredProperties);
        }
        PythonBlock pythonBlock = null;
        try {
            pythonBlock = usePythonHost
                    ? (facing
                            ? new PythonFacingBlock(properties, behavior, ticking, sync, containerSize)
                            : new PythonBlock(properties, behavior, ticking, sync, containerSize, hasBlockEntity))
                    : null;
            // 构造完成后 StateDefinition 才完整，此时才能写入默认状态取值
            if (pythonBlock != null) {
                pythonBlock.applyDefaultValues(defaults);
            }
        } finally {
            if (!declaredProperties.isEmpty()) {
                PythonBlock.PENDING_PROPERTIES.remove();
            }
        }
        // lambda 工厂要求捕获「实际上的最终变量」，故取一个 final 别名
        final PythonBlock host = pythonBlock;
        Block block = host != null ? host : new Block(properties);

        Registry.register(BuiltInRegistries.BLOCK, id, block);
        // 关联注册：同名 BlockItem，使方块可以以物品形式持有/放置
        Item.Properties blockItemProperties = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id));
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, blockItemProperties));

        if (host != null && host.hasBlockEntity()) {
            // 工厂委托回方块自身，绕开「BlockEntityType 与方块互相依赖」的循环
            BlockEntityType<PythonBlockEntity> entityType = FabricBlockEntityTypeBuilder
                    .create((pos, state) -> (PythonBlockEntity) host.newBlockEntity(pos, state), block)
                    .build();
            host.attachEntityType(entityType);
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, entityType);
            // 把方块实体的物品栏暴露给自动化：管道/漏斗经 fabric-transfer-api-v1 直接取放。
            // 方向透传——原版很多方块只在特定面暴露，这里交给 ContainerStorage 判断
            ItemStorage.SIDED.registerForBlockEntity(
                    (be, direction) -> ContainerStorage.of(be.container(), direction),
                    entityType);
        }
        LOGGER.info("Registered block {} (hardness={}, resistance={}, sound={}, requiresTool={}, blockEntity={},"
                        + " stateProperties={})",
                id, hardness, resistance, soundName, requiresTool, host != null && host.hasBlockEntity(),
                declaredByName.keySet());
    }

    /**
     * 注册一个物品。
     *
     * <p>参数包字段：
     * <ul>
     *   <li>{@code maxStackSize} (int)：最大堆叠数，默认 64</li>
     *   <li>{@code rarity} (String)：common/uncommon/rare/epic，默认 common</li>
     *   <li>{@code group} (String)：创造模式分组，取值 building_blocks / colored_blocks /
     *       natural_blocks / functional_blocks / redstone_blocks / tools_and_utilities /
     *       combat / food_and_drinks / ingredients / spawn_eggs / op_blocks，
     *       默认不加入任何分组</li>
     * </ul>
     *
     * <p>第一批「基础属性」：
     * <ul>
     *   <li>{@code maxDamage} (int)：最大耐久。设置后物品自动变为不可堆叠（堆叠数被压到 1）</li>
     *   <li>{@code fireResistant} (boolean)：是否防火，默认 false</li>
     *   <li>{@code enchantability} (int)：附魔能力，默认不设置</li>
     *   <li>{@code food} (Map)：食物参数包，见下</li>
     * </ul>
     *
     * <p>{@code food} 子字段：
     * <ul>
     *   <li>{@code nutrition} (int)：饥饿值，默认 0</li>
     *   <li>{@code saturation} (float)：饱和度系数（非绝对值），默认 0</li>
     *   <li>{@code alwaysEat} (boolean)：是否总是可食用，默认 false</li>
     *   <li>{@code returns} (String)：食用后返回的物品 id（如 {@code minecraft:bowl}），可不传；
     *       该物品必须已注册，因此 Python 侧要保证依赖先注册</li>
     *   <li>{@code effects} (List&lt;Map&gt)：药水效果列表，每项含 {@code id}（效果 id）、
     *       {@code duration}（tick，默认 200）、{@code amplifier}（等级，默认 0）、
     *       {@code probability}（触发概率，默认 1.0）</li>
     * </ul>
     *
     * <p>{@code food} 的 Consumable 子字段（给任意一个才会覆盖原版 DEFAULT_FOOD）：
     * <ul>
     *   <li>{@code consumeSeconds} (float)：进食耗时秒数，默认 1.6</li>
     *   <li>{@code animation} (String)：none/eat/drink/block/bow/trident/crossbow/spyglass/
     *       toot_horn/brush/bundle/spear</li>
     *   <li>{@code sound} (String)：进食音效 id，如 {@code minecraft:entity.generic.drink}</li>
     *   <li>{@code particles} (boolean)：是否产生进食粒子，默认 true</li>
     * </ul>
     *
     * <p>第二批「工具 / 盔甲 / 可装备」：
     * <ul>
     *   <li>{@code tool} (Map)：{@code kind}（pickaxe/axe/hoe/shovel/sword，决定默认挖掘标签）、
     *       {@code material}（原版材质 wood/stone/copper/iron/diamond/gold/netherite）、
     *       {@code mineableTag}（可选，覆盖默认挖掘标签，sword 不适用）、
     *       {@code attackDamage}/{@code attackSpeed}（float）、
     *       {@code customMaterial}（material 缺省时生效：durability / speed /
     *       attackDamageBonus / enchantmentValue / incorrectForDrops(tag id) / repairItems(tag id)）</li>
     *   <li>{@code armor} (Map)：{@code type}（helmet/chestplate/leggings/boots/body）、
     *       {@code material}（原版材质 leather/copper/chainmail/iron/gold/diamond/
     *       turtle_scute/netherite/armadillo_scute）、
     *       {@code customMaterial}（material 缺省时生效：durability / defense(部位→护甲值) /
     *       enchantmentValue / equipSound(sound id) / toughness / knockbackResistance /
     *       repairIngredient(tag id) / assetId）</li>
     *   <li>{@code equippable} (Map)：{@code slot}（必填）、{@code equipSound}/{@code assetId}/
     *       {@code cameraOverlay}/{@code shearingSound}（id 字符串）、
     *       {@code dispensable}/{@code swappable}/{@code damageOnHurt}/{@code equipOnInteract}/
     *       {@code canBeSheared}（boolean）、{@code allowedEntities}（实体 id 列表）</li>
     * </ul>
     *
     * <p>第三批「内容增强」：
     * <ul>
     *   <li>{@code customName} (String)：直接覆盖显示名（非翻译键）</li>
     *   <li>{@code lore} (List&lt;String&gt)：悬停描述行</li>
     *   <li>{@code enchantments} (Map)：附魔 id → 等级。附魔是数据驱动注册表，初始化期无法取
     *       holder，这里使用按 key 延迟解析的 holder，见 {@link #lazyHolder(ResourceKey)}</li>
     *   <li>{@code attributeModifiers} (List&lt;Map&gt)：每项 {@code attribute}（属性 id）、
     *       {@code amount}（double）、{@code operation}（add_value/add_multiplied_base/
     *       add_multiplied_total）、{@code slot}（any/mainhand/offhand/hand/feet/legs/chest/
     *       head/armor/body）、{@code id}（可选，修饰符 id）</li>
     *   <li>{@code unbreakable} (boolean)：不可破坏</li>
     *   <li>{@code glintOverride} (boolean)：true 强制附魔光效、false 强制隐藏</li>
     * </ul>
     *
     * <p>物品行为（P7）：
     * <ul>
     *   <li>{@code behavior} (Python 对象)：传入后物品改用 {@link DelegatingItem} 转发宿主。
     *       Python 侧定义与 Item 方法同名的函数即可，未定义的走原版行为。可用名：
     *       {@code use} / {@code useOn} / {@code interactLivingEntity} / {@code finishUsingItem} /
     *       {@code releaseUsing} / {@code getUseDuration} / {@code getUseAnimation} /
     *       {@code onUseTick} / {@code hurtEnemy} / {@code postHurtEnemy} / {@code mineBlock} /
     *       {@code getDestroySpeed} / {@code inventoryTick} / {@code onCraftedBy} / {@code isFoil}</li>
     * </ul>
     *
     * <p>第四批「特殊场景」：
     * <ul>
     *   <li>{@code recipeRemainder} (String)：合成后留在工作台的物品 id（必须已注册）</li>
     *   <li>{@code repairCost} (int)：铁砧修理惩罚</li>
     *   <li>{@code canBreak} / {@code canPlaceOn} (List&lt;String&gt)：冒险模式允许破坏/放置的方块 id 列表</li>
     *   <li>{@code container} (List&lt;Map&gt)：容器内容，每项 {@code item}（物品 id）、{@code count}（默认 1）</li>
     *   <li>{@code bannerPatterns} (List&lt;Map&gt)：每项 {@code pattern}（图案 id）、{@code color}（颜色名）</li>
     *   <li>{@code dyedColor} (int 或 "#RRGGBB")：染色颜色</li>
     *   <li>{@code mapId} (int)：地图 id</li>
     * </ul>
     */
    public static void registerItem(String path, Map<String, Object> options) {
        Identifier id = ModIds.of(path);
        int maxStackSize = asInt(options.get("maxStackSize"), 64);
        Rarity rarity = resolveRarity(asString(options.get("rarity"), "common"));
        String group = asString(options.get("group"), null);

        Item.Properties properties = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, id))
                .stacksTo(maxStackSize)
                .rarity(rarity);

        // durability() 内部会把最大堆叠数一并设为 1，所以必须排在 stacksTo 之后
        int maxDamage = asInt(options.get("maxDamage"), 0);
        if (maxDamage > 0) {
            properties.durability(maxDamage);
        }
        if (asBoolean(options.get("fireResistant"), false)) {
            properties.fireResistant();
        }
        if (options.containsKey("enchantability")) {
            properties.enchantable(asInt(options.get("enchantability"), 0));
        }
        applyFood(properties, asMap(options.get("food")));
        applyTool(properties, asMap(options.get("tool")));
        applyArmor(properties, asMap(options.get("armor")));
        applyEquippable(properties, asMap(options.get("equippable")));
        applyContent(properties, options);
        applyEnchantments(properties, options);
        applyAttributeModifiers(properties, options, path);
        applyAdventure(properties, options);
        applySpecialContent(properties, options);

        // recipeRemainder：与 food.returns 同理，被引用的物品必须先注册
        String recipeRemainder = asString(options.get("recipeRemainder"), null);
        if (recipeRemainder != null) {
            properties.craftRemainder(resolveItem(recipeRemainder));
        }

        // behavior：给了 Python 行为对象就用转发宿主，否则用普通 Item
        Object behavior = options.get("behavior");
        Item item = behavior instanceof PyObject
                ? new DelegatingItem(properties, (PyObject) behavior)
                : new Item(properties);
        Registry.register(BuiltInRegistries.ITEM, id, item);

        if (group != null) {
            ResourceKey<CreativeModeTab> tab = resolveGroup(group);
            CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(item));
        }
        // 只打印选项原值与最终事实，不打印推导值：maxStackSize 可能被 durability() 压到 1
        LOGGER.info("Registered item {} (rarity={}, group={}, maxDamage={}, options={})",
                id, rarity.name(), group, maxDamage, options.keySet());
    }

    /** 把 {@code food} 参数包翻译为 FoodProperties / Consumable，并附加到物品属性上。 */
    private static void applyFood(Item.Properties properties, Map<String, Object> food) {
        if (food == null) {
            return;
        }
        FoodProperties.Builder builder = new FoodProperties.Builder()
                .nutrition(asInt(food.get("nutrition"), 0))
                .saturationModifier(asFloat(food.get("saturation"), 0.0f));
        if (asBoolean(food.get("alwaysEat"), false)) {
            builder.alwaysEdible();
        }
        FoodProperties foodProperties = builder.build();

        Consumable consumable = buildConsumable(food);
        if (consumable == null) {
            properties.food(foodProperties);
        } else {
            properties.food(foodProperties, consumable);
        }

        String returns = asString(food.get("returns"), null);
        if (returns != null) {
            properties.usingConvertsTo(resolveItem(returns));
        }
    }

    /**
     * 由 {@code food} 的 Consumable 相关字段构造 Consumable。
     *
     * <p>一个都没给时返回 null，此时 {@code properties.food(FoodProperties)} 会套用原版的
     * {@code Consumable.DEFAULT_FOOD}（进食动画 EAT、音效 GENERIC_EAT）。
     */
    private static Consumable buildConsumable(Map<String, Object> food) {
        boolean custom = food.containsKey("consumeSeconds") || food.containsKey("animation")
                || food.containsKey("sound") || food.containsKey("particles")
                || asList(food.get("effects")) != null;
        if (!custom) {
            return null;
        }
        Consumable.Builder builder = Consumable.builder();
        if (food.containsKey("consumeSeconds")) {
            builder.consumeSeconds(asFloat(food.get("consumeSeconds"), 1.6f));
        }
        String animation = asString(food.get("animation"), null);
        if (animation != null) {
            builder.animation(resolveUseAnimation(animation));
        }
        String sound = asString(food.get("sound"), null);
        if (sound != null) {
            builder.sound(resolveSoundEvent(sound));
        }
        if (food.containsKey("particles")) {
            builder.hasConsumeParticles(asBoolean(food.get("particles"), true));
        }
        List<?> effects = asList(food.get("effects"));
        if (effects != null) {
            for (Object raw : effects) {
                Map<String, Object> effect = asMap(raw);
                if (effect == null) {
                    continue;
                }
                MobEffectInstance instance = new MobEffectInstance(
                        resolveEffect(asString(effect.get("id"), null)),
                        asInt(effect.get("duration"), 200),
                        asInt(effect.get("amplifier"), 0));
                builder.onConsume(new ApplyStatusEffectsConsumeEffect(
                        instance, asFloat(effect.get("probability"), 1.0f)));
            }
        }
        return builder.build();
    }

    private static ItemUseAnimation resolveUseAnimation(String name) {
        switch (name) {
        case "none": return ItemUseAnimation.NONE;
        case "eat": return ItemUseAnimation.EAT;
        case "drink": return ItemUseAnimation.DRINK;
        case "block": return ItemUseAnimation.BLOCK;
        case "bow": return ItemUseAnimation.BOW;
        case "trident": return ItemUseAnimation.TRIDENT;
        case "crossbow": return ItemUseAnimation.CROSSBOW;
        case "spyglass": return ItemUseAnimation.SPYGLASS;
        case "toot_horn": return ItemUseAnimation.TOOT_HORN;
        case "brush": return ItemUseAnimation.BRUSH;
        case "bundle": return ItemUseAnimation.BUNDLE;
        case "spear": return ItemUseAnimation.SPEAR;
        default:
            throw new IllegalArgumentException("Unknown use animation: " + name);
        }
    }

    // ---------- 第二批：工具 / 盔甲 / 可装备 ----------

    /** {@code tool} 参数包 → ToolMaterial + 挖掘标签 + 攻击属性。 */
    private static void applyTool(Item.Properties properties, Map<String, Object> tool) {
        if (tool == null) {
            return;
        }
        ToolMaterial material = resolveToolMaterial(tool);
        float attackDamage = asFloat(tool.get("attackDamage"), 0.0f);
        float attackSpeed = asFloat(tool.get("attackSpeed"), 0.0f);
        String kind = asString(tool.get("kind"), null);
        String mineableTag = asString(tool.get("mineableTag"), null);

        if ("sword".equals(kind)) {
            properties.sword(material, attackDamage, attackSpeed);
            return;
        }
        if (mineableTag != null) {
            // tool() 的第五个参数是 disableBlockingForSeconds，原版工具均为 0
            properties.tool(material, blockTag(mineableTag), attackDamage, attackSpeed, 0.0f);
            return;
        }
        switch (kind == null ? "" : kind) {
        case "pickaxe":
            properties.pickaxe(material, attackDamage, attackSpeed);
            return;
        case "axe":
            properties.axe(material, attackDamage, attackSpeed);
            return;
        case "hoe":
            properties.hoe(material, attackDamage, attackSpeed);
            return;
        case "shovel":
            properties.shovel(material, attackDamage, attackSpeed);
            return;
        default:
            throw new IllegalArgumentException(
                    "tool requires 'kind' (pickaxe/axe/hoe/shovel/sword) or 'mineableTag'");
        }
    }

    /** 原版材质名 → ToolMaterial 常量；{@code material} 缺省时按 {@code customMaterial} 构造。 */
    private static ToolMaterial resolveToolMaterial(Map<String, Object> tool) {
        String name = asString(tool.get("material"), null);
        if (name != null) {
            switch (name) {
            case "wood": return ToolMaterial.WOOD;
            case "stone": return ToolMaterial.STONE;
            case "copper": return ToolMaterial.COPPER;
            case "iron": return ToolMaterial.IRON;
            case "diamond": return ToolMaterial.DIAMOND;
            case "gold": return ToolMaterial.GOLD;
            case "netherite": return ToolMaterial.NETHERITE;
            default:
                throw new IllegalArgumentException("Unknown tool material: " + name);
            }
        }
        Map<String, Object> custom = asMap(tool.get("customMaterial"));
        if (custom == null) {
            throw new IllegalArgumentException("tool requires 'material' or 'customMaterial'");
        }
        return new ToolMaterial(
                blockTag(asString(custom.get("incorrectForDrops"), null), BlockTags.INCORRECT_FOR_WOODEN_TOOL),
                asInt(custom.get("durability"), 100),
                asFloat(custom.get("speed"), 1.0f),
                asFloat(custom.get("attackDamageBonus"), 0.0f),
                asInt(custom.get("enchantmentValue"), 0),
                itemTag(asString(custom.get("repairItems"), null), ItemTags.WOODEN_TOOL_MATERIALS));
    }

    /** {@code armor} 参数包 → ArmorMaterial + 部位。 */
    private static void applyArmor(Item.Properties properties, Map<String, Object> armor) {
        if (armor == null) {
            return;
        }
        String typeName = asString(armor.get("type"), null);
        if (typeName == null) {
            throw new IllegalArgumentException("armor requires 'type'");
        }
        properties.humanoidArmor(resolveArmorMaterial(armor), resolveArmorType(typeName));
    }

    private static ArmorType resolveArmorType(String name) {
        switch (name) {
        case "helmet": return ArmorType.HELMET;
        case "chestplate": return ArmorType.CHESTPLATE;
        case "leggings": return ArmorType.LEGGINGS;
        case "boots": return ArmorType.BOOTS;
        case "body": return ArmorType.BODY;
        default:
            throw new IllegalArgumentException("Unknown armor type: " + name);
        }
    }

    /** 原版材质名 → ArmorMaterials 常量；{@code material} 缺省时按 {@code customMaterial} 构造。 */
    private static ArmorMaterial resolveArmorMaterial(Map<String, Object> armor) {
        String name = asString(armor.get("material"), null);
        if (name != null) {
            switch (name) {
            case "leather": return ArmorMaterials.LEATHER;
            case "copper": return ArmorMaterials.COPPER;
            case "chainmail": return ArmorMaterials.CHAINMAIL;
            case "iron": return ArmorMaterials.IRON;
            case "gold": return ArmorMaterials.GOLD;
            case "diamond": return ArmorMaterials.DIAMOND;
            case "turtle_scute": return ArmorMaterials.TURTLE_SCUTE;
            case "netherite": return ArmorMaterials.NETHERITE;
            case "armadillo_scute": return ArmorMaterials.ARMADILLO_SCUTE;
            default:
                throw new IllegalArgumentException("Unknown armor material: " + name);
            }
        }
        Map<String, Object> custom = asMap(armor.get("customMaterial"));
        if (custom == null) {
            throw new IllegalArgumentException("armor requires 'material' or 'customMaterial'");
        }
        String assetId = asString(custom.get("assetId"), null);
        if (assetId == null) {
            // EquipmentAsset 是数据驱动资源，没有通用默认值，必须显式给出
            throw new IllegalArgumentException("armor customMaterial requires 'assetId'");
        }
        Map<ArmorType, Integer> defense = new LinkedHashMap<>();
        Map<String, Object> defenseIn = asMap(custom.get("defense"));
        if (defenseIn != null) {
            for (Map.Entry<String, Object> entry : defenseIn.entrySet()) {
                defense.put(resolveArmorType(entry.getKey()), asInt(entry.getValue(), 0));
            }
        }
        return new ArmorMaterial(
                asInt(custom.get("durability"), 100),
                defense,
                asInt(custom.get("enchantmentValue"), 0),
                resolveSoundEvent(asString(custom.get("equipSound"), "minecraft:item.armor.equip_iron")),
                asFloat(custom.get("toughness"), 0.0f),
                asFloat(custom.get("knockbackResistance"), 0.0f),
                itemTag(asString(custom.get("repairIngredient"), null), ItemTags.REPAIRS_IRON_ARMOR),
                ResourceKey.create(EquipmentAssets.ROOT_ID, parseId(assetId)));
    }

    /** {@code equippable} 参数包 → Equippable 数据组件。 */
    private static void applyEquippable(Item.Properties properties, Map<String, Object> equippable) {
        if (equippable == null) {
            return;
        }
        String slotName = asString(equippable.get("slot"), null);
        if (slotName == null) {
            throw new IllegalArgumentException("equippable requires 'slot'");
        }
        Equippable.Builder builder = Equippable.builder(resolveEquipmentSlot(slotName));

        String equipSound = asString(equippable.get("equipSound"), null);
        if (equipSound != null) {
            builder.setEquipSound(resolveSoundEvent(equipSound));
        }
        String assetId = asString(equippable.get("assetId"), null);
        if (assetId != null) {
            builder.setAsset(ResourceKey.create(EquipmentAssets.ROOT_ID, parseId(assetId)));
        }
        String cameraOverlay = asString(equippable.get("cameraOverlay"), null);
        if (cameraOverlay != null) {
            builder.setCameraOverlay(parseId(cameraOverlay));
        }
        String shearingSound = asString(equippable.get("shearingSound"), null);
        if (shearingSound != null) {
            builder.setShearingSound(resolveSoundEvent(shearingSound));
        }
        if (equippable.containsKey("dispensable")) {
            builder.setDispensable(asBoolean(equippable.get("dispensable"), false));
        }
        if (equippable.containsKey("swappable")) {
            builder.setSwappable(asBoolean(equippable.get("swappable"), false));
        }
        if (equippable.containsKey("damageOnHurt")) {
            builder.setDamageOnHurt(asBoolean(equippable.get("damageOnHurt"), false));
        }
        if (equippable.containsKey("equipOnInteract")) {
            builder.setEquipOnInteract(asBoolean(equippable.get("equipOnInteract"), false));
        }
        if (equippable.containsKey("canBeSheared")) {
            builder.setCanBeSheared(asBoolean(equippable.get("canBeSheared"), false));
        }
        List<?> allowed = asList(equippable.get("allowedEntities"));
        if (allowed != null && !allowed.isEmpty()) {
            List<EntityType<?>> types = new ArrayList<>();
            for (Object raw : allowed) {
                types.add(resolveEntityType(String.valueOf(raw)));
            }
            builder.setAllowedEntities(types.toArray(new EntityType<?>[0]));
        }
        properties.component(DataComponents.EQUIPPABLE, builder.build());
    }

    private static EquipmentSlot resolveEquipmentSlot(String name) {
        switch (name) {
        case "mainhand": return EquipmentSlot.MAINHAND;
        case "offhand": return EquipmentSlot.OFFHAND;
        case "feet": return EquipmentSlot.FEET;
        case "legs": return EquipmentSlot.LEGS;
        case "chest": return EquipmentSlot.CHEST;
        case "head": return EquipmentSlot.HEAD;
        case "body": return EquipmentSlot.BODY;
        case "saddle": return EquipmentSlot.SADDLE;
        default:
            throw new IllegalArgumentException("Unknown equipment slot: " + name);
        }
    }

    // ---------- 第三批：内容增强 ----------

    /** customName / lore / unbreakable / glintOverride。 */
    private static void applyContent(Item.Properties properties, Map<String, Object> options) {
        String customName = asString(options.get("customName"), null);
        if (customName != null) {
            properties.component(DataComponents.CUSTOM_NAME, Component.literal(customName));
        }
        List<?> lore = asList(options.get("lore"));
        if (lore != null && !lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Object raw : lore) {
                lines.add(Component.literal(String.valueOf(raw)));
            }
            properties.component(DataComponents.LORE, new ItemLore(lines));
        }
        if (asBoolean(options.get("unbreakable"), false)) {
            properties.component(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        }
        if (options.containsKey("glintOverride")) {
            properties.component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,
                    asBoolean(options.get("glintOverride"), false));
        }
    }

    /**
     * 附魔列表：附魔 id → 等级。
     *
     * <p>附魔属于数据驱动注册表，模组初始化阶段拿不到 HolderLookup，因此用
     * {@link #lazyHolder(ResourceKey)} 生成按 key 延迟解析的 holder：序列化时写 id，
     * 世界加载后再解析为真实附魔。
     */
    private static void applyEnchantments(Item.Properties properties, Map<String, Object> options) {
        Map<String, Object> enchantments = asMap(options.get("enchantments"));
        if (enchantments == null || enchantments.isEmpty()) {
            return;
        }
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (Map.Entry<String, Object> entry : enchantments.entrySet()) {
            Holder<Enchantment> holder = lazyHolder(
                    ResourceKey.create(Registries.ENCHANTMENT, parseId(entry.getKey())));
            mutable.set(holder, asInt(entry.getValue(), 1));
        }
        properties.component(DataComponents.ENCHANTMENTS, mutable.toImmutable());
    }

    /** 属性修饰符列表；每个修饰符需要一个稳定 id，未提供时按物品路径与下标生成。 */
    private static void applyAttributeModifiers(Item.Properties properties,
                                                Map<String, Object> options, String itemPath) {
        List<?> entries = asList(options.get("attributeModifiers"));
        if (entries == null || entries.isEmpty()) {
            return;
        }
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        int index = 0;
        for (Object raw : entries) {
            Map<String, Object> entry = asMap(raw);
            if (entry == null) {
                throw new IllegalArgumentException("attributeModifiers[" + index + "] must be a map");
            }
            String attributeId = asString(entry.get("attribute"), null);
            if (attributeId == null) {
                throw new IllegalArgumentException("attributeModifiers[" + index + "] requires 'attribute'");
            }
            AttributeModifier modifier = new AttributeModifier(
                    parseId(asString(entry.get("id"),
                            ModIds.of(itemPath + "/modifier_" + index).toString())),
                    asDouble(entry.get("amount"), 0.0d),
                    resolveOperation(asString(entry.get("operation"), "add_value")));
            builder.add(resolveAttribute(attributeId), modifier,
                    resolveSlotGroup(asString(entry.get("slot"), "any")));
            index++;
        }
        properties.component(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
    }

    // ---------- 第四批：特殊场景 ----------

    /** 冒险模式：canBreak / canPlaceOn。 */
    private static void applyAdventure(Item.Properties properties, Map<String, Object> options) {
        List<BlockPredicate> canBreak = buildBlockPredicates(asList(options.get("canBreak")));
        if (canBreak != null) {
            properties.component(DataComponents.CAN_BREAK, new AdventureModePredicate(canBreak));
        }
        List<BlockPredicate> canPlaceOn = buildBlockPredicates(asList(options.get("canPlaceOn")));
        if (canPlaceOn != null) {
            properties.component(DataComponents.CAN_PLACE_ON, new AdventureModePredicate(canPlaceOn));
        }
    }

    private static List<BlockPredicate> buildBlockPredicates(List<?> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return null;
        }
        List<Block> blocks = new ArrayList<>();
        for (Object raw : blockIds) {
            blocks.add(resolveBlock(String.valueOf(raw)));
        }
        // Registry 本身即 HolderGetter，可直接作为 BlockPredicate.Builder 的查询源
        return List.of(BlockPredicate.Builder.block().of(BuiltInRegistries.BLOCK, blocks).build());
    }

    /** 容器内容 / 旗帜图案 / 染色 / 地图 id / 修理成本。 */
    private static void applySpecialContent(Item.Properties properties, Map<String, Object> options) {
        List<?> container = asList(options.get("container"));
        if (container != null && !container.isEmpty()) {
            List<ItemStack> stacks = new ArrayList<>();
            for (Object raw : container) {
                Map<String, Object> entry = asMap(raw);
                if (entry == null) {
                    throw new IllegalArgumentException("container entries must be maps");
                }
                String itemId = asString(entry.get("item"), null);
                if (itemId == null) {
                    throw new IllegalArgumentException("container entry requires 'item'");
                }
                stacks.add(new ItemStack(resolveItem(itemId), asInt(entry.get("count"), 1)));
            }
            properties.component(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
        }

        List<?> bannerPatterns = asList(options.get("bannerPatterns"));
        if (bannerPatterns != null && !bannerPatterns.isEmpty()) {
            List<BannerPatternLayers.Layer> layers = new ArrayList<>();
            for (Object raw : bannerPatterns) {
                Map<String, Object> entry = asMap(raw);
                if (entry == null) {
                    throw new IllegalArgumentException("bannerPatterns entries must be maps");
                }
                String patternId = asString(entry.get("pattern"), null);
                if (patternId == null) {
                    throw new IllegalArgumentException("bannerPatterns entry requires 'pattern'");
                }
                Holder<BannerPattern> pattern = lazyHolder(
                        ResourceKey.create(Registries.BANNER_PATTERN, parseId(patternId)));
                layers.add(new BannerPatternLayers.Layer(pattern,
                        resolveDyeColor(asString(entry.get("color"), "white"))));
            }
            properties.component(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(layers));
        }

        Object dyedColor = options.get("dyedColor");
        if (dyedColor != null) {
            properties.component(DataComponents.DYED_COLOR, new DyedItemColor(asColor(dyedColor)));
        }
        if (options.containsKey("repairCost")) {
            properties.component(DataComponents.REPAIR_COST, asInt(options.get("repairCost"), 0));
        }
        if (options.containsKey("mapId")) {
            properties.component(DataComponents.MAP_ID, new MapId(asInt(options.get("mapId"), 0)));
        }
    }

    // ---------- 语义翻译：字符串 → 游戏对象 ----------

    private static SoundType resolveSound(String name) {
        switch (name) {
        case "stone": return SoundType.STONE;
        case "metal": return SoundType.METAL;
        case "wood": return SoundType.WOOD;
        case "gravel": return SoundType.GRAVEL;
        case "glass": return SoundType.GLASS;
        case "sand": return SoundType.SAND;
        case "snow": return SoundType.SNOW;
        case "wool": return SoundType.WOOL;
        case "slime": return SoundType.SLIME_BLOCK;
        default:
            throw new IllegalArgumentException("Unknown sound group: " + name);
        }
    }

    private static Rarity resolveRarity(String name) {
        switch (name) {
        case "common": return Rarity.COMMON;
        case "uncommon": return Rarity.UNCOMMON;
        case "rare": return Rarity.RARE;
        case "epic": return Rarity.EPIC;
        default:
            throw new IllegalArgumentException("Unknown rarity: " + name);
        }
    }

    private static ResourceKey<CreativeModeTab> resolveGroup(String name) {
        switch (name) {
        case "building_blocks": return CreativeModeTabs.BUILDING_BLOCKS;
        case "colored_blocks": return CreativeModeTabs.COLORED_BLOCKS;
        case "natural_blocks": return CreativeModeTabs.NATURAL_BLOCKS;
        case "functional_blocks": return CreativeModeTabs.FUNCTIONAL_BLOCKS;
        case "redstone_blocks": return CreativeModeTabs.REDSTONE_BLOCKS;
        case "tools_and_utilities": return CreativeModeTabs.TOOLS_AND_UTILITIES;
        case "combat": return CreativeModeTabs.COMBAT;
        case "food_and_drinks": return CreativeModeTabs.FOOD_AND_DRINKS;
        case "ingredients": return CreativeModeTabs.INGREDIENTS;
        case "spawn_eggs": return CreativeModeTabs.SPAWN_EGGS;
        case "op_blocks": return CreativeModeTabs.OP_BLOCKS;
        default:
            throw new IllegalArgumentException("Unsupported creative group: " + name);
        }
    }

    // ---------- 注册表查询：id 字符串 → 已注册对象 ----------

    /**
     * 按 id 查已注册的物品。
     *
     * <p>物品是「自引用依赖」：引用方必须先于被引用方注册。用 {@code containsKey} 而非
     * {@code getValue} 判断是否存在——默认注册表对未知 id 会返回默认值（air）而非 null。
     */
    private static Item resolveItem(String id) {
        Identifier key = parseId(id);
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            throw new IllegalArgumentException("Unknown item: " + id
                    + "（被引用物品必须先注册，请检查注册顺序）");
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }

    /** 按 id 查状态效果（药水效果）。 */
    private static Holder<MobEffect> resolveEffect(String id) {
        if (id == null) {
            throw new IllegalArgumentException("Food effect requires an 'id'");
        }
        return BuiltInRegistries.MOB_EFFECT.get(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown mob effect: " + id));
    }

    /** 内部别名：id 解析与 path 校验的公共实现在 {@link ModIds}。 */
    private static Identifier parseId(String raw) {
        return ModIds.parse(raw);
    }

    /** 按 id 查已注册方块。默认注册表查不到会返回默认值，故用 {@code containsKey} 判定。 */
    private static Block resolveBlock(String id) {
        Identifier key = parseId(id);
        if (!BuiltInRegistries.BLOCK.containsKey(key)) {
            throw new IllegalArgumentException("Unknown block: " + id);
        }
        return BuiltInRegistries.BLOCK.getValue(key);
    }

    /** 按 id 查音效事件（用于盔甲装备音与可装备音效）。 */
    private static Holder<SoundEvent> resolveSoundEvent(String id) {
        return BuiltInRegistries.SOUND_EVENT.get(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown sound event: " + id));
    }

    /** 按 id 查实体属性（用于属性修饰符）。 */
    static Holder<Attribute> resolveAttribute(String id) {
        return BuiltInRegistries.ATTRIBUTE.get(parseId(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown attribute: " + id));
    }

    /** 按 id 查实体类型（用于可装备的 allowedEntities）。 */
    private static EntityType<?> resolveEntityType(String id) {
        Identifier key = parseId(id);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
            throw new IllegalArgumentException("Unknown entity type: " + id);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(key);
    }

    /** 属性修饰符的作用槽位分组。 */
    private static EquipmentSlotGroup resolveSlotGroup(String name) {
        switch (name) {
        case "any": return EquipmentSlotGroup.ANY;
        case "mainhand": return EquipmentSlotGroup.MAINHAND;
        case "offhand": return EquipmentSlotGroup.OFFHAND;
        case "hand": return EquipmentSlotGroup.HAND;
        case "feet": return EquipmentSlotGroup.FEET;
        case "legs": return EquipmentSlotGroup.LEGS;
        case "chest": return EquipmentSlotGroup.CHEST;
        case "head": return EquipmentSlotGroup.HEAD;
        case "armor": return EquipmentSlotGroup.ARMOR;
        case "body": return EquipmentSlotGroup.BODY;
        case "saddle": return EquipmentSlotGroup.SADDLE;
        default:
            throw new IllegalArgumentException("Unknown attribute slot group: " + name);
        }
    }

    private static AttributeModifier.Operation resolveOperation(String name) {
        switch (name) {
        case "add_value": return AttributeModifier.Operation.ADD_VALUE;
        case "add_multiplied_base": return AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
        case "add_multiplied_total": return AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        default:
            throw new IllegalArgumentException("Unknown attribute operation: " + name);
        }
    }

    private static DyeColor resolveDyeColor(String name) {
        DyeColor color = DyeColor.byName(name, null);
        if (color == null) {
            throw new IllegalArgumentException("Unknown dye color: " + name);
        }
        return color;
    }

    private static TagKey<Block> blockTag(String id) {
        return TagKey.create(Registries.BLOCK, parseId(id));
    }

    private static TagKey<Block> blockTag(String id, TagKey<Block> fallback) {
        return id == null ? fallback : blockTag(id);
    }

    private static TagKey<Item> itemTag(String id, TagKey<Item> fallback) {
        return id == null ? fallback : TagKey.create(Registries.ITEM, parseId(id));
    }

    /**
     * 为数据驱动注册表（附魔、旗帜图案）生成「按 key 延迟解析」的 holder。
     *
     * <p>这类注册表要等数据包加载后才存在，模组初始化阶段取不到 holder。而
     * {@code Holder.Reference.unwrap()} 在未绑定时会退化为 {@code Either.left(key)}，
     * 即按 id 序列化；{@code canSerializeIn} 默认只允许同一个 owner，因此这里配一个
     * 许可恒真的 {@link #PERMISSIVE_OWNER}，使 holder 能正常写出并在世界加载后按 id 解析。
     */
    @SuppressWarnings("unchecked")
    private static <T> Holder<T> lazyHolder(ResourceKey<T> key) {
        return Holder.Reference.createStandAlone((HolderOwner<T>) PERMISSIVE_OWNER, key);
    }

    // ---------- 方块状态属性声明（properties / defaults） ----------

    /** 属性名/枚举取值的合法字符集（与原版一致）。 */
    private static final String VALID_PROPERTY_NAME = "[a-z0-9_]+";

    /**
     * 把 Python 的 {@code properties} 描述翻译成原版属性对象。
     *
     * <p>支持 {@code int}（{@link IntegerProperty}）、{@code bool}（{@link BooleanProperty}）、
     * {@code enum}（按字符串映射的 {@link StringListProperty}）；非法名字、未知类型、缺失参数
     * 都抛带中文提示的 {@link IllegalArgumentException}。
     */
    private static List<Property<?>> buildDeclaredProperties(Map<String, Object> declared) {
        List<Property<?>> result = new ArrayList<>();
        if (declared == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches(VALID_PROPERTY_NAME)) {
                throw new IllegalArgumentException(
                        "方块状态属性名必须匹配 " + VALID_PROPERTY_NAME + "，收到: " + name);
            }
            Map<String, Object> spec = asMap(entry.getValue());
            if (spec == null) {
                throw new IllegalArgumentException("方块状态属性 \"" + name
                        + "\" 的描述必须是 map，例如 {\"type\": \"int\", \"max\": 7}");
            }
            String type = asString(spec.get("type"), null);
            if (type == null) {
                throw new IllegalArgumentException(
                        "方块状态属性 \"" + name + "\" 缺少 \"type\"（int / bool / enum）");
            }
            switch (type) {
            case "int": {
                if (!spec.containsKey("max")) {
                    throw new IllegalArgumentException("int 属性 \"" + name + "\" 缺少 \"max\"");
                }
                int min = asInt(spec.get("min"), 0);
                int max = asInt(spec.get("max"), min);
                if (max < min) {
                    throw new IllegalArgumentException("int 属性 \"" + name + "\" 的 max(" + max
                            + ") 不能小于 min(" + min + ")");
                }
                result.add(IntegerProperty.create(name, min, max));
                break;
            }
            case "bool":
                result.add(BooleanProperty.create(name));
                break;
            case "enum": {
                List<?> rawValues = asList(spec.get("values"));
                if (rawValues == null || rawValues.isEmpty()) {
                    throw new IllegalArgumentException(
                            "enum 属性 \"" + name + "\" 需要非空的 \"values\" 字符串列表");
                }
                List<String> values = new ArrayList<>();
                for (Object raw : rawValues) {
                    String value = asString(raw, null);
                    if (value == null || !value.matches(VALID_PROPERTY_NAME)) {
                        throw new IllegalArgumentException("enum 属性 \"" + name + "\" 的取值必须匹配 "
                                + VALID_PROPERTY_NAME + "，收到: " + raw);
                    }
                    if (values.contains(value)) {
                        throw new IllegalArgumentException(
                                "enum 属性 \"" + name + "\" 的取值重复: " + value);
                    }
                    values.add(value);
                }
                result.add(new StringListProperty(name, values));
                break;
            }
            default:
                throw new IllegalArgumentException("未知的方块状态属性类型 \"" + type
                        + "\"（属性 \"" + name + "\"），支持 int / bool / enum");
            }
        }
        return result;
    }

    /**
     * 「按字符串映射」的枚举属性：取值就是 Python 给的字符串列表。
     *
     * <p>为什么不用 {@link net.minecraft.world.level.block.state.properties.EnumProperty}：它要求
     * 一个编译期存在的 {@code Enum & StringRepresentable} 类，而 Python 在运行期才能给出任意取值；
     * 这里用自定义 {@link Property}&lt;String&gt; 达到同样效果（方块状态 JSON、NBT/网络序列化
     * 都按字符串读写）。
     */
    private static final class StringListProperty extends Property<String> {
        private final List<String> values;

        StringListProperty(String name, List<String> values) {
            super(name, String.class);
            this.values = List.copyOf(values);
        }

        @Override
        public List<String> getPossibleValues() {
            return values;
        }

        @Override
        public String getName(String value) {
            return value;
        }

        @Override
        public Optional<String> getValue(String value) {
            return values.contains(value) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public int getInternalIndex(String value) {
            return values.indexOf(value);
        }
    }

    // ---------- 方块状态读写便捷方法（供 Python 行为类调用） ----------

    /** 方块当前状态是否声明了名为 {@code name} 的属性。 */
    public static boolean hasProperty(BlockState state, String name) {
        return findProperty(state, name) != null;
    }

    /** 读取 int 属性；属性缺失或类型不符时抛带中文提示的 {@link IllegalArgumentException}。 */
    public static int getInt(BlockState state, String name) {
        Property<?> property = requireProperty(state, name);
        if (!(property instanceof IntegerProperty intProperty)) {
            throw new IllegalArgumentException("方块状态属性 \"" + name + "\" 不是 int 类型");
        }
        return state.getValue(intProperty);
    }

    /** 写入 int 属性，返回新的 {@link BlockState}（原状态不可变）；取值非法时抛错。 */
    public static BlockState setInt(BlockState state, String name, int value) {
        Property<?> property = requireProperty(state, name);
        if (!(property instanceof IntegerProperty intProperty)) {
            throw new IllegalArgumentException("方块状态属性 \"" + name + "\" 不是 int 类型");
        }
        return state.setValue(intProperty, value);
    }

    /** 读取 bool 属性。 */
    public static boolean getBool(BlockState state, String name) {
        Property<?> property = requireProperty(state, name);
        if (!(property instanceof BooleanProperty boolProperty)) {
            throw new IllegalArgumentException("方块状态属性 \"" + name + "\" 不是 bool 类型");
        }
        return state.getValue(boolProperty);
    }

    /** 写入 bool 属性，返回新的 {@link BlockState}。 */
    public static BlockState setBool(BlockState state, String name, boolean value) {
        Property<?> property = requireProperty(state, name);
        if (!(property instanceof BooleanProperty boolProperty)) {
            throw new IllegalArgumentException("方块状态属性 \"" + name + "\" 不是 bool 类型");
        }
        return state.setValue(boolProperty, value);
    }

    /** 读取枚举（字符串）属性，返回取值字符串。 */
    public static String getString(BlockState state, String name) {
        Property<?> property = requireProperty(state, name);
        if (!(property instanceof StringListProperty stringProperty)) {
            throw new IllegalArgumentException("方块状态属性 \"" + name + "\" 不是 enum 类型");
        }
        return state.getValue(stringProperty);
    }

    /** 写入枚举（字符串）属性，返回新的 {@link BlockState}；取值不在声明列表内时抛错。 */
    public static BlockState setString(BlockState state, String name, String value) {
        Property<?> property = requireProperty(state, name);
        if (!(property instanceof StringListProperty stringProperty)) {
            throw new IllegalArgumentException("方块状态属性 \"" + name + "\" 不是 enum 类型");
        }
        return state.setValue(stringProperty, value);
    }

    private static Property<?> requireProperty(BlockState state, String name) {
        Property<?> property = findProperty(state, name);
        if (property == null) {
            throw new IllegalArgumentException("方块 " + state.getBlock()
                    + " 没有声明方块状态属性 \"" + name + "\"");
        }
        return property;
    }

    private static Property<?> findProperty(BlockState state, String name) {
        if (name == null) {
            return null;
        }
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    // ---------- 枚举校验 ----------

    private static void checkEnum(String value, String... allowed) {
        for (String a : allowed) {
            if (a.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("Invalid value '" + value
                + "', expected one of: " + String.join(", ", allowed));
    }
}
