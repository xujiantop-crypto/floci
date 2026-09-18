package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigDataServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void transformFeatureFlagsProducesRetrievalTimeValues() throws Exception {
        byte[] deploymentContent = """
                {
                  "flags": {"enabled": {"name": "enabled"}, "disabled": {"name": "disabled"}},
                  "values": {
                    "enabled": {"enabled": true, "color": "blue", "_createdAt": "created", "_updatedAt": "updated"},
                    "disabled": {"enabled": false, "secret": "must-not-leak"}
                  },
                  "version": "1"
                }
                """.getBytes(StandardCharsets.UTF_8);

        byte[] retrievalContent = AppConfigDataService.transformFeatureFlags(deploymentContent, MAPPER);

        assertEquals(MAPPER.readTree("""
                {
                  "enabled": {"enabled": true, "color": "blue"},
                  "disabled": {"enabled": false}
                }
                """), MAPPER.readTree(retrievalContent));
    }

    @Test
    void transformFeatureFlagsPreservesUnsupportedMultiVariantAndMalformedContent() {
        byte[] multiVariantContent = "{\"values\":{\"flag\":{\"enabled\":true,\"_variants\":[]}}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] malformedContent = "{not-json".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(multiVariantContent,
                AppConfigDataService.transformFeatureFlags(multiVariantContent, MAPPER));
        assertArrayEquals(malformedContent,
                AppConfigDataService.transformFeatureFlags(malformedContent, MAPPER));
    }
}
