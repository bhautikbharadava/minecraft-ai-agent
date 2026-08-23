package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.McAgent;

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
    private final String initFailure;

    private ReflectiveBaritoneIntegration(Object mineProcess, String initFailure) {
        this.mineProcess = mineProcess;
        this.initFailure = initFailure;
    }

    static BaritoneIntegration create() {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object process = baritone.getClass().getMethod("getMineProcess").invoke(baritone);
            return new ReflectiveBaritoneIntegration(process, null);
        } catch (Throwable throwable) {
            Throwable root = throwable;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return new ReflectiveBaritoneIntegration(null,
                    "Baritone not detected (" + root.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public boolean available() {
        return mineProcess != null;
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
    public void stop() {
        if (mineProcess == null) {
            return;
        }
        try {
            Method cancel = findMethod(mineProcess.getClass(), "cancel", new Class<?>[0]);
            if (cancel != null) {
                cancel.setAccessible(true);
                runOnClientThread(() -> cancel.invoke(mineProcess));
            }
        } catch (Throwable ignored) {
            // Best-effort stop; verification still guards goal completion.
        }
    }

    @Override
    public String describe() {
        return mineProcess != null ? "Baritone" : String.valueOf(initFailure);
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
