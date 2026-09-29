package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.testing.SelectivePersistentStorageProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SelectivePersistentStorageProfile.class)
class StorageFactoryServiceCatalogIntegrationTest {

    @Inject
    StorageFactory storageFactory;

    @Test
    void acmStorageOverrideIsApplied() {
        StorageBackend<String, String> backend = storageFactory.create(
                "acm",
                "acm-test.json",
                new TypeReference<Map<String, String>>() {}
        );

        assertInstanceOf(AccountAwareStorageBackend.class, backend);
    }
}
