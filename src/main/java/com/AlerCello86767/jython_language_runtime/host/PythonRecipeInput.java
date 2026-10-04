package com.AlerCello86767.jython_language_runtime.host;

import java.util.Collections;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

/**
 * 配方输入宿主：把「一组物品栈」适配成原版 {@link RecipeInput}。
 *
 * <p>26.1 的配方匹配接口从旧的 {@code Container}/{@code Inventory} 改成了极简的
 * {@link RecipeInput}（只需 {@code getItem(int)} + {@code size()}），所以这里只做箱式包装。
 *
 * <p>越界或 {@code null} 一律回 {@link ItemStack#EMPTY}，让 {@link PythonRecipe#matches} 走
 * 「空槽不匹配」这条正常分支，而不是抛异常打断机器 tick。
 */
public final class PythonRecipeInput implements RecipeInput {
    private final List<ItemStack> stacks;

    public PythonRecipeInput(List<ItemStack> stacks) {
        this.stacks = stacks;
    }

    @Override
    public ItemStack getItem(int index) {
        if (index < 0 || index >= stacks.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = stacks.get(index);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public int size() {
        return stacks.size();
    }

    /** 原始物品栈列表（只读），便于调用方复用。 */
    public List<ItemStack> stacks() {
        return Collections.unmodifiableList(stacks);
    }
}
