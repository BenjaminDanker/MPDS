package mpds.mpds;

import com.google.gson.JsonParser;
import mpds.mpds.mixin.HungerManagerAccessor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static mpds.mpds.MPDS.*;

public class sqlPlayer {

    private static final String SOULBOUND_KEY = "idType";
    private static final String SOULBOUND_VALUE = "Soulbound";
    private static final String SOULBOUND_VALUE_CRAFTED = "CraftedSoulbound";

    public String uuid;
    public String name;
    public int air;
    public float health;
    public float exhaustion;
    public int foodLevel;
    public float saturationLevel;
    public int foodTickTimer;
    public int experienceLevel;
    public float experienceProgress;
    public String enderChestInventory;
    public String off;
    public int selectedSlot;
    public String main;
    public String armor;
    public String effects;

    public static void sqlToPlayer(ServerPlayerEntity player, ResultSet resultSet) throws SQLException {
        if (SA) player.setAir(resultSet.getInt("Air"));

        if (SH) player.setHealth(resultSet.getFloat("Health"));

        if (SF) {
            ((HungerManagerAccessor) player.getHungerManager()).mpds$setExhaustion(resultSet.getFloat("exhaustion"));
            player.getHungerManager().setFoodLevel(resultSet.getInt("foodLevel"));
            player.getHungerManager().setSaturationLevel(resultSet.getFloat("saturationLevel"));
            ((HungerManagerAccessor) player.getHungerManager()).mpds$setFoodTickTimer(resultSet.getInt("foodTickTimer"));
        }

        if (SL) {
            player.setExperienceLevel(resultSet.getInt("experienceLevel"));
            player.experienceProgress = resultSet.getFloat("experienceProgress");
        }

        if (SEn) {
            purgeSoulboundFromInventory(player.getEnderChestInventory());
            String stored = safeString(resultSet.getString("enderChestInventory"));
            if (!stored.isEmpty()) {
                List.of(stored.split("&")).forEach(compound -> {
                    String[] compounds = compound.split("~");
                    ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(compounds[0]))
                        .resultOrPartial(LOGGER::error)
                        .orElseThrow();
                    if (!isSoulbound(parsed)) {
                        return;
                    }
                    int preferredSlot = Integer.parseInt(compounds[1]);
                    insertIntoInventoryPreserving(player, player.getEnderChestInventory(), preferredSlot, parsed);
                });
            }
        }

        if (SI) {
            purgeSoulboundFromPlayerInventory(player);

            String offStored = safeString(resultSet.getString("off"));
            if (!offStored.isEmpty()) {
                ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(offStored))
                    .resultOrPartial(LOGGER::error)
                    .orElseThrow();
                if (isSoulbound(parsed)) {
                    setOffhandPreserving(player, parsed);
                }
            }

