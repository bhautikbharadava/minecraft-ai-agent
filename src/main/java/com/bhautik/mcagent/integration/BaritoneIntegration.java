package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.McAgent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Execution seam between the agent and a navigation backend. Goals and
 * planners never touch this layer directly; only actions do.
 */
public interface BaritoneIntegration {
    /** True when a navigation backend is present and usable. */
    boolean available();

    /**
     * Starts mining until the backend reports the requested quantity.
     * Returns false when no backend is available or the request fails.
     */
    boolean startMine(String blockName, int quantity);

    /**
     * Starts pathing to the given block position. Returns false when no
     * backend is available or the request fails; arrival is verified by
     * the calling action against live world state.
     */
    boolean startGoTo(int x, int y, int z);

    /**
     * Starts open-ended exploration outward from the given center
     * coordinates. Arrival at whatever the agent seeks is verified by
     * the calling action; stop() ends the wander.
     */
    boolean startExplore(int centerX, int centerZ);

    /** Stops any in-flight navigation owned by the agent. Safe to call repeatedly. */
    void stop();

    /** Human-readable description for logs and status output. */
    String describe();

    static BaritoneIntegration unavailable() {
        return new BaritoneIntegration() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public boolean startMine(String blockName, int quantity) {
                return false;
            }

            @Override
            public boolean startGoTo(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean startExplore(int centerX, int centerZ) {
                return false;
            }

            @Override
            public void stop() {
            }

            @Override
            public String describe() {
                return "no navigation backend installed";
            }
        };
    }

    /**
     * Detects Baritone at runtime without a compile-time dependency.
     * Falls back to {@link #unavailable()} when it is not loaded.
     */
    static BaritoneIntegration detect() {
        return ReflectiveBaritoneIntegration.create();
    }
}

/**
 * Talks to Baritone through reflection so the mod builds and runs with or
 * without Baritone installed. All failures degrade to "unavailable".
 */
final class ReflectiveBaritoneIntegration implements BaritoneIntegration {

    private static final long CLIENT_THREAD_TIMEOUT_SECONDS = 5;

    private final Object mineProcess;
    private final Object goalProcess;
    private final Object exploreProcess;
    private final String initFailure;

    private ReflectiveBaritoneIntegration(Object mineProcess, Object goalProcess,
                                          Object exploreProcess, String initFailure) {
        this.mineProcess = mineProcess;
        this.goalProcess = goalProcess;
        this.exploreProcess = exploreProcess;
        this.initFailure = initFailure;
    }

