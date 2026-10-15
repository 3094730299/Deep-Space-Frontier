package com.deepspacedeployer.economy;

import com.deepspacedeployer.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 蓝图凭证的数据读写：这张凭证代表哪一份蓝图 */
public final class VoucherData {

    private static final String ROOT = "DSD_Voucher";
    private static final String BLUEPRINT = "Blueprint";

    public static boolean isVoucher(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.BLUEPRINT_VOUCHER.get());
    }

    public static String blueprint(ItemStack stack) {
        if (!isVoucher(stack)) {
            return "";
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return "";
        }
        CompoundTag tag = data.copyTag();
        return tag.contains(ROOT) ? tag.getCompound(ROOT).getString(BLUEPRINT) : "";
    }

    public static void setBlueprint(ItemStack stack, String blueprintName) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        CompoundTag root = tag.contains(ROOT) ? tag.getCompound(ROOT) : new CompoundTag();
        root.putString(BLUEPRINT, blueprintName == null ? "" : blueprintName);
        tag.put(ROOT, root);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private VoucherData() {
    }
}