            player.getInventory().setSelectedSlot(resultSet.getInt("selectedSlot"));
            player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(resultSet.getInt("selectedSlot")));

            String mainStored = safeString(resultSet.getString("main"));
            if (!mainStored.isEmpty()) {
                List.of(mainStored.split("&")).forEach(compound -> {
                    String[] compounds = compound.split("~");
                    ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(compounds[0]))
                        .resultOrPartial(LOGGER::error)
                        .orElseThrow();
                    if (!isSoulbound(parsed)) {
                        return;
                    }
                    int preferredSlot = Integer.parseInt(compounds[1]);
                    insertIntoInventoryPreserving(player, player.getInventory(), preferredSlot, parsed);
                });
            }

            String armorStored = safeString(resultSet.getString("armor"));
            if (!armorStored.isEmpty()) {
                List.of(armorStored.split("&")).forEach(compound -> {
                    String[] compounds = compound.split("~");
                    ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(compounds[0]))
                        .resultOrPartial(LOGGER::error)
                        .orElseThrow();
                    if (!isSoulbound(parsed)) {
                        return;
                    }
                    int idx = Integer.parseInt(compounds[1]);
                    net.minecraft.entity.EquipmentSlot slot = switch (idx) {
                        case 0 -> net.minecraft.entity.EquipmentSlot.FEET;
                        case 1 -> net.minecraft.entity.EquipmentSlot.LEGS;
                        case 2 -> net.minecraft.entity.EquipmentSlot.CHEST;
                        case 3 -> net.minecraft.entity.EquipmentSlot.HEAD;
                        default -> null;
                    };
                    if (slot != null) {
                        equipArmorPreserving(player, slot, parsed);
                    }
                });
            }
        }

        if (SEf && !"".equals(resultSet.getString("effects")))
            List.of(resultSet.getString("effects").split("&")).forEach(compound -> player.addStatusEffect(StatusEffectInstance.CODEC.parse(wrappedOps, JsonParser.parseString(compound)).resultOrPartial(LOGGER::error).orElseThrow()));
    }

    public sqlPlayer(ServerPlayerEntity player) {
        this.name = player.getName().getString();
        this.uuid = player.getUuidAsString();
        this.air = player.getAir();
        this.health = player.getHealth();
        this.exhaustion = ((HungerManagerAccessor) player.getHungerManager()).mpds$getExhaustion();
        this.foodLevel = player.getHungerManager().getFoodLevel();
        this.saturationLevel = player.getHungerManager().getSaturationLevel();
        this.foodTickTimer = ((HungerManagerAccessor) player.getHungerManager()).mpds$getFoodTickTimer();
        this.experienceLevel = player.experienceLevel;
        this.experienceProgress = player.experienceProgress;

        EnderChestInventory end = player.getEnderChestInventory();
        this.enderChestInventory = "";
        if (SEn) {
            StringBuilder endresults = new StringBuilder();
            for (int i = 0; i < end.size(); i++) {
                ItemStack st = end.getStack(i);
                if (st.isEmpty() || !isSoulbound(st)) {
                    continue;
                }
                endresults.append(ItemStack.CODEC.encodeStart(wrappedOps, st).resultOrPartial(LOGGER::error).orElseThrow())
                    .append("~").append(i).append("&");
                end.setStack(i, ItemStack.EMPTY);
            }
            this.enderChestInventory = endresults.toString();
        }

        this.off = "";
        this.selectedSlot = player.getInventory().getSelectedSlot();
        this.main = "";
        this.armor = "";

        if (SI) {
            ItemStack offhand = player.getStackInHand(Hand.OFF_HAND);
            if (!offhand.isEmpty() && isSoulbound(offhand)) {
                this.off = ItemStack.CODEC.encodeStart(wrappedOps, offhand).resultOrPartial(LOGGER::error).orElseThrow().toString();
                player.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
            }

            StringBuilder mainresults = new StringBuilder();
            for (int i = 0; i < 36; i++) {
                ItemStack st = player.getInventory().getStack(i);
                if (st.isEmpty() || !isSoulbound(st)) {
                    continue;
                }
                mainresults.append(ItemStack.CODEC.encodeStart(wrappedOps, st).resultOrPartial(LOGGER::error).orElseThrow())
                    .append("~").append(i).append("&");
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
            this.main = mainresults.toString();

            StringBuilder armorresults = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                net.minecraft.entity.EquipmentSlot slot = switch (i) {
                    case 0 -> net.minecraft.entity.EquipmentSlot.FEET;
                    case 1 -> net.minecraft.entity.EquipmentSlot.LEGS;
                    case 2 -> net.minecraft.entity.EquipmentSlot.CHEST;
                    case 3 -> net.minecraft.entity.EquipmentSlot.HEAD;
                    default -> null;
                };
                ItemStack st = slot == null ? ItemStack.EMPTY : player.getEquippedStack(slot);
                if (st.isEmpty() || !isSoulbound(st)) {
                    continue;
                }
                armorresults.append(ItemStack.CODEC.encodeStart(wrappedOps, st).resultOrPartial(LOGGER::error).orElseThrow())
                    .append("~").append(i).append("&");
                if (slot != null) {
                    player.equipStack(slot, ItemStack.EMPTY);
                }
            }
            this.armor = armorresults.toString();
        }

        StringBuilder effectresults = new StringBuilder();
        player.getStatusEffects().forEach(effect -> effectresults.append(StatusEffectInstance.CODEC.encodeStart(wrappedOps, effect).resultOrPartial(LOGGER::error).orElseThrow()).append("&"));
        this.effects = effectresults.toString();
        if (SEf) player.clearStatusEffects();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isSoulbound(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null) {
            return false;
        }

        NbtCompound nbt = custom.copyNbt();

        String idType = nbt.getString(SOULBOUND_KEY).orElse("");

        return SOULBOUND_VALUE.equals(idType) || SOULBOUND_VALUE_CRAFTED.equals(idType);
    }

    private static void purgeSoulboundFromPlayerInventory(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }

        PlayerInventoryAccessor.purgeSoulbound(player);
    }

    private static void purgeSoulboundFromInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack st = inventory.getStack(i);
            if (!st.isEmpty() && isSoulbound(st)) {
                inventory.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static void setOffhandPreserving(ServerPlayerEntity player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }

        ItemStack existing = player.getStackInHand(Hand.OFF_HAND);
        if (existing.isEmpty()) {
            player.setStackInHand(Hand.OFF_HAND, stack);
            return;
        }

        ItemStack existingCopy = existing.copy();
        player.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);

        boolean moved = player.getInventory().insertStack(existingCopy);
        if (!moved) {
            EnderChestInventory end = player.getEnderChestInventory();
            moved = end != null && end.addStack(existingCopy).isEmpty();
        }

        if (moved) {
            player.setStackInHand(Hand.OFF_HAND, stack);
            return;
        }

        // Couldn't move the existing offhand item; keep it and try to restore Soulbound into main inventory instead.
        player.setStackInHand(Hand.OFF_HAND, existing);
        boolean inserted = player.getInventory().insertStack(stack);
        if (!inserted) {
            LOGGER.warn("Could not restore Soulbound offhand item {} for {} (no space)", stack, player.getName().getString());
        }
    }

    private static void equipArmorPreserving(ServerPlayerEntity player, net.minecraft.entity.EquipmentSlot slot, ItemStack stack) {
        if (player == null || slot == null || stack == null || stack.isEmpty()) {
            return;
        }

        ItemStack existing = player.getEquippedStack(slot);
        if (existing.isEmpty()) {
            player.equipStack(slot, stack);
            return;
        }

        ItemStack existingCopy = existing.copy();
        player.equipStack(slot, ItemStack.EMPTY);

        boolean moved = player.getInventory().insertStack(existingCopy);
        if (!moved) {
            EnderChestInventory end = player.getEnderChestInventory();
            moved = end != null && end.addStack(existingCopy).isEmpty();
        }

        if (moved) {
            player.equipStack(slot, stack);
            return;
        }

        // Couldn't move the existing armor; keep it and place Soulbound into main inventory instead.
        player.equipStack(slot, existing);
        boolean inserted = player.getInventory().insertStack(stack);
        if (!inserted) {
            LOGGER.warn("Could not restore Soulbound armor item {} for {} (no space)", stack, player.getName().getString());
        }
    }

    private static void insertIntoInventoryPreserving(ServerPlayerEntity player, Inventory inventory, int preferredSlot, ItemStack stack) {
        if (player == null || inventory == null || stack == null || stack.isEmpty()) {
            return;
        }

        if (preferredSlot >= 0 && preferredSlot < inventory.size() && inventory.getStack(preferredSlot).isEmpty()) {
            inventory.setStack(preferredSlot, stack);
            return;
        }

        int scanLimit = inventory.size();
        if (inventory == player.getInventory()) {
            scanLimit = Math.min(36, inventory.size());
        }

        for (int i = 0; i < scanLimit; i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack);
                return;
            }
        }

        if (inventory != player.getInventory()) {
            boolean moved = player.getInventory().insertStack(stack);
            if (moved) {
                return;
            }
        }

        LOGGER.warn("No space to restore Soulbound item {} for {}", stack, player.getName().getString());
    }

    private static final class PlayerInventoryAccessor {
        private PlayerInventoryAccessor() {
        }

        private static void purgeSoulbound(ServerPlayerEntity player) {
            if (player == null) {
                return;
            }

            // main inventory
            for (int i = 0; i < 36 && i < player.getInventory().size(); i++) {
                ItemStack st = player.getInventory().getStack(i);
                if (!st.isEmpty() && isSoulbound(st)) {
                    player.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }

            // offhand
            ItemStack off = player.getStackInHand(Hand.OFF_HAND);
            if (!off.isEmpty() && isSoulbound(off)) {
                player.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
            }

            // armor
            for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[] {
                net.minecraft.entity.EquipmentSlot.FEET,
                net.minecraft.entity.EquipmentSlot.LEGS,
                net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.HEAD
            }) {
                ItemStack st = player.getEquippedStack(slot);
                if (!st.isEmpty() && isSoulbound(st)) {
                    player.equipStack(slot, ItemStack.EMPTY);
                }
            }
        }
    }
}
