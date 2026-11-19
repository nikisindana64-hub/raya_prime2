package com.mamarala.rayaprime.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Ядро Раи — Райя в «свёрнутом» виде в инвентаре. ПКМ — выпустить её в мир.
 * Внутри хранится всё её состояние: инвентарь, здоровье, имя, режим.
 */
public class RayaCoreItem extends Item {

    public RayaCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            RayaStorage.release(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.rayaprime.raya_core.tooltip1"));
        tooltip.add(Component.translatable("item.rayaprime.raya_core.tooltip2"));
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag raya = data.copyTag().getCompound("raya");
            if (raya.contains("Health")) {
                tooltip.add(Component.literal("HP: " + (int) raya.getFloat("Health")));
            }
        }
    }
}
