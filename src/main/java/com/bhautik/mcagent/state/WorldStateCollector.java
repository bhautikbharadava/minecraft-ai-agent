package com.bhautik.mcagent.state;

import net.minecraft.server.level.ServerPlayer;

public final class WorldStateCollector {
    private WorldStateCollector() {
    }

    public static WorldState collect(ServerPlayer player) {
        return new WorldState(
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.level().dimension().identifier().toString(),
                player.experienceLevel
        );
    }
}
