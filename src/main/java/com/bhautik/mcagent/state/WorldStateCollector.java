package com.bhautik.mcagent.state;

import net.minecraft.server.network.ServerPlayerEntity;

public final class WorldStateCollector {
    private WorldStateCollector() {
    }

    public static WorldState collect(ServerPlayerEntity player) {
        return new WorldState(
                player.getHealth(),
                player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getWorld().getRegistryKey().getValue().toString()
        );
    }
}
