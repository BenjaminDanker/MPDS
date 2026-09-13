package mpds.mpds;

import com.google.gson.JsonParser;
import mpds.mpds.mixin.HungerManagerAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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

    public record SqlRow(
        int air,
        float health,
        float exhaustion,
        int foodLevel,
        float saturationLevel,
        int foodTickTimer,
        int experienceLevel,
        float experienceProgress,
        String enderChestInventory,
        String off,
        int selectedSlot,
        String main,
        String armor,
        String effects
    ) {
    }

    public static SqlRow readRow(ResultSet resultSet) throws SQLException {
        return new SqlRow(
            resultSet.getInt("Air"),
            resultSet.getFloat("Health"),
            resultSet.getFloat("exhaustion"),
            resultSet.getInt("foodLevel"),
            resultSet.getFloat("saturationLevel"),
            resultSet.getInt("foodTickTimer"),
            resultSet.getInt("experienceLevel"),
            resultSet.getFloat("experienceProgress"),
            resultSet.getString("enderChestInventory"),
            resultSet.getString("off"),
            resultSet.getInt("selectedSlot"),
            resultSet.getString("main"),
            resultSet.getString("armor"),
            resultSet.getString("effects")
        );
    }

    public static void sqlToPlayer(ServerPlayer player, ResultSet resultSet) throws SQLException {
        sqlToPlayer(player, readRow(resultSet));
    }

    public static void sqlToPlayer(ServerPlayer player, SqlRow row) {
        if (SA) player.setAirSupply(row.air());

        if (SH) player.setHealth(row.health());

        if (SF) {
            ((HungerManagerAccessor) player.getFoodData()).mpds$setExhaustion(row.exhaustion());
            player.getFoodData().setFoodLevel(row.foodLevel());
            player.getFoodData().setSaturation(row.saturationLevel());
            ((HungerManagerAccessor) player.getFoodData()).mpds$setFoodTickTimer(row.foodTickTimer());
        }

        if (SL) {
            player.setExperienceLevels(row.experienceLevel());
            player.experienceProgress = row.experienceProgress();
        }

        if (SEn) {
            purgeSoulboundFromInventory(player.getEnderChestInventory());
            String stored = safeString(row.enderChestInventory());
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

            String offStored = safeString(row.off());
            if (!offStored.isEmpty()) {
                ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(offStored))
                    .resultOrPartial(LOGGER::error)
                    .orElseThrow();
                if (isSoulbound(parsed)) {
                    setOffhandPreserving(player, parsed);
                }
            }

            player.getInventory().setSelectedSlot(row.selectedSlot());
            player.connection.send(new ClientboundSetHeldSlotPacket(row.selectedSlot()));

            String mainStored = safeString(row.main());
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

            String armorStored = safeString(row.armor());
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
                    net.minecraft.world.entity.EquipmentSlot slot = switch (idx) {
                        case 0 -> net.minecraft.world.entity.EquipmentSlot.FEET;
                        case 1 -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                        case 2 -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                        case 3 -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                        default -> null;
                    };
                    if (slot != null) {
                        equipArmorPreserving(player, slot, parsed);
                    }
                });
            }
        }

        String effectsStored = safeString(row.effects());
        if (SEf && !effectsStored.isEmpty()) {
            List.of(effectsStored.split("&")).forEach(compound -> player.addEffect(MobEffectInstance.CODEC.parse(wrappedOps, JsonParser.parseString(compound)).resultOrPartial(LOGGER::error).orElseThrow()));
        }
    }

    public sqlPlayer(ServerPlayer player) {
        this.name = player.getName().getString();
        this.uuid = player.getStringUUID();
        this.air = player.getAirSupply();
        this.health = player.getHealth();
        this.exhaustion = ((HungerManagerAccessor) player.getFoodData()).mpds$getExhaustion();
        this.foodLevel = player.getFoodData().getFoodLevel();
        this.saturationLevel = player.getFoodData().getSaturationLevel();
        this.foodTickTimer = ((HungerManagerAccessor) player.getFoodData()).mpds$getFoodTickTimer();
        this.experienceLevel = player.experienceLevel;
        this.experienceProgress = player.experienceProgress;

        PlayerEnderChestContainer end = player.getEnderChestInventory();
        this.enderChestInventory = "";
        if (SEn) {
            StringBuilder endresults = new StringBuilder();
            for (int i = 0; i < end.getContainerSize(); i++) {
                ItemStack st = end.getItem(i);
                if (st.isEmpty() || !isSoulbound(st)) {
                    continue;
                }
                endresults.append(ItemStack.CODEC.encodeStart(wrappedOps, st).resultOrPartial(LOGGER::error).orElseThrow())
                    .append("~").append(i).append("&");
                end.setItem(i, ItemStack.EMPTY);
            }
            this.enderChestInventory = endresults.toString();
        }

        this.off = "";
        this.selectedSlot = player.getInventory().getSelectedSlot();
        this.main = "";
        this.armor = "";

        if (SI) {
            ItemStack offhand = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!offhand.isEmpty() && isSoulbound(offhand)) {
                this.off = ItemStack.CODEC.encodeStart(wrappedOps, offhand).resultOrPartial(LOGGER::error).orElseThrow().toString();
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }

            StringBuilder mainresults = new StringBuilder();
            for (int i = 0; i < 36; i++) {
                ItemStack st = player.getInventory().getItem(i);
                if (st.isEmpty() || !isSoulbound(st)) {
                    continue;
                }
                mainresults.append(ItemStack.CODEC.encodeStart(wrappedOps, st).resultOrPartial(LOGGER::error).orElseThrow())
                    .append("~").append(i).append("&");
                player.getInventory().setItem(i, ItemStack.EMPTY);
            }
            this.main = mainresults.toString();

            StringBuilder armorresults = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                net.minecraft.world.entity.EquipmentSlot slot = switch (i) {
                    case 0 -> net.minecraft.world.entity.EquipmentSlot.FEET;
                    case 1 -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                    case 2 -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                    case 3 -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                    default -> null;
                };
                ItemStack st = slot == null ? ItemStack.EMPTY : player.getItemBySlot(slot);
                if (st.isEmpty() || !isSoulbound(st)) {
                    continue;
                }
                armorresults.append(ItemStack.CODEC.encodeStart(wrappedOps, st).resultOrPartial(LOGGER::error).orElseThrow())
                    .append("~").append(i).append("&");
                if (slot != null) {
                    player.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            this.armor = armorresults.toString();
        }

        StringBuilder effectresults = new StringBuilder();
        player.getActiveEffects().forEach(effect -> effectresults.append(MobEffectInstance.CODEC.encodeStart(wrappedOps, effect).resultOrPartial(LOGGER::error).orElseThrow()).append("&"));
        this.effects = effectresults.toString();
        if (SEf) player.removeAllEffects();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isSoulbound(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return false;
        }

        CompoundTag nbt = custom.copyTag();

        String idType = nbt.getString(SOULBOUND_KEY).orElse("");

        return SOULBOUND_VALUE.equals(idType) || SOULBOUND_VALUE_CRAFTED.equals(idType);
    }

    private static void purgeSoulboundFromPlayerInventory(ServerPlayer player) {
        if (player == null) {
            return;
        }

        PlayerInventoryAccessor.purgeSoulbound(player);
    }

    private static void purgeSoulboundFromInventory(Container inventory) {
        if (inventory == null) {
            return;
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack st = inventory.getItem(i);
            if (!st.isEmpty() && isSoulbound(st)) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private static void setOffhandPreserving(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }

        ItemStack existing = player.getItemInHand(InteractionHand.OFF_HAND);
        if (existing.isEmpty()) {
            player.setItemInHand(InteractionHand.OFF_HAND, stack);
            return;
        }

        ItemStack existingCopy = existing.copy();
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

        boolean moved = player.getInventory().add(existingCopy);
        if (!moved) {
            PlayerEnderChestContainer end = player.getEnderChestInventory();
            moved = end != null && end.addItem(existingCopy).isEmpty();
        }

        if (moved) {
            player.setItemInHand(InteractionHand.OFF_HAND, stack);
            return;
        }

        // Couldn't move the existing offhand item; keep it and try to restore Soulbound into main inventory instead.
        player.setItemInHand(InteractionHand.OFF_HAND, existing);
        boolean inserted = player.getInventory().add(stack);
        if (!inserted) {
            LOGGER.warn("Could not restore Soulbound offhand item {} for {} (no space)", stack, player.getName().getString());
        }
    }

    private static void equipArmorPreserving(ServerPlayer player, net.minecraft.world.entity.EquipmentSlot slot, ItemStack stack) {
        if (player == null || slot == null || stack == null || stack.isEmpty()) {
            return;
        }

        ItemStack existing = player.getItemBySlot(slot);
        if (existing.isEmpty()) {
            player.setItemSlot(slot, stack);
            return;
        }

        ItemStack existingCopy = existing.copy();
        player.setItemSlot(slot, ItemStack.EMPTY);

        boolean moved = player.getInventory().add(existingCopy);
        if (!moved) {
            PlayerEnderChestContainer end = player.getEnderChestInventory();
            moved = end != null && end.addItem(existingCopy).isEmpty();
        }

        if (moved) {
            player.setItemSlot(slot, stack);
            return;
        }

        // Couldn't move the existing armor; keep it and place Soulbound into main inventory instead.
        player.setItemSlot(slot, existing);
        boolean inserted = player.getInventory().add(stack);
        if (!inserted) {
            LOGGER.warn("Could not restore Soulbound armor item {} for {} (no space)", stack, player.getName().getString());
        }
    }

    private static void insertIntoInventoryPreserving(ServerPlayer player, Container inventory, int preferredSlot, ItemStack stack) {
        if (player == null || inventory == null || stack == null || stack.isEmpty()) {
            return;
        }

        if (preferredSlot >= 0 && preferredSlot < inventory.getContainerSize() && inventory.getItem(preferredSlot).isEmpty()) {
            inventory.setItem(preferredSlot, stack);
            return;
        }

        int scanLimit = inventory.getContainerSize();
        if (inventory == player.getInventory()) {
            scanLimit = Math.min(36, inventory.getContainerSize());
        }

        for (int i = 0; i < scanLimit; i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack);
                return;
            }
        }

        if (inventory != player.getInventory()) {
            boolean moved = player.getInventory().add(stack);
            if (moved) {
                return;
            }
        }

        LOGGER.warn("No space to restore Soulbound item {} for {}", stack, player.getName().getString());
    }

    private static final class PlayerInventoryAccessor {
        private PlayerInventoryAccessor() {
        }

        private static void purgeSoulbound(ServerPlayer player) {
            if (player == null) {
                return;
            }

            // main inventory
            for (int i = 0; i < 36 && i < player.getInventory().getContainerSize(); i++) {
                ItemStack st = player.getInventory().getItem(i);
                if (!st.isEmpty() && isSoulbound(st)) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }

            // offhand
            ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!off.isEmpty() && isSoulbound(off)) {
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }

            // armor
            for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[] {
                net.minecraft.world.entity.EquipmentSlot.FEET,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.HEAD
            }) {
                ItemStack st = player.getItemBySlot(slot);
                if (!st.isEmpty() && isSoulbound(st)) {
                    player.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
        }
    }
}
