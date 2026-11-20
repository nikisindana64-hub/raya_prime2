package com.mamarala.rayaprime.item;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Свернуть Райю в Ядро в инвентаре и выпустить обратно. */
public final class RayaStorage {

    private RayaStorage() {}

    /** Найти Ядро Раи в инвентаре игрока (включая броню и вторую руку), иначе пустой стек. */
    public static ItemStack findCore(ServerPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(RayaPrimeMod.RAYA_CORE.get())) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean hasCore(ServerPlayer player) {
        return !findCore(player).isEmpty();
    }

    /** Райя «прячется в инвентарь»: состояние сохраняется в предмет, тело исчезает. */
    public static boolean store(ServerPlayer player, RayaPrimeEntity raya) {
        if (hasCore(player)) {
            player.sendSystemMessage(Component.literal("§b[Райя-Прайм]§r Моё Ядро уже у тебя в инвентаре."));
            return false;
        }
        CompoundTag rayaTag = new CompoundTag();
        raya.saveWithoutId(rayaTag);
        rayaTag.remove("UUID");
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("raya", rayaTag);

        ItemStack core = new ItemStack(RayaPrimeMod.RAYA_CORE.get());
        core.set(DataComponents.CUSTOM_DATA, CustomData.of(wrapper));

        if (!player.getInventory().add(core)) {
            player.sendSystemMessage(Component.literal("§c[Райя-Прайм]§r В инвентаре нет места для моего Ядра — освободи слот."));
            return false;
        }
        raya.clearTasks();
        raya.discard();
        return true;
    }

    /** Перенести Райю за игроком в другое измерение (сохраняя инвентарь, заметки, места). Возвращает новое тело. */
    public static RayaPrimeEntity transfer(ServerPlayer player, RayaPrimeEntity old) {
        CompoundTag tag = new CompoundTag();
        old.saveWithoutId(tag);
        tag.remove("UUID");
        old.clearTasks();
        old.discard();
        ServerLevel level = player.serverLevel();
        RayaPrimeEntity raya = RayaPrimeMod.RAYA_PRIME.get().create(level);
        if (raya == null) return null;
        raya.load(tag);
        raya.setOwnerUUID(player.getUUID());
        raya.moveTo(player.getX() + 1.2, player.getY() + 1.4, player.getZ() + 1.2, player.getYRot(), 0.0F);
        raya.setDeltaMovement(0, 0, 0);
        raya.setActiveMode("FOLLOW");
        level.addFreshEntity(raya);
        return raya;
    }

    /** Выпустить Райю из Ядра рядом с игроком. Стек уменьшается на 1. */
    public static boolean release(ServerPlayer player, ItemStack core) {
        if (core.isEmpty()) return false;
        ServerLevel level = player.serverLevel();
        RayaPrimeEntity raya = RayaPrimeMod.RAYA_PRIME.get().create(level);
        if (raya == null) return false;

        CustomData data = core.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag().getCompound("raya");
            if (!tag.isEmpty()) {
                tag.remove("UUID"); // новое тело — новый UUID
                raya.load(tag);
            }
        }
        raya.setOwnerUUID(player.getUUID());
        raya.moveTo(player.getX() + 1.2, player.getY() + 1.4, player.getZ() + 1.2, player.getYRot(), 0.0F);
        raya.setDeltaMovement(0, 0, 0);
        raya.setActiveMode("FOLLOW");
        if (!raya.hasCustomName()) {
            raya.setCustomName(Component.literal("Райя-Прайм"));
        }
        level.addFreshEntity(raya);
        core.shrink(1);
        return true;
    }
}
