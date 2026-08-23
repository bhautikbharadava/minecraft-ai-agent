package com.bhautik.mcagent;

import com.bhautik.mcagent.command.AgentCommand;
import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.goal.GoalService;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.planner.Planner;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McAgent implements ModInitializer {
    public static final String MOD_ID = "mcagent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final BaritoneIntegration BARITONE = BaritoneIntegration.detect();

    private final AgentExecutor executor = new AgentExecutor(new Planner(BARITONE), BARITONE);
    private final GoalService goalService = new GoalService(executor);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Minecraft AI Agent (navigation backend: {})", BARITONE.describe());
        AgentCommand.register(executor, goalService);
        ServerTickEvents.END_SERVER_TICK.register(goalService::serverTick);
    }
}
