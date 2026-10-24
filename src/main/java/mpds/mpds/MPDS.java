package mpds.mpds;

import com.silver.authorization.PermissionNodes;
import com.silver.authorization.fabric.AuthorizationChecks;
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
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import mpds.mpds.net.ReturnRemoveRequestPayload;
import mpds.mpds.net.ReturnRemoveResponsePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class MPDS implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mpds");

    public static String ServerName;

    public static final Set<UUID> broken = ConcurrentHashMap.newKeySet();

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
    /** On the Magic, Tech and Magitech servers: everything a player carries (not just soulbound items) follows them between these servers only. */
    public static boolean FamilySync;

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
        FamilySync = Boolean.parseBoolean(config.get("FamilySync"));

        try {
            sql.configurePendingDirectory(configDir.resolve("pending-saves"));
        } catch (IOException e) {
            LOGGER.error("Could not load MPDS pending-save queue", e);
        }

        try {
            sql.init();
        } catch (SQLException e) {
            LOGGER.error("FAIL TO CONNECT MYSQL");
            LOGGER.error("DID YOU CHANGE MPDS CONFIG?");
            e.printStackTrace();
        }
        sql.startRecovery();

        ServerPlayConnectionEvents.JOIN.register(Join::onjoin);
        ServerPlayConnectionEvents.DISCONNECT.register(Disconnect::ondisconnect);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("updateskip")
                .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_SKIP_MANAGE))
                .then(argument("player", StringArgumentType.word()).then(argument("skip", BoolArgumentType.bool())
                        .executes(ctx -> {
                            while (true) {
                                try {
                                    sql.updateSkip(StringArgumentType.getString(ctx, "player"), String.valueOf(BoolArgumentType.getBool(ctx, "skip")));
                                    ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.translatable("set " + StringArgumentType.getString(ctx, "player") + "'s data " + BoolArgumentType.getBool(ctx, "skip")).withStyle(ChatFormatting.YELLOW), false);

                                    return 1;
                                } catch (CommunicationsException ignored) {
                                } catch (Exception e) {
                                    ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.translatable("THERE WERE SOME ERRORS : \n" + e.getMessage()).withStyle(ChatFormatting.RED), false);
                                    LOGGER.error("THERE WERE SOME ERRORS :");
                                    e.printStackTrace();

                                    return 1;
                                }
                            }
                        })
                    ))));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("showskip")
                .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_SKIP_INSPECT))
                        .executes(ctx -> {
                            StringBuilder skipp = new StringBuilder();

                            while (true) {
                                try {
                                    for (String skippedPlayer : sql.showSkippedPlayers())
                                        skipp.append("・").append(skippedPlayer).append("\n");

                                    ServerPlayer player;
                                    if ((player = ctx.getSource().getPlayer()) != null) {
                                        player.sendSystemMessage(Component.nullToEmpty(skipp.toString()));
                                    } else {
                                        ctx.getSource().getServer().sendSystemMessage(Component.nullToEmpty(skipp.toString()));
                                    }

                                    return 1;
                                } catch (CommunicationsException ignored) {
                                } catch (Exception e) {
                                    ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.translatable("THERE WERE SOME ERRORS : \n" + e.getMessage()).withStyle(ChatFormatting.RED), false);
                                    LOGGER.error("THERE WERE SOME ERRORS :");
                                    e.printStackTrace();

                                    return 1;
                                }
                            }
                        })
                ));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("mpdscomplete")
                .then(argument("action", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer actor = ctx.getSource().getPlayer();
                        String action = StringArgumentType.getString(ctx, "action").toLowerCase(Locale.ROOT);
                        if (actor == null) return 0;
                        if (action.equals("magic") || action.equals("tech")) {
                            // Finishing a branch story: the story's completion function tags the player, then calls this.
                            if (!actor.entityTags().contains("mpds_" + action + "_completed")) return 0;
                            try {
                                sql.completeBranch(actor.getName().getString(), actor.getStringUUID(), action);
                                actor.removeTag("mpds_" + action + "_completed");
                                return 1;
                            } catch (Exception failure) {
                                LOGGER.error("ALERT: could not record {} completion for {}", action, actor.getName().getString(), failure);
                                return 0;
                            }
                        }
                        if (!(action.equals("desert") || action.equals("cave"))) return 0;
                        if (!actor.entityTags().contains("mpds_" + action + "_defeated")) return 0;
                        try {
                            sql.completeDefeatReward(actor.getName().getString(), actor.getStringUUID(), action);
                            return 1;
                        } catch (Exception failure) {
                            LOGGER.error("Failed to record {} datapack completion for {}", action, actor.getName().getString(), failure);
                            return 0;
                        }
                    }))));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsdefeated")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_PROGRESSION_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("flag", StringArgumentType.word())
                            .then(argument("value", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String flag = StringArgumentType.getString(ctx, "flag");
                                    boolean value = BoolArgumentType.getBool(ctx, "value");

                                    String name = target.getName().getString();
                                    String uuid = target.getStringUUID();

                                    try {
                                        boolean previous;
                                        switch (flag.toLowerCase(Locale.ROOT)) {
                                            case "skyisland", "skyislanddefeated" -> {
                                                previous = sql.isSkyIslandDefeated(name, uuid);
                                                sql.setSkyIslandDefeated(name, uuid, value);
                                            }
                                            case "desert", "desertdefeated" -> {
                                                previous = sql.isDesertDefeated(name, uuid);
                                                sql.setDesertDefeated(name, uuid, value);
                                            }
                                            case "ocean", "oceandefeated" -> {
                                                previous = sql.isOceanDefeated(name, uuid);
                                                sql.setOceanDefeated(name, uuid, value);
                                            }
                                            case "cave", "cavedefeated" -> {
                                                previous = sql.isCaveDefeated(name, uuid);
                                                sql.setCaveDefeated(name, uuid, value);
                                            }
                                            default -> {
                                                ctx.getSource().sendSystemMessage(Component.literal(
                                                    "Unknown flag. Use: SkyIslandDefeated, DesertDefeated, OceanDefeated, CaveDefeated"));
                                                return 0;
                                            }
                                        }

                                        boolean changed = previous != value;

                                        ctx.getSource().sendSystemMessage(Component.literal(
                                            "Set " + name + " " + flag + " = " + value + (changed ? "" : " (unchanged)")));
                                        return changed ? 1 : 0;
                                    } catch (Exception e) {
                                        ctx.getSource().sendSystemMessage(Component.literal("Error updating flag: " + e.getMessage()).withStyle(ChatFormatting.RED));
                                        LOGGER.error("Error updating defeated flag {} for {}", flag, name, e);
                                        return 0;
                                    }
                                }))))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdssoulboundmax")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_INVENTORY_METADATA_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("delta", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                int delta = IntegerArgumentType.getInteger(ctx, "delta");

                                String name = target.getName().getString();
                                String uuid = target.getStringUUID();

                                try {
                                    sql.adjustSoulboundMax(name, uuid, delta);
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                        "Adjusted " + name + " SoulboundMax by " + delta));
                                    return 1;
                                } catch (Exception e) {
                                    ctx.getSource().sendSystemMessage(Component.literal("Error updating SoulboundMax: " + e.getMessage()).withStyle(ChatFormatting.RED));
                                    LOGGER.error("Error adjusting SoulboundMax for {}", name, e);
                                    return 0;
                                }
                            })))
            )
        );

        // Silent unlock: a secondary boss kill makes one element choosable. No chat message, no item.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdselementunlock")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_PROGRESSION_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("element", StringArgumentType.word())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                String element = StringArgumentType.getString(ctx, "element").toLowerCase(Locale.ROOT);
                                int bit = switch (element) {
                                    case "air" -> sql.ElementProgress.AIR;
                                    case "water" -> sql.ElementProgress.WATER;
                                    case "earth" -> sql.ElementProgress.EARTH;
                                    case "fire" -> sql.ElementProgress.FIRE;
                                    default -> 0;
                                };
                                if (bit == 0) {
                                    ctx.getSource().sendSystemMessage(Component.literal("Unknown element. Use: air, water, earth, fire"));
                                    return 0;
                                }
                                try {
                                    boolean added = sql.unlockElement(target.getName().getString(), target.getStringUUID(), bit);
                                    LOGGER.info("Element unlock {} for {} (new={})", element, target.getName().getString(), added);
                                    return added ? 1 : 0;
                                } catch (Exception e) {
                                    LOGGER.error("Error unlocking element {} for {}", element, target.getName().getString(), e);
                                    return 0;
                                }
                            })))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsskillpoints")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_PROGRESSION_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("delta", IntegerArgumentType.integer(-1000, 1000))
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                int delta = IntegerArgumentType.getInteger(ctx, "delta");
                                try {
                                    sql.addSkillPoints(target.getName().getString(), target.getStringUUID(), delta);
                                    LOGGER.info("Skill points {} for {}", delta, target.getName().getString());
                                    return 1;
                                } catch (Exception e) {
                                    LOGGER.error("Error adjusting skill points for {}", target.getName().getString(), e);
                                    return 0;
                                }
                            })))
            )
        );

        // Datapack-safe claims. Staff-only commands cannot run inside datapack functions, so a function tags the
        // player (players cannot tag themselves) and calls one of these, which checks the tag and does the work.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("mpdselementclaim").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayer();
                if (player == null) return 0;
                int claimed = 0;
                for (String element : new String[] {"air", "water", "earth", "fire"}) {
                    String tag = "mpds_unlock_" + element;
                    if (!player.entityTags().contains(tag)) continue;
                    player.removeTag(tag);
                    int bit = switch (element) {
                        case "air" -> sql.ElementProgress.AIR;
                        case "water" -> sql.ElementProgress.WATER;
                        case "earth" -> sql.ElementProgress.EARTH;
                        default -> sql.ElementProgress.FIRE;
                    };
                    try {
                        sql.unlockElement(player.getName().getString(), player.getStringUUID(), bit);
                        claimed++;
                    } catch (Exception failure) {
                        LOGGER.error("Failed to record {} element unlock for {}", element, player.getName().getString(), failure);
                    }
                }
                return claimed;
            })));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(literal("mpdsskillclaim").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayer();
                if (player == null) return 0;
                int total = 0;
                for (int points = 1; points <= 20; points++) {
                    String tag = "mpds_points_" + points;
                    if (!player.entityTags().contains(tag)) continue;
                    player.removeTag(tag);
                    try {
                        sql.addSkillPoints(player.getName().getString(), player.getStringUUID(), points);
                        total += points;
                    } catch (Exception failure) {
                        LOGGER.error("Failed to add {} skill points for {}", points, player.getName().getString(), failure);
                    }
                }
                return total;
            })));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsgrantbossreward")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_REWARD_GRANT))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("flag", StringArgumentType.word())
                            .then(argument("delta", IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String flag = StringArgumentType.getString(ctx, "flag");
                                    int delta = IntegerArgumentType.getInteger(ctx, "delta");

                                    String name = target.getName().getString();
                                    String uuid = target.getStringUUID();

                                    try {
                                        boolean granted;
                                        try {
                                            granted = mpds.mpds.api.BossRewards.grant(name, uuid, flag, delta);
                                        } catch (IllegalArgumentException unknownFlag) {
                                            ctx.getSource().sendSystemMessage(Component.literal(
                                                "Unknown flag. Use: SkyIslandDefeated, DesertDefeated, OceanDefeated, CaveDefeated"));
                                            return 0;
                                        }
                                        if (!granted) {
                                            ctx.getSource().sendSystemMessage(Component.literal(
                                                name + " already has " + flag + "=true; SoulboundMax not changed."));
                                            return 0;
                                        }
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                            "Granted boss reward for " + name + ": " + flag + "=true, SoulboundMax +" + delta));
                                        return 1;
                                    } catch (Exception e) {
                                        ctx.getSource().sendSystemMessage(Component.literal("Error granting boss reward: " + e.getMessage()).withStyle(ChatFormatting.RED));
                                        LOGGER.error("Error granting boss reward {} for {}", flag, name, e);
                                        return 0;
                                    }
                                }))))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsgetsoulboundmax")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_INVENTORY_METADATA_INSPECT))
                    .then(argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");

                            String name = target.getName().getString();
                            String uuid = target.getStringUUID();

                            try {
                                int max = sql.getSoulboundMax(name, uuid);
                                ctx.getSource().sendSystemMessage(Component.literal(
                                    name + " SoulboundMax=" + max));
                                return 1;
                            } catch (Exception e) {
                                ctx.getSource().sendSystemMessage(Component.literal("Error reading SoulboundMax: " + e.getMessage()).withStyle(ChatFormatting.RED));
                                LOGGER.error("Error reading SoulboundMax for {}", name, e);
                                return 0;
                            }
                        }))
            )
        );

        // Used by Babylon when a player switches between the Magic and Tech branches.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsbranchswitch")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_PROGRESSION_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("from", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(argument("to", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String from = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "from");
                                    String to = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "to");
                                    try {
                                        sql.switchBranch(target.getName().getString(), target.getStringUUID(), from, to);
                                        LOGGER.info("{} switched branch {} -> {}", target.getName().getString(), from, to);
                                        return 1;
                                    } catch (Exception e) {
                                        LOGGER.error("ALERT: branch switch {} -> {} failed for {}", from, to, target.getName().getString(), e);
                                        return 0;
                                    }
                                }))))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsstage1")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_PROGRESSION_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("value", BoolArgumentType.bool())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                boolean value = BoolArgumentType.getBool(ctx, "value");

                                String name = target.getName().getString();
                                String uuid = target.getStringUUID();

                                try {
                                    sql.setStage1Cleared(name, uuid, value);
                                    ctx.getSource().sendSystemMessage(Component.literal("Set " + name + " Stage1Cleared = " + value));
                                    return 1;
                                } catch (Exception e) {
                                    ctx.getSource().sendSystemMessage(Component.literal("Error updating Stage1Cleared: " + e.getMessage()).withStyle(ChatFormatting.RED));
                                    LOGGER.error("Error updating Stage1Cleared for {}", name, e);
                                    return 0;
                                }
                            })))
            )
        );

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                literal("mpdsremovecustomid")
                    .requires(AuthorizationChecks.requires(PermissionNodes.MPDS_INVENTORY_METADATA_MODIFY))
                    .then(argument("player", EntityArgument.player())
                        .then(argument("key", StringArgumentType.word())
                            .then(argument("value", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String key = StringArgumentType.getString(ctx, "key");
                                    String value = StringArgumentType.getString(ctx, "value");

                                    if (wrappedOps == null) {
                                        ctx.getSource().sendSystemMessage(Component.literal("MPDS registry ops not ready yet.").withStyle(ChatFormatting.RED));
                                        return 0;
                                    }

                                    String name = target.getName().getString();
                                    String uuid = target.getStringUUID();

                                    try {
                                        int removed = removeCustomDataFromDbInventories(uuid, key, value);
                                        ctx.getSource().sendSystemMessage(Component.literal("Removed " + removed + " item(s) from " + name + " DB soulbound inventories for " + key + "=" + value));
                                        return 1;
                                    } catch (Exception e) {
                                        ctx.getSource().sendSystemMessage(Component.literal("Error removing items from DB: " + e.getMessage()).withStyle(ChatFormatting.RED));
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
                                    ctx.getSource().sendSystemMessage(Component.literal("MPDS registry ops not ready yet.").withStyle(ChatFormatting.RED));
                                    return 0;
                                }

                                ServerPlayer self = ctx.getSource().getPlayer();
                                if (self == null) {
                                    ctx.getSource().sendSystemMessage(Component.literal("Players only.").withStyle(ChatFormatting.RED));
                                    return 0;
                                }

                                String key = StringArgumentType.getString(ctx, "key");
                                String value = StringArgumentType.getString(ctx, "value");

                                try {
                                    int removed = removeCustomDataFromDbInventories(self.getStringUUID(), key, value);
                                    self.sendSystemMessage(Component.literal("Removed " + removed + " item(s) from your DB soulbound inventories for " + key + "=" + value)
                                            .withStyle(ChatFormatting.YELLOW));
                                    return 1;
                                } catch (Exception e) {
                                    self.sendSystemMessage(Component.literal("Error removing items from DB: " + e.getMessage()).withStyle(ChatFormatting.RED));
                                    LOGGER.error("Error removing custom data items from DB for {} {}={}", self.getName().getString(), key, value, e);
                                    return 0;
                                }
                            })))
            )
        );

        ServerLifecycleEvents.SERVER_STARTING.register(server -> wrappedOps = server.registryAccess().createSerializationContext(JsonOps.INSTANCE));

        PayloadTypeRegistry.serverboundPlay().register(ReturnRemoveRequestPayload.PACKET_ID, ReturnRemoveRequestPayload.codec);
        PayloadTypeRegistry.clientboundPlay().register(ReturnRemoveResponsePayload.PACKET_ID, ReturnRemoveResponsePayload.codec);

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
                        removedDb += removeCustomDataFromDbInventories(context.player().getStringUUID(), key, value);

                        remainingInv += countCustomDataInLiveInventories(context.player(), key, value);
                        remainingDb += countCustomDataInDbInventories(context.player().getStringUUID(), key, value);
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
            // Players were disconnected during shutdown and their saves queued; write them now.
            sql.flushPendingSavesNow();
            sql.stopRecovery();
            try {
                sql.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        LOGGER.info("MPDS loaded");
    }

    private static int removeCustomDataFromLiveInventories(ServerPlayer player, String key, String value) {
        if (player == null || key == null || value == null || key.isBlank() || value.isBlank()) {
            return 0;
        }

        int removed = 0;

        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                inv.setItem(i, ItemStack.EMPTY);
                removed++;
            }
        }

        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                ender.setItem(i, ItemStack.EMPTY);
                removed++;
            }
        }

        if (removed > 0) {
            inv.setChanged();
            ender.setChanged();
            player.containerMenu.broadcastChanges();
        }

        return removed;
    }

    private static int countCustomDataInLiveInventories(ServerPlayer player, String key, String value) {
        if (player == null || key == null || value == null || key.isBlank() || value.isBlank()) {
            return 0;
        }

        int count = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                count++;
            }
        }

        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (!stack.isEmpty() && matchesCustomData(stack, key, value)) {
                count++;
            }
        }

        return count;
    }

    private static int countCustomDataInDbInventories(String uuid, String key, String value) throws SQLException {
        var stored = sql.findPlayerData(uuid);
        if (stored.isEmpty()) return 0;
        var row = stored.orElseThrow().data();
        String mainStored = safeString(row.main());
        String offStored = safeString(row.off());
        String armorStored = safeString(row.armor());
        String enderStored = safeString(row.enderChestInventory());
        int count = 0;

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

    /** Removes items carrying custom data key=value from the player's stored (database) inventories; returns how many. */
    public static int removeCustomDataFromDbInventories(String uuid, String key, String value) throws SQLException {
        int removed = 0;
        var stored = sql.findPlayerData(uuid);
        if (stored.isEmpty()) return 0;
        var row = stored.orElseThrow().data();
        String mainStored = safeString(row.main());
        String offStored = safeString(row.off());
        String armorStored = safeString(row.armor());
        String enderStored = safeString(row.enderChestInventory());

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

        sql.updateInventories(uuid, main.newValue, off.newValue, armor.newValue, ender.newValue);

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
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return false;
        }
        CompoundTag nbt = custom.copyTag();
        String actual = nbt.getString(key).orElse("");
        return value.equals(actual);
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private record FilterResult(String newValue, int removedCount) {
    }

    public static void playSound(ServerPlayer player, SoundEvent event) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), event, SoundSource.PLAYERS, 1f, 1f);
    }

}
