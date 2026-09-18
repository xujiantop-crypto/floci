package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationSession;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AppConfigDataService {
    private static final Logger LOG = Logger.getLogger(AppConfigDataService.class);

    private final StorageBackend<String, ConfigurationSession> sessionStore;
    private final AppConfigService appConfigService;
    private final ObjectMapper objectMapper;

    @Inject
    public AppConfigDataService(StorageFactory storageFactory, AppConfigService appConfigService,
                                ObjectMapper objectMapper) {
        this.sessionStore = storageFactory.create("appconfigdata", "appconfigdata-sessions.json", new TypeReference<>() {});
        this.appConfigService = appConfigService;
        this.objectMapper = objectMapper;
    }

    public String startConfigurationSession(Map<String, Object> request) {
        String appId = (String) request.get("ApplicationIdentifier");
        String envId = (String) request.get("EnvironmentIdentifier");
        String profileId = (String) request.get("ConfigurationProfileIdentifier");

        // Validate resources exist
        appConfigService.getEnvironment(appId, envId);
        appConfigService.getConfigurationProfile(appId, profileId);

        ConfigurationSession session = new ConfigurationSession();
        session.setId(UUID.randomUUID().toString());
        session.setApplicationId(appId);
        session.setEnvironmentId(envId);
        session.setConfigurationProfileId(profileId);
        int pollInterval = parsePollInterval(request.getOrDefault("RequiredMinimumPollIntervalInSeconds", 15));
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);
        session.setCurrentToken(UUID.randomUUID().toString());

        sessionStore.put(session.getCurrentToken(), session);
        LOG.infov("Started AppConfigData session {0} for app {1}, env {2}, profile {3}", session.getId(), appId, envId, profileId);
        return session.getCurrentToken();
    }

    private static int parsePollInterval(Object value) {
        if (!(value instanceof Number)) {
            throw invalidPollInterval();
        }
        try {
            long interval = new BigDecimal(value.toString()).longValueExact();
            if (interval < 15 || interval > 86400) {
                throw invalidPollInterval();
            }
            return (int) interval;
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalidPollInterval();
        }
    }

    private static AwsException invalidPollInterval() {
        return new AwsException("BadRequestException",
                "RequiredMinimumPollIntervalInSeconds must be an integer between 15 and 86400", 400);
    }

    public ConfigurationData getLatestConfiguration(String token) {
        ConfigurationSession session = sessionStore.get(token)
                .orElseThrow(() -> new AwsException("BadRequestException", "Invalid configuration token", 400));

        int pollInterval = normalizePollInterval(session.getRequiredMinimumPollIntervalInSeconds());
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);

        String activeVersion = appConfigService.getActiveVersion(session.getEnvironmentId(), session.getConfigurationProfileId());
        
        HostedConfigurationVersion version = null;
        if (activeVersion != null && !activeVersion.equals(session.getLastConfigurationVersion())) {
            try {
                version = appConfigService.getHostedConfigurationVersion(session.getApplicationId(), session.getConfigurationProfileId(), Integer.parseInt(activeVersion));
                session.setLastConfigurationVersion(activeVersion);
            } catch (Exception e) {
                LOG.warnv("Active version {0} not found for session {1}", activeVersion, session.getId());
            }
        }

        // Generate next token
        String nextToken = UUID.randomUUID().toString();
        session.setCurrentToken(nextToken);
        sessionStore.delete(token); // Old token is invalid
        sessionStore.put(nextToken, session);

        byte[] content = (version != null) ? resolveContent(session, version) : new byte[0];
        String contentType = (version != null) ? version.getContentType() : "application/octet-stream";
        String versionLabel = (version != null) ? String.valueOf(version.getVersionNumber()) : "";

        return new ConfigurationData(content, contentType, versionLabel, nextToken,
                pollInterval);
    }

    private byte[] resolveContent(ConfigurationSession session, HostedConfigurationVersion version) {
        ConfigurationProfile profile = appConfigService.getConfigurationProfile(
                session.getApplicationId(), session.getConfigurationProfileId());
        if (!"AWS.AppConfig.FeatureFlags".equals(profile.getType())) {
            return version.getContent();
        }
        return transformFeatureFlags(version.getContent(), objectMapper);
    }

    static byte[] transformFeatureFlags(byte[] content, ObjectMapper objectMapper) {
        try {
            JsonNode document = objectMapper.readTree(content);
            JsonNode values = document == null ? null : document.get("values");
            if (values == null || !values.isObject()) {
                return content;
            }

            ObjectNode retrieval = objectMapper.createObjectNode();
            var fields = values.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode definition = entry.getValue();
                JsonNode enabled = definition.get("enabled");
                if (!definition.isObject() || enabled == null || !enabled.isBoolean()
                        || definition.has("_variants")) {
                    // Multi-variant profiles use Amazon Ion at retrieval time. Do not emit a
                    // partially converted JSON document for a format this service does not yet
                    // evaluate.
                    return content;
                }

                if (!enabled.booleanValue()) {
                    retrieval.putObject(entry.getKey()).put("enabled", false);
                    continue;
                }

                ObjectNode flag = (ObjectNode) definition.deepCopy();
                flag.remove("_createdAt");
                flag.remove("_updatedAt");
                retrieval.set(entry.getKey(), flag);
            }
            return objectMapper.writeValueAsBytes(retrieval);
        } catch (IOException | RuntimeException e) {
            // Hosted versions are stored as opaque bytes. Preserve that behavior for malformed
            // legacy content instead of turning a data-plane poll into an unexpected 500.
            return content;
        }
    }

    static int normalizePollInterval(int interval) {
        return interval >= 15 && interval <= 86400 ? interval : 15;
    }

    public record ConfigurationData(byte[] content, String contentType, String configurationVersion,
                                    String nextPollConfigurationToken, int nextPollIntervalInSeconds) {}
}
