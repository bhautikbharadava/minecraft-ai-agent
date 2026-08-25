package com.bhautik.mcagent.action;

import com.bhautik.mcagent.McAgent;
import com.bhautik.mcagent.world.LightSensor;

import java.util.function.Supplier;

/**
 * Drops a torch where the agent stands when it is dark enough for mobs
 * to spawn. Prevention beats recovery: this runs after mining steps so
 * tunnels stay lit (PRD 15, background safety). Best-effort by design —
 * a failed placement never disturbs the goal.
 */
public final class LightTorchAction implements AgentAction {
    /** Light levels below this are mob-spawnable territory. */
    public static final int MIN_LIGHT = 8;
    public static final int MAX_PLACE_ATTEMPTS = 5;

    private final String title;
    private final String torchItemId;
    private final LightSensor light;
    private final Supplier<Integer> carriedTorches;
    private final PlaceBlockAction.Placer placer;

    private ActionStatus status = ActionStatus.PENDING;
    private String failureReason;
    private int attempts;

    public LightTorchAction(String torchItemId, LightSensor light,
                            Supplier<Integer> carriedTorches,
                            PlaceBlockAction.Placer placer) {
        this.torchItemId = torchItemId;
        this.light = light;
        this.carriedTorches = carriedTorches;
        this.placer = placer;
        this.title = "Light area";
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public ActionStatus status() {
        return status;
    }

    @Override
    public String failureReason() {
        return failureReason;
    }

    @Override
    public boolean bestEffort() {
        return true;
    }

    @Override
    public void start() {
        if (status != ActionStatus.PENDING) {
            return;
        }
        status = ActionStatus.RUNNING;
    }

    @Override
    public void tick() {
        if (status != ActionStatus.RUNNING) {
            return;
        }
        if (light.level() >= MIN_LIGHT || carriedTorches.get() <= 0) {
            // Nothing to fix: bright already, or no torches carried.
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Skipped: {} (light {}, torches {})",
                    title, light.level(), carriedTorches.get());
            return;
        }
        if (attempts >= MAX_PLACE_ATTEMPTS) {
            fail("torch placement never verified");
            return;
        }
        attempts++;
        PlaceBlockAction.Placer.Result result = placer.place(torchItemId);
        if (result.success()) {
            status = ActionStatus.SUCCESS;
            McAgent.LOGGER.info("[Action] Verified success: {}", title);
        } else if (attempts >= MAX_PLACE_ATTEMPTS) {
            fail(result.failureReason());
        }
    }

    @Override
    public void cancel() {
        if (!status.terminal()) {
            status = ActionStatus.CANCELLED;
        }
    }

    private void fail(String reason) {
        failureReason = reason;
        status = ActionStatus.FAILED;
        McAgent.LOGGER.warn("[Recovery] Action failed: {} ({})", title, reason);
    }
}
