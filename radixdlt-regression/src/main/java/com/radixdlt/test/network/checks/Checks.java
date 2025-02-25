package com.radixdlt.test.network.checks;

import com.google.common.collect.Maps;
import com.radixdlt.test.network.RadixNetworkConfiguration;
import com.radixdlt.test.network.RadixNode;
import com.radixdlt.test.network.client.RadixHttpClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class Checks {

    private final Map<Class<? extends Check>, Check> checks;

    /**
     * Will run checks against the given nodes, using the given config
     */
    public static Checks forNodesAndCheckConfiguration(List<RadixNode> nodes, RadixNetworkConfiguration configuration) {
        return new Checks(nodes, configuration);
    }

    private Checks(List<RadixNode> nodes, RadixNetworkConfiguration configuration) {
        checks = Maps.newHashMap();

        // initialize the checks
        var livenessCheck = new LivenessCheck(nodes, 3, RadixHttpClient.fromRadixNetworkConfiguration(configuration));
        checks.put(LivenessCheck.class, livenessCheck);
    }

    public boolean runCheck(String name, Object... options) {
        AtomicReference<Check> checkWithMatchingName = new AtomicReference<>();
        checks.forEach((key, value) -> checkWithMatchingName.compareAndSet(null, value));
        if (checkWithMatchingName.get() == null) {
            throw new IllegalArgumentException("Check named '" + name + "' not found");
        }
        return checkWithMatchingName.get().check(options);
    }

    public boolean runCheck(Class<?> type, Object... options) {
        if (!checks.containsKey(type)) {
            throw new IllegalArgumentException("Check '" + type + "' not found");
        }
        return checks.get(type).check(options);
    }

}
