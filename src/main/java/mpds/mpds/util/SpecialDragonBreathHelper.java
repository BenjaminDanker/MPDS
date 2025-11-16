package mpds.mpds.util;

import mpds.mpds.MPDS;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Utility for identifying and purging duplicate "Special Dragon Breath" bottles so only one survives.
 */
public final class SpecialDragonBreathHelper {

    private SpecialDragonBreathHelper() {
    }

    public static int purgeExtraSpecialDragonBreath(ServerPlayerEntity player, String context) {
        if (player == null) {
            return 0;
        }

        PlayerInventory inventory = player.getInventory();
        if (inventory == null) {
            MPDS.LOGGER.info("Skipping dragon breath cleanup for {}: inventory unavailable (context={})",
                safePlayerName(player), context);
            return 0;
        }

        boolean keptOne = false;
        int removed = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!isSpecialDragonBreath(stack)) {
                continue;
            }

            if (!keptOne) {
                keptOne = true;
                continue;
            }

            removed += stack.getCount();
            inventory.setStack(slot, ItemStack.EMPTY);
        }

        if (removed > 0) {
            MPDS.LOGGER.info("Removed {} extra special dragon breath bottles from {} ({})", removed,
                safePlayerName(player), context);
        } else {
            MPDS.LOGGER.info("No extra special dragon breath bottles found for {} ({})",
                safePlayerName(player), context);
        }
        return removed;
    }

    public static boolean isSpecialDragonBreath(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.DRAGON_BREATH)) {
            return false;
        }
        Text name = stack.getName();
        return name != null && "Special Dragon Breath".equals(name.getString());
    }

    private static String safePlayerName(ServerPlayerEntity player) {
        return player == null ? "unknown" : player.getName().getString();
    }
}
