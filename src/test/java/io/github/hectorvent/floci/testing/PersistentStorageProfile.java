package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs the Athena workgroup, CloudFormation stack, and Glue security-configuration persistence
 * tests against one persistent directory. Each test reads and clears only its own storage file:
 * {@code workgroups.json}, {@code cloudformation-stacks.json}, or
 * {@code security_configurations.json}. The other services' files do not affect its assertions.
 */
public class PersistentStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/shared-persistent-storage-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.mode", "persistent",
                "floci.storage.persistent-path", STORAGE_DIR);
    }
}
