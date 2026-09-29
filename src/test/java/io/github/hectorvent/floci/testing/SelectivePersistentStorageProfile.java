package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Keeps storage in memory except for DynamoDB and ACM. The duplicate-backend test checks only
 * {@code dynamodb-tables.json}; the service-catalog test checks the ACM backend type, not its
 * contents. Clearing the backends in the former cannot invalidate the latter's assertion.
 */
public class SelectivePersistentStorageProfile implements QuarkusTestProfile {

    public static final String STORAGE_DIR = "target/selective-persistent-storage-it";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.storage.mode", "memory",
                "floci.storage.services.dynamodb.mode", "persistent",
                "floci.storage.services.acm.mode", "persistent",
                "floci.storage.persistent-path", STORAGE_DIR);
    }
}
