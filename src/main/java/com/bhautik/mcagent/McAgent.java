package com.bhautik.mcagent;

import com.bhautik.mcagent.command.AgentCommand;
import com.bhautik.mcagent.executor.AgentExecutor;
import com.bhautik.mcagent.goal.GoalService;
import com.bhautik.mcagent.integration.BaritoneIntegration;
import com.bhautik.mcagent.planner.Planner;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McAgent implements ModInitializer {
    public static final String MOD_ID = "mcagent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final AgentExecutor executor = new AgentExecutor(new Planner(), BaritoneIntegration.unavailable());
    private final GoalService goalService = new GoalService();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Minecraft AI Agent foundation");
        AgentCommand.register(executor, goalService);
    }
}
