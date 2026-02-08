package mpds.mpds;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.JsonOps;
import com.mysql.cj.jdbc.exceptions.CommunicationsException;
import mpds.mpds.events.Disconnect;
import mpds.mpds.events.Join;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import mpds.mpds.net.ReturnRemoveRequestPayload;
import mpds.mpds.net.ReturnRemoveResponsePayload;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class MPDS implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mpds");

    public static String ServerName;

    public static final List<UUID> broken = new ArrayList<>();

    static HashMap<String, String> config;

    public static boolean AJM;

    public static boolean ASM;

    public static boolean AEM;

    public static boolean SA;

    public static boolean SH;

    public static boolean SF;

    public static boolean SL;

    public static boolean SEn;

    public static boolean SI;

    public static boolean SEf;

    public static Gson gson = new Gson();

    public static RegistryOps<JsonElement> wrappedOps;

    private static final String DB_COL_MAIN = "main";
    private static final String DB_COL_OFF = "off";
    private static final String DB_COL_ARMOR = "armor";
    private static final String DB_COL_ENDER = "enderChestInventory";


    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("MPDS");
        Path configjson = configDir.resolve("Config.json");

        if (Files.notExists(configDir)) {
            try {
                Files.createDirectory(configDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }


        if (Files.notExists(configjson)) {
            try {
                Files.copy(Objects.requireNonNull(MPDS.class.getResourceAsStream("/mpdsconfig.json")), configjson);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("made MPDS config file.\nPlease set!");
            System.exit(0);
        }

        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(String.valueOf(configjson)), StandardCharsets.UTF_8))) {
            config = gson.fromJson(reader, new TypeToken<HashMap<String, String>>() {
            }.getType());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        AJM = Boolean.parseBoolean(config.get("AJM"));
        ASM = Boolean.parseBoolean(config.get("ASM"));
        AEM = Boolean.parseBoolean(config.get("AEM"));

        sql.TABLE_NAME = config.get("TABLE_NAME");

        ServerName = config.get("ServerName");

        SA = Boolean.parseBoolean(config.get("SA"));
        SH = Boolean.parseBoolean(config.get("SH"));
        SF = Boolean.parseBoolean(config.get("SF"));
        SL = Boolean.parseBoolean(config.get("SL"));
        SEn = Boolean.parseBoolean(config.get("SEn"));
        SI = Boolean.parseBoolean(config.get("SI"));
        SEf = Boolean.parseBoolean(config.get("SEf"));

        try {
            sql.init();
        } catch (SQLException e) {
            LOGGER.error("FAIL TO CONNECT MYSQL");
            LOGGER.error("DID YOU CHANGE MPDS CONFIG?");
            e.printStackTrace();
        }

        ServerPlayConnectionEvents.JOIN.register(Join::onjoin);
        ServerPlayConnectionEvents.DISCONNECT.register(Disconnect::ondisconnect);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("updateskip").then(argument("player", StringArgumentType.word()).then(argument("skip", BoolArgumentType.bool())
                        .executes(ctx -> {
                            while (true) {
                                try {
                                    sql.updateSkip(StringArgumentType.getString(ctx, "player"), String.valueOf(BoolArgumentType.getBool(ctx, "skip")));
                                    ctx.getSource().getServer().getPlayerManager().broadcast(Text.translatable("set " + StringArgumentType.getString(ctx, "player") + "'s data " + BoolArgumentType.getBool(ctx, "skip")).formatted(Formatting.YELLOW), false);

                                    return 1;
                                } catch (CommunicationsException ignored) {
                                } catch (Exception e) {
                                    ctx.getSource().getServer().getPlayerManager().broadcast(Text.translatable("THERE WERE SOME ERRORS : \n" + e.getMessage()).formatted(Formatting.RED), false);
                                    LOGGER.error("THERE WERE SOME ERRORS :");
                                    e.printStackTrace();

                                    return 1;
                                }
                            }
                        })
                ))));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("showskip")
                        .executes(ctx -> {
                            ResultSet skiprs;
                            StringBuilder skipp = new StringBuilder();

                            while (true) {
                                try {
                                    skiprs = sql.showSkip();

                                    while (skiprs.next()) {
                                        if ("false".equals(skiprs.getString("skip"))) continue;
                                        skipp.append("・").append(skiprs.getString("Name")).append("\n");
                                    }

                                    ServerPlayerEntity player;
                                    if ((player = ctx.getSource().getPlayer()) != null) {
                                        player.sendMessage(Text.of(skipp.toString()));
                                    } else {
                                        ctx.getSource().getServer().sendMessage(Text.of(skipp.toString()));
                                    }

                                    return 1;
                                } catch (CommunicationsException ignored) {
                                } catch (Exception e) {
                                    ctx.getSource().getServer().getPlayerManager().broadcast(Text.translatable("THERE WERE SOME ERRORS : \n" + e.getMessage()).formatted(Formatting.RED), false);
                                    LOGGER.error("THERE WERE SOME ERRORS :");
                                    e.printStackTrace();

                                    return 1;
                                }
                            }
                        })
                ));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsdefeated")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("player", EntityArgumentType.player())
                        .then(argument("flag", StringArgumentType.word())
                            .then(argument("value", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                    String flag = StringArgumentType.getString(ctx, "flag");
                                    boolean value = BoolArgumentType.getBool(ctx, "value");

                                    String name = target.getName().getString();
                                    String uuid = target.getUuidAsString();

                                    try {
                                        switch (flag.toLowerCase(Locale.ROOT)) {
                                            case "skyisland", "skyislanddefeated" -> sql.setSkyIslandDefeated(name, uuid, value);
                                            case "desert", "desertdefeated" -> sql.setDesertDefeated(name, uuid, value);
                                            case "ocean", "oceandefeated" -> sql.setOceanDefeated(name, uuid, value);
                                            case "cave", "cavedefeated" -> sql.setCaveDefeated(name, uuid, value);
                                            default -> {
                                                ctx.getSource().sendMessage(Text.literal(
                                                    "Unknown flag. Use: SkyIslandDefeated, DesertDefeated, OceanDefeated, CaveDefeated"));
                                                return 0;
                                            }
                                        }

                                        ctx.getSource().sendMessage(Text.literal(
                                            "Set " + name + " " + flag + " = " + value));
                                        return 1;
                                    } catch (Exception e) {
                                        ctx.getSource().sendMessage(Text.literal("Error updating flag: " + e.getMessage()).formatted(Formatting.RED));
                                        LOGGER.error("Error updating defeated flag {} for {}", flag, name, e);
                                        return 0;
                                    }
                                }))))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdssoulboundmax")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("player", EntityArgumentType.player())
                        .then(argument("delta", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                int delta = IntegerArgumentType.getInteger(ctx, "delta");

                                String name = target.getName().getString();
                                String uuid = target.getUuidAsString();

                                try {
                                    sql.adjustSoulboundMax(name, uuid, delta);
                                    ctx.getSource().sendMessage(Text.literal(
                                        "Adjusted " + name + " CraftedSoulboundMax by " + delta));
                                    return 1;
                                } catch (Exception e) {
                                    ctx.getSource().sendMessage(Text.literal("Error updating CraftedSoulboundMax: " + e.getMessage()).formatted(Formatting.RED));
                                    LOGGER.error("Error adjusting CraftedSoulboundMax for {}", name, e);
                                    return 0;
                                }
                            })))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsstage1")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("player", EntityArgumentType.player())
                        .then(argument("value", BoolArgumentType.bool())
                            .executes(ctx -> {
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                boolean value = BoolArgumentType.getBool(ctx, "value");

                                String name = target.getName().getString();
                                String uuid = target.getUuidAsString();

                                try {
                                    sql.setStage1Cleared(name, uuid, value);
                                    ctx.getSource().sendMessage(Text.literal("Set " + name + " Stage1Cleared = " + value));
                                    return 1;
                                } catch (Exception e) {
                                    ctx.getSource().sendMessage(Text.literal("Error updating Stage1Cleared: " + e.getMessage()).formatted(Formatting.RED));
                                    LOGGER.error("Error updating Stage1Cleared for {}", name, e);
                                    return 0;
                                }
                            })))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsremovecustomid")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(argument("player", EntityArgumentType.player())
                        .then(argument("key", StringArgumentType.word())
                            .then(argument("value", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                    String key = StringArgumentType.getString(ctx, "key");
                                    String value = StringArgumentType.getString(ctx, "value");

                                    if (wrappedOps == null) {
                                        ctx.getSource().sendMessage(Text.literal("MPDS registry ops not ready yet.").formatted(Formatting.RED));
                                        return 0;
                                    }

                                    String name = target.getName().getString();
                                    String uuid = target.getUuidAsString();

                                    try {
                                        int removed = removeCustomDataFromDbInventories(uuid, key, value);
                                        ctx.getSource().sendMessage(Text.literal("Removed " + removed + " item(s) from " + name + " DB soulbound inventories for " + key + "=" + value));
                                        return 1;
                                    } catch (Exception e) {
                                        ctx.getSource().sendMessage(Text.literal("Error removing items from DB: " + e.getMessage()).formatted(Formatting.RED));
                                        LOGGER.error("Error removing custom data items from DB for {} {}={}", name, key, value, e);
                                        return 0;
                                    }
                                }))))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsremovecustomidself")
                    .then(argument("key", StringArgumentType.word())
                        .then(argument("value", StringArgumentType.word())
                            .executes(ctx -> {
                                if (wrappedOps == null) {
                                    ctx.getSource().sendMessage(Text.literal("MPDS registry ops not ready yet.").formatted(Formatting.RED));
                                    return 0;
                                }

                                ServerPlayerEntity self = ctx.getSource().getPlayer();
                                if (self == null) {
                                    ctx.getSource().sendMessage(Text.literal("Players only.").formatted(Formatting.RED));
                                    return 0;
                                }

                                String key = StringArgumentType.getString(ctx, "key");
                                String value = StringArgumentType.getString(ctx, "value");

                                try {
                                    int removed = removeCustomDataFromDbInventories(self.getUuidAsString(), key, value);
                                    self.sendMessage(Text.literal("Removed " + removed + " item(s) from your DB soulbound inventories for " + key + "=" + value)
                                            .formatted(Formatting.YELLOW));
                                    return 1;
                                } catch (Exception e) {
                                    self.sendMessage(Text.literal("Error removing items from DB: " + e.getMessage()).formatted(Formatting.RED));
                                    LOGGER.error("Error removing custom data items from DB for {} {}={}", self.getName().getString(), key, value, e);
                                    return 0;
                                }
                            })))
            )
        );

        ServerLifecycleEvents.SERVER_STARTING.register(server -> wrappedOps = server.getRegistryManager().getOps(JsonOps.INSTANCE));

        PayloadTypeRegistry.playC2S().register(ReturnRemoveRequestPayload.PACKET_ID, ReturnRemoveRequestPayload.codec);
        PayloadTypeRegistry.playS2C().register(ReturnRemoveResponsePayload.PACKET_ID, ReturnRemoveResponsePayload.codec);

        ServerPlayNetworking.registerGlobalReceiver(ReturnRemoveRequestPayload.PACKET_ID, (payload, context) -> {
            context.server().execute(() -> {
                UUID requestId = payload.requestId();
                try {
                    int removedInv = 0;
                    int removedDb = 0;
                    int remainingInv = 0;
                    int remainingDb = 0;

                    for (ReturnRemoveRequestPayload.Entry e : payload.entries()) {
                        String key = e.key();
                        String value = e.value();

                        removedInv += removeCustomDataFromLiveInventories(context.player(), key, value);
                        removedDb += removeCustomDataFromDbInventories(context.player().getUuidAsString(), key, value);

                        remainingInv += countCustomDataInLiveInventories(context.player(), key, value);
                        remainingDb += countCustomDataInDbInventories(context.player().getUuidAsString(), key, value);
                    }

                    ServerPlayNetworking.send(context.player(), new ReturnRemoveResponsePayload(
                            requestId,
                            true,
                            removedInv,
                            removedDb,
                            remainingInv,
                            remainingDb,
                            ""
                    ));
                } catch (Exception ex) {
                    LOGGER.error("MPDS return_remove handler failed", ex);
                    ServerPlayNetworking.send(context.player(), new ReturnRemoveResponsePayload(
                            requestId,
                            false,
                            0,
                            0,
                            0,
                            0,
                            ex.toString()
                    ));
                }
            });
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                sql.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        LOGGER.info("MPDS loaded");
    }

    private static int removeCustomDataFromLiveInventories(ServerPlayerEntity player, String key, String value) {
        if (player == null || key == null || value == null || key.isBlank() || value.isBlank()) {
            return 0;
        }

        int removed = 0;

        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                inv.setStack(i, ItemStack.EMPTY);
                removed++;
            }
        }

        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.size(); i++) {
            ItemStack stack = ender.getStack(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                ender.setStack(i, ItemStack.EMPTY);
                removed++;
            }
        }

        if (removed > 0) {
            inv.markDirty();
            ender.markDirty();
            player.currentScreenHandler.sendContentUpdates();
        }

        return removed;
    }

    private static int countCustomDataInLiveInventories(ServerPlayerEntity player, String key, String value) {
        if (player == null || key == null || value == null || key.isBlank() || value.isBlank()) {
            return 0;
        }

        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                count++;
            }
        }

        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.size(); i++) {
            ItemStack stack = ender.getStack(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                count++;
            }
        }

        return count;
    }

    private static int countCustomDataInDbInventories(String uuid, String key, String value) throws SQLException {
        int count = 0;

        ResultSet rs = sql.join(uuid);
        if (!rs.next()) {
            return 0;
        }

        String mainStored = safeString(rs.getString(DB_COL_MAIN));
        String offStored = safeString(rs.getString(DB_COL_OFF));
        String armorStored = safeString(rs.getString(DB_COL_ARMOR));
        String enderStored = safeString(rs.getString(DB_COL_ENDER));

        count += countSlotEncodedList(mainStored, key, value);
        count += countSlotEncodedList(armorStored, key, value);
        count += countSlotEncodedList(enderStored, key, value);
        count += countSingleStack(offStored, key, value);

        return count;
    }

    private static int countSingleStack(String json, String key, String value) {
        if (json == null || json.isEmpty()) {
            return 0;
        }
        try {
            ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(json))
                .resultOrPartial(LOGGER::error)
                .orElse(ItemStack.EMPTY);
            if (!parsed.isEmpty() && matchesCustomData(parsed, key, value)) {
                return 1;
            }
            return 0;
        } catch (Exception e) {
            LOGGER.error("Failed parsing ItemStack JSON while counting single stack", e);
            return 0;
        }
    }

    private static int countSlotEncodedList(String stored, String key, String value) {
        if (stored == null || stored.isEmpty()) {
            return 0;
        }

        int count = 0;
        String[] entries = stored.split("&");
        for (String entry : entries) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }

            int tilde = entry.lastIndexOf('~');
            if (tilde <= 0 || tilde >= entry.length() - 1) {
                continue;
            }

            String json = entry.substring(0, tilde);
            try {
                ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(json))
                    .resultOrPartial(LOGGER::error)
                    .orElse(ItemStack.EMPTY);
                if (!parsed.isEmpty() && matchesCustomData(parsed, key, value)) {
                    count++;
                }
            } catch (Exception e) {
                LOGGER.error("Failed parsing ItemStack JSON while counting list entry", e);
            }
        }
        return count;
    }

    private static int removeCustomDataFromDbInventories(String uuid, String key, String value) throws SQLException {
        int removed = 0;

        ResultSet rs = sql.join(uuid);
        if (!rs.next()) {
            return 0;
        }

        String mainStored = safeString(rs.getString(DB_COL_MAIN));
        String offStored = safeString(rs.getString(DB_COL_OFF));
        String armorStored = safeString(rs.getString(DB_COL_ARMOR));
        String enderStored = safeString(rs.getString(DB_COL_ENDER));

        FilterResult main = filterSlotEncodedList(mainStored, key, value);
        FilterResult armor = filterSlotEncodedList(armorStored, key, value);
        FilterResult ender = filterSlotEncodedList(enderStored, key, value);
        FilterResult off = filterSingleStack(offStored, key, value);

        removed += main.removedCount;
        removed += armor.removedCount;
        removed += ender.removedCount;
        removed += off.removedCount;

        if (removed <= 0) {
            return 0;
        }

        java.sql.PreparedStatement update = sql.connection.prepareStatement(
            "UPDATE " + sql.TABLE_NAME + " SET main=?, off=?, armor=?, enderChestInventory=? WHERE uuid=?");
        update.setString(1, main.newValue);
        update.setString(2, off.newValue);
        update.setString(3, armor.newValue);
        update.setString(4, ender.newValue);
        update.setString(5, uuid);
        update.executeUpdate();

        return removed;
    }

    private static FilterResult filterSingleStack(String json, String key, String value) {
        if (json == null || json.isEmpty()) {
            return new FilterResult("", 0);
        }
        try {
            ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(json))
                .resultOrPartial(LOGGER::error)
                .orElse(ItemStack.EMPTY);
            if (!parsed.isEmpty() && matchesCustomData(parsed, key, value)) {
                return new FilterResult("", 1);
            }
            return new FilterResult(json, 0);
        } catch (Exception e) {
            LOGGER.error("Failed parsing ItemStack JSON while filtering single stack", e);
            return new FilterResult(json, 0);
        }
    }

    private static FilterResult filterSlotEncodedList(String stored, String key, String value) {
        if (stored == null || stored.isEmpty()) {
            return new FilterResult("", 0);
        }

        StringBuilder kept = new StringBuilder();
        int removed = 0;

        String[] entries = stored.split("&");
        for (String entry : entries) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }

            int tilde = entry.lastIndexOf('~');
            if (tilde <= 0 || tilde >= entry.length() - 1) {
                // Unexpected format; keep it rather than risking data loss.
                kept.append(entry).append('&');
                continue;
            }

            String json = entry.substring(0, tilde);
            String slot = entry.substring(tilde + 1);

            try {
                ItemStack parsed = ItemStack.CODEC.parse(wrappedOps, JsonParser.parseString(json))
                    .resultOrPartial(LOGGER::error)
                    .orElse(ItemStack.EMPTY);
                if (!parsed.isEmpty() && matchesCustomData(parsed, key, value)) {
                    removed++;
                    continue;
                }
            } catch (Exception e) {
                LOGGER.error("Failed parsing ItemStack JSON while filtering list entry", e);
                // Keep unparseable entries.
            }

            kept.append(json).append('~').append(slot).append('&');
        }

        return new FilterResult(kept.toString(), removed);
    }

    private static boolean matchesCustomData(ItemStack stack, String key, String value) {
        if (stack == null || stack.isEmpty() || key == null || value == null) {
            return false;
        }
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null) {
            return false;
        }
        NbtCompound nbt = custom.copyNbt();
        String actual = nbt.getString(key).orElse("");
        return value.equals(actual);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private record FilterResult(String newValue, int removedCount) {
    }

    public static void playSound(ServerPlayerEntity player, SoundEvent event) {
        player.playSoundToPlayer(event, SoundCategory.PLAYERS, 1f, 1f);
    }
}