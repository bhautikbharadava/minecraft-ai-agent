package com.bhautik.mcagent.integration;

import com.bhautik.mcagent.McAgent;

import java.lang.reflect.Method;

/**
 * Tiny reflective bridge to client-thread execution for features that
 * must drive client-authoritative state (vehicle steering). Fire-and-
 * forget: failures are logged and swallowed, never blocking the caller.
 */
final class ClientBridge {

    private ClientBridge() {
    }

    /** Runs a task on the client thread; no-op on dedicated servers. */
    static void post(Runnable task) {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object client = minecraftClass.getMethod("getInstance").invoke(null);
            Method execute = findExecute(minecraftClass);
            if (execute == null) {
                return;
            }
            execute.invoke(client, (Runnable) () -> {
                try {
                    task.run();
                } catch (Throwable broken) {
                    McAgent.LOGGER.warn("Client task failed: {}", String.valueOf(broken));
                }
            });
        } catch (Throwable unavailable) {
            // No client thread (dedicated server or shutdown).
        }
    }

    private static Method findExecute(Class<?> minecraftClass) {
        for (Method method : minecraftClass.getMethods()) {
            if ("execute".equals(method.getName())
                    && method.getParameterCount() == 1
                    && Runnable.class.equals(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }
}
