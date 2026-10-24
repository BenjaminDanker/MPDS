package mpds.mpds;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Story and boss rewards that the datapacks used to grant by running MPDS commands. When a player completes one of
 * these advancements, the reward is recorded here directly: the skill points of a magic chapter, the Magic or Tech
 * completion, and the desert and cave boss rewards. Advancements that are not in the table cost one map lookup.
 * Database work runs on {@link #IO}; the player's "already rewarded" tag is added on the server thread afterwards.
 */
public final class AdvancementRewards {
    private sealed interface Reward permits SkillPoints, Branch, Boss {
    }

    /** {@code points} as stored by sql.addSkillPoints (the datapacks' mpds_points_N tags). */
    private record SkillPoints(int points) implements Reward {
    }

    private record Branch(String branch, int points) implements Reward {
    }

    /** {@code tag} is the existing per-player tag that marks the reward as given (it skips the database next time). */
    private record Boss(String action, String tag) implements Reward {
    }

    private static final Map<Identifier, Reward> REWARDS = Map.of(
        Identifier.parse("magicstory:temple"), new SkillPoints(6),
        Identifier.parse("magicstory:fire"), new SkillPoints(9),
        Identifier.parse("magicstory:tower"), new SkillPoints(6),
        Identifier.parse("magicstory:wizard"), new SkillPoints(6),
        Identifier.parse("magicstory:void"), new SkillPoints(9),
        Identifier.parse("magicstory:convergence"), new Branch("magic", 12),
        Identifier.parse("techstory:foundry_lit"), new Branch("tech", 0),
        // Revoked and earned again on every legendary kill; the tag makes the repeats free.
        Identifier.parse("infinity_cave:kill/legendary"), new Boss("cave", "mpds_cave_defeated"),
        Identifier.parse("petroglyph-guide:pharao_boss"), new Boss("desert", "mpds_desert_defeated")
    );

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MPDS-Advancement-Rewards");
        thread.setDaemon(true);
        return thread;
    });

    private AdvancementRewards() {
    }

    /** True when completing this advancement gives an MPDS reward (cheap check, called on every award). */
    public static boolean isRewarded(Identifier advancement) {
        return REWARDS.containsKey(advancement);
    }

    /** Server thread: the player has just completed {@code advancement}. */
    public static void onCompleted(ServerPlayer player, Identifier advancement) {
        Reward reward = REWARDS.get(advancement);
        if (reward == null || (reward instanceof Boss boss && player.entityTags().contains(boss.tag()))) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        String name = player.getName().getString();
        String uuid = player.getStringUUID();
        UUID id = player.getUUID();
        IO.execute(() -> {
            try {
                switch (reward) {
                    case SkillPoints points -> sql.addSkillPoints(name, uuid, points.points());
                    case Branch branch -> {
                        if (branch.points() > 0) {
                            sql.addSkillPoints(name, uuid, branch.points());
                        }
                        sql.completeBranch(name, uuid, branch.branch());
                    }
                    case Boss boss -> {
                        sql.completeDefeatReward(name, uuid, boss.action());
                        server.execute(() -> {
                            ServerPlayer online = server.getPlayerList().getPlayer(id);
                            if (online != null) {
                                online.addTag(boss.tag());
                            }
                        });
                    }
                }
                MPDS.LOGGER.info("Recorded the {} reward for {}", advancement, name);
            } catch (Exception failure) {
                MPDS.LOGGER.error("ALERT: could not record the {} reward for {}", advancement, name, failure);
            }
        });
    }
}
