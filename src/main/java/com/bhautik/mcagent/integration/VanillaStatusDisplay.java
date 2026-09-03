package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.action.AgentStatusDisplay;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.UUID;

/**
 * Shows agent state with vanilla UI only - a boss bar for the goal and
 * the action bar for live reasoning. Both are server-driven, so no
 * client mod is needed and this stays inside the project's
 * server-authoritative boundary.
 */
public final class VanillaStatusDisplay {

    private static final UUID BAR_ID =
            UUID.nameUUIDFromBytes("mcagent:status".getBytes());

    private VanillaStatusDisplay() {
    }

    public static AgentStatusDisplay forPlayer(ServerPlayer player) {
        ServerBossEvent bar = new ServerBossEvent(BAR_ID,
                Component.literal("Agent idle"),
                BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(0.0f);

        return new AgentStatusDisplay() {
            private boolean shown;

            @Override
            public void showGoal(String goal, String step, int done, int total) {
                if (!shown) {
                    bar.addPlayer(player);
                    shown = true;
                }
                bar.setColor(BossEvent.BossBarColor.BLUE);
                bar.setName(Component.literal(goal)
                        .withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("  |  " + step)
                                .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(
                                        total > 0 ? "  (" + done + "/" + total + ")" : "")
                                .withStyle(ChatFormatting.GRAY)));
                // Guard against a plan that grew mid-run reporting > 100%.
                bar.setProgress(total <= 0 ? 0.0f
                        : Math.min(1.0f, (float) done / total));
            }

            @Override
            public void note(String reasoning) {
                // true = above the hotbar, so it does not bury the chat.
                player.sendSystemMessage(Component.literal(reasoning)
                        .withStyle(ChatFormatting.YELLOW), true);
            }

            @Override
            public void finish(String outcome, boolean success) {
                if (shown) {
                    bar.setColor(success ? BossEvent.BossBarColor.GREEN
                            : BossEvent.BossBarColor.RED);
                    bar.setName(Component.literal(outcome)
                            .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED));
                    bar.setProgress(success ? 1.0f : bar.getProgress());
                    // Leave the finished bar up briefly by removing the
                    // player on the next goal rather than immediately, so
                    // the outcome is actually readable.
                    bar.removePlayer(player);
                    shown = false;
                }
                player.sendSystemMessage(Component.literal(outcome)
                        .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED),
                        true);
            }
        };
    }
}
