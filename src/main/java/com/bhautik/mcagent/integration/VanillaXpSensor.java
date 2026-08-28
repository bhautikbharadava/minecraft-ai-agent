package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.world.XpSensor;

import net.minecraft.server.level.ServerPlayer;

/**
 * Reads experience straight off the server-side player. Server
 * authoritative for the same reason equipping is (see the design
 * decisions log): the integrated server owns the authoritative counts
 * and syncs them to the client.
 */
public final class VanillaXpSensor {

    private VanillaXpSensor() {
    }

    public static XpSensor sensor(ServerPlayer player) {
        return new XpSensor() {
            @Override public int level() {
                return player.experienceLevel;
            }

            @Override public int totalPoints() {
                return player.totalExperience;
            }
        };
    }
}
