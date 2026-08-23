package com.bhautik.mcagent.integration;

import java.util.Optional;

public interface BaritoneIntegration {
    Optional<String> status();

    static BaritoneIntegration unavailable() {
        return () -> Optional.empty();
    }
}