    static BaritoneIntegration create() {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object mine = null;
            Object goal = null;
            Object explore = null;
            try {
                mine = baritone.getClass().getMethod("getMineProcess").invoke(baritone);
            } catch (Throwable ignored) {
                // mining unsupported by this backend build; goto may still work
            }
            try {
                goal = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);
            } catch (Throwable ignored) {
                // goto unsupported; mining may still work
            }
            try {
                explore = baritone.getClass().getMethod("getExploreProcess").invoke(baritone);
            } catch (Throwable ignored) {
                // exploration unsupported; the rest may still work
            }
            if (mine == null && goal == null && explore == null) {
                return new ReflectiveBaritoneIntegration(null, null, null,
                        "no usable Baritone processes found");
            }
            return new ReflectiveBaritoneIntegration(mine, goal, explore, null);
        } catch (Throwable throwable) {
            Throwable root = throwable;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return new ReflectiveBaritoneIntegration(null, null, null,
                    "Baritone not detected (" + root.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public boolean available() {
        return mineProcess != null || goalProcess != null || exploreProcess != null;
    }

    @Override
    public boolean startMine(String blockName, int quantity) {
        if (mineProcess == null) {
            return false;
        }
        try {
            Method byName = findMethod(mineProcess.getClass(), "mineByName",
                    new Class<?>[]{int.class, String[].class});
            if (byName != null) {
                byName.setAccessible(true);
                runOnClientThread(() -> byName.invoke(mineProcess, quantity, (Object) new String[]{blockName}));
                return true;
            }
            Method plain = findMethod(mineProcess.getClass(), "mine",
                    new Class<?>[]{String[].class});
            if (plain != null) {
                plain.setAccessible(true);
                runOnClientThread(() -> plain.invoke(mineProcess, (Object) new String[]{blockName}));
                return true;
            }
            return false;
        } catch (Throwable throwable) {
            Throwable root = rootOf(throwable);
            McAgent.LOGGER.warn("Baritone mining request failed: {}", String.valueOf(root));
            return false;
        }
    }

    @Override
    public boolean startGoTo(int x, int y, int z) {
        if (goalProcess == null) {
            return false;
        }
        try {
            Class<?> goalBlock = resolveGoalBlock();
            if (goalBlock == null) {
                McAgent.LOGGER.warn("Baritone goto unsupported: no GoalBlock class found");
                return false;
            }
            Object goal = goalBlock.getConstructor(int.class, int.class, int.class)
                    .newInstance(x, y, z);
            Method setGoalAndPath = findMethod(goalProcess.getClass(), "setGoalAndPath",
                    new Class<?>[]{goalBlock});
            if (setGoalAndPath != null) {
                Method call = setGoalAndPath;
                call.setAccessible(true);
                runOnClientThread(() -> call.invoke(goalProcess, goal));
                return true;
            }
            Method setGoal = findMethod(goalProcess.getClass(), "setGoal",
                    new Class<?>[]{goalBlock});
            if (setGoal == null) {
                return false;
            }
            Method startPath = findMethod(goalProcess.getClass(), "path", new Class<?>[0]);
            if (startPath == null) {
                return false;
            }
            setGoal.setAccessible(true);
            runOnClientThread(() -> setGoal.invoke(goalProcess, goal));
            startPath.setAccessible(true);
            runOnClientThread(() -> startPath.invoke(goalProcess));
            return true;
        } catch (Throwable throwable) {
            Throwable root = rootOf(throwable);
            McAgent.LOGGER.warn("Baritone goto request failed: {}", String.valueOf(root));
            return false;
        }
    }

    /** Goal classes have moved between Baritone versions; probe known homes. */
    private static Class<?> resolveGoalBlock() {
        for (String candidate : new String[]{
                "baritone.api.pathing.goals.GoalBlock",
                "baritone.api.goal.GoalBlock",
                "baritone.api.utils.goal.GoalBlock"}) {
            try {
                return Class.forName(candidate);
            } catch (ClassNotFoundException ignored) {
                // try the next known location
            }
        }
        return null;
    }

    @Override
    public boolean startExplore(int centerX, int centerZ) {
        if (exploreProcess == null) {
            return false;
        }
        try {
            Method explore = findMethod(exploreProcess.getClass(), "explore",
                    new Class<?>[]{int.class, int.class});
            if (explore == null) {
                return false;
            }
            explore.setAccessible(true);
            runOnClientThread(() -> explore.invoke(exploreProcess, centerX, centerZ));
            return true;
        } catch (Throwable throwable) {
            Throwable root = rootOf(throwable);
            McAgent.LOGGER.warn("Baritone explore request failed: {}", String.valueOf(root));
            return false;
        }
    }

    @Override
    public void stop() {
        for (Object process : new Object[]{mineProcess, goalProcess, exploreProcess}) {
            if (process == null) {
                continue;
            }
            try {
                Method cancel = findMethod(process.getClass(), "cancel", new Class<?>[0]);
                if (cancel != null) {
                    cancel.setAccessible(true);
                    runOnClientThread(() -> cancel.invoke(process));
                }
            } catch (Throwable ignored) {
                // Best-effort stop; verification still guards goal completion.
            }
        }
    }

    @Override
    public String describe() {
        if (mineProcess == null && goalProcess == null && exploreProcess == null) {
            return String.valueOf(initFailure);
        }
        StringBuilder description = new StringBuilder("Baritone");
        if (goalProcess == null) {
            description.append(" (no goto)");
        }
        if (mineProcess == null) {
            description.append(" (no mining)");
        }
        if (exploreProcess == null) {
            description.append(" (no explore)");
        }
        return description.toString();
    }

    /**
     * Baritone state is main-thread-only; integrated-server commands and
     * ticks run on the server thread. Marshals the call onto the client
     * thread via {@code Minecraft.execute} when needed, waiting briefly
     * for completion so callers still get synchronous outcomes.
     */
    private void runOnClientThread(ThrowingRunnable task) throws Throwable {
        Class<?> clientClass;
        try {
            clientClass = Class.forName("net.minecraft.client.Minecraft");
        } catch (ClassNotFoundException missing) {
            task.run(); // dedicated server: no client thread exists
            return;
        }
        Object client = clientClass.getMethod("getInstance").invoke(null);
        Method onThread = findMethod(clientClass, "isOnThread", new Class<?>[0]);
        if (onThread == null) {
            onThread = findMethod(clientClass, "isSameThread", new Class<?>[0]);
        }
        if (onThread != null) {
            onThread.setAccessible(true);
            if ((Boolean) onThread.invoke(client)) {
                task.run();
                return;
            }
        }
        Method execute = findMethod(clientClass, "execute", new Class<?>[]{Runnable.class});
        if (execute == null) {
            throw new IllegalStateException("client thread scheduling unavailable");
        }
        execute.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        execute.invoke(client, (Runnable) () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(CLIENT_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for client thread");
        }
        Throwable thrown = failure.get();
        if (thrown != null) {
            throw thrown;
        }
    }

    private static Throwable rootOf(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameterTypes.length) {
                    continue;
                }
                boolean matches = true;
                for (int i = 0; i < actual.length; i++) {
                    if (!actual[i].isAssignableFrom(parameterTypes[i])
                            && !actual[i].equals(parameterTypes[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    return method;
                }
            }
            for (Class<?> iface : type.getInterfaces()) {
                Method found = findMethod(iface, name, parameterTypes);
                if (found != null) {
                    return found;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
