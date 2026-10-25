package parking.forecast.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import parking.forecast.domain.EligibilityStatus;

@Component
public final class ExperimentalOffStreetPredictionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExperimentalOffStreetPredictionService.class);
    private static final String MODEL_DIRECTORY = "classpath:parking/forecast/models/experimental/";
    private static final int MAX_INPUT_AGE_SECONDS = 15 * 60;
    private static final long LEAD_TOLERANCE_SECONDS = 15 * 60;
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final String EXPERIMENTAL_MESSAGE =
            "Experimental off-street estimate trained from offline collector data; not a validated production probability.";

    private final boolean enabled;
    private final Map<Integer, ModelArtifact> models;

    @Autowired
    public ExperimentalOffStreetPredictionService(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${parking.predictions.experimental.enabled:false}") boolean enabled
    ) {
        this.enabled = enabled;
        this.models = loadModels(objectMapper, resourceLoader);
    }

    private ExperimentalOffStreetPredictionService(boolean enabled, Map<Integer, ModelArtifact> models) {
        this.enabled = enabled;
        this.models = models;
    }

    static ExperimentalOffStreetPredictionService disabled() {
        return new ExperimentalOffStreetPredictionService(false, Map.of());
    }

    public PredictionResult predict(PredictionInput input) {
        if (input == null) {
            return unavailable(null, "MODEL_INPUT_NOT_READY", "Prediction inputs are incomplete.");
        }
        if (input.generatedAt() == null) {
            return unavailable(input, "PREDICTION_TIME_MISSING", "The prediction timestamp is missing.");
        }
        if (!"READY".equals(input.etaStatus()) || input.targetArrivalAt() == null) {
            return unavailable(input, "ETA_NOT_READY", "A current ETA is required for experimental prediction.");
        }
        if (!input.targetArrivalAt().isAfter(input.generatedAt())) {
            return unavailable(input, "ETA_EXPIRED", "The target arrival time has already passed.");
        }
        if (!"READY".equals(input.sourceStatus()) && !"PARTIAL".equals(input.sourceStatus())) {
            return unavailable(input, sourceReason(input.sourceStatus()), "A published current off-street observation is required.");
        }
        if (input.snapshotAt() == null || input.generatedAt() == null) {
            return unavailable(input, "SNAPSHOT_TIME_MISSING", "The current snapshot timestamp is missing.");
        }

        long ageSeconds = Duration.between(input.snapshotAt(), input.generatedAt()).getSeconds();
        if (ageSeconds < 0 || ageSeconds > MAX_INPUT_AGE_SECONDS) {
            return unavailable(input, "EXPERIMENTAL_INPUT_TOO_OLD", "Experimental inference requires an observation no older than 15 minutes.");
        }

        String state = availabilityState(input.availabilityStatus(), input.availableCarSpaces(), input.totalCarSpaces());
        if (state == null) {
            return unavailable(input, availabilityReason(input.availabilityStatus()), "The current car-space observation is not usable as a model input.");
        }
        if (!enabled) {
            return unavailable(input, "EXPERIMENTAL_MODEL_DISABLED", "Experimental prediction is disabled.");
        }
        if (input.carEligibility() != EligibilityStatus.CONFIRMED) {
            return unavailable(input, "CAR_ELIGIBILITY_NOT_CONFIRMED", "General-car eligibility is not confirmed.");
        }

        long leadSeconds = Duration.between(input.snapshotAt(), input.targetArrivalAt()).getSeconds();
        ModelArtifact model = nearestLeadModel(leadSeconds);
        if (model == null) {
            return unavailable(input, "MODEL_HORIZON_UNSUPPORTED", "Only arrivals within 15 minutes of the 30- or 60-minute model horizons are supported.");
        }

        double probability = model.stateProbabilities().getOrDefault(state, model.fallbackProbability());
        return new PredictionResult(
                "EXPERIMENTAL",
                probability,
                model.version(),
                input.inputSnapshotVersion(),
                (int) ageSeconds,
                List.of("EXPERIMENTAL_MODEL", "OFFLINE_COLLECTOR_TRAINING", "NOT_PRODUCTION_VALIDATED"),
                EXPERIMENTAL_MESSAGE
        );
    }

    private ModelArtifact nearestLeadModel(long leadSeconds) {
        ModelArtifact nearest = null;
        long nearestDifference = Long.MAX_VALUE;
        for (Map.Entry<Integer, ModelArtifact> entry : models.entrySet()) {
            long supportedLeadSeconds = entry.getKey() * 60L;
            long difference = Math.abs(leadSeconds - supportedLeadSeconds);
            boolean isCloser = difference < nearestDifference;
            boolean shorterModelWinsTie = difference == nearestDifference
                    && nearest != null
                    && entry.getKey() < nearest.leadMinutes();
            if (difference <= LEAD_TOLERANCE_SECONDS && (isCloser || shorterModelWinsTie)) {
                nearest = entry.getValue();
                nearestDifference = difference;
            }
        }
        return nearest;
    }

    private String availabilityState(String status, Integer available, Integer total) {
        if (total == null || total <= 0 || available == null || available < 0 || available > total) {
            return null;
        }
        if ("AVAILABLE".equals(status) && available > 0) {
            return "available";
        }
        if ("FULL".equals(status) && available == 0) {
            return "unavailable";
        }
        return null;
    }

    private String sourceReason(String status) {
        return switch (status == null ? "" : status) {
            case "STALE" -> "REALTIME_SNAPSHOT_EXPIRED";
            case "NO_REALTIME_SNAPSHOT" -> "NO_REALTIME_SNAPSHOT";
            case "NO_OBSERVATION" -> "NO_OBSERVATION";
            case "UNKNOWN" -> "UNKNOWN_AVAILABILITY";
            default -> "REALTIME_SNAPSHOT_NOT_READY";
        };
    }

    private String availabilityReason(String status) {
        return switch (status == null ? "" : status) {
            case "STALE" -> "REALTIME_SNAPSHOT_EXPIRED";
            case "NO_REALTIME_SNAPSHOT" -> "NO_REALTIME_SNAPSHOT";
            case "NO_OBSERVATION" -> "NO_OBSERVATION";
            case "UNKNOWN" -> "UNKNOWN_AVAILABILITY";
            default -> "CURRENT_AVAILABILITY_NOT_CONFIRMED";
        };
    }

    private PredictionResult unavailable(PredictionInput input, String reason, String message) {
        Integer ageSeconds = null;
        if (input != null && input.snapshotAt() != null && input.generatedAt() != null) {
            long age = Duration.between(input.snapshotAt(), input.generatedAt()).getSeconds();
            if (age >= 0 && age <= Integer.MAX_VALUE) {
                ageSeconds = (int) age;
            }
        }
        List<String> reasons = new java.util.ArrayList<>(List.of(
                reason,
                "BACKTEST_NOT_VERIFIED",
                "EXPERIMENTAL_MODEL_NOT_PRODUCTION_VALIDATED"
        ));
        if (models.isEmpty()) {
            reasons.add("MODEL_NOT_LOADED");
        }
        return new PredictionResult(
                "INSUFFICIENT_DATA",
                null,
                null,
                input == null ? null : input.inputSnapshotVersion(),
                ageSeconds,
                List.copyOf(reasons),
                message
        );
    }

    private Map<Integer, ModelArtifact> loadModels(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        Map<Integer, ModelArtifact> loaded = new HashMap<>();
        for (int leadMinutes : List.of(30, 60)) {
            String path = MODEL_DIRECTORY + "offstreet-lead-" + leadMinutes
                    + "m-calibrated-persistence-experiment-v1.json";
            Resource resource = resourceLoader.getResource(path);
            try {
                if (!resource.exists()) {
                    LOGGER.warn("Experimental off-street model artifact is missing: {}", path);
                    continue;
                }
                byte[] bytes;
                try (var inputStream = resource.getInputStream()) {
                    bytes = inputStream.readAllBytes();
                }
                JsonNode root = objectMapper.readTree(bytes);
                ModelArtifact artifact = parseArtifact(root, leadMinutes, bytes);
                loaded.put(leadMinutes, artifact);
            } catch (IOException | IllegalArgumentException exception) {
                LOGGER.warn("Experimental off-street model artifact was rejected: {}", path, exception);
            }
        }
        return Map.copyOf(loaded);
    }

    private ModelArtifact parseArtifact(JsonNode root, int expectedLeadMinutes, byte[] bytes) {
        require(root != null, "artifact_empty");
        require("parking-calibrated-persistence-experiment-v1".equals(text(root, "schemaVersion")), "schema_mismatch");
        require("offstreet_current_state_v1".equals(text(root, "featureVersion")), "feature_version_mismatch");
        require("EXPERIMENT_ONLY".equals(text(root, "status")), "status_mismatch");
        require(root.path("publishToOnlineSystem").isBoolean() && !root.path("publishToOnlineSystem").asBoolean(), "publication_policy_mismatch");
        require("offstreet".equals(text(root, "dataset")), "dataset_mismatch");
        require("OFFSTREET".equals(text(root, "coveragePolicyVersion")), "coverage_policy_mismatch");
        require("arrival_has_confirmed_availability".equals(text(root, "target")), "target_mismatch");
        require(root.path("leadMinutes").asInt(-1) == expectedLeadMinutes, "lead_mismatch");
        require(root.path("trainingSamples").asInt(0) >= 500, "training_sample_gate_failed");
        require(SHA_256.matcher(text(root, "sourceZipSha256")).matches(), "source_hash_missing");
        require(SHA_256.matcher(text(root, "trainingConfigSha256")).matches(), "config_hash_missing");

        JsonNode calibration = root.path("calibrationParameters");
        Map<String, Double> stateProbabilities = new HashMap<>();
        JsonNode stateNode = calibration.path("stateProbabilities");
        for (String state : List.of("available", "unavailable")) {
            double probability = stateNode.path(state).asDouble(Double.NaN);
            require(Double.isFinite(probability) && probability >= 0.0 && probability <= 1.0, "state_probability_invalid");
            stateProbabilities.put(state, probability);
        }
        double fallback = calibration.path("fallbackProbability").asDouble(Double.NaN);
        require(Double.isFinite(fallback) && fallback >= 0.0 && fallback <= 1.0, "fallback_probability_invalid");

        String modelId = text(root, "modelId");
        require(!modelId.isBlank(), "model_id_missing");
        return new ModelArtifact(
                modelId,
                modelId + "+sha256:" + sha256(bytes).substring(0, 12),
                expectedLeadMinutes,
                fallback,
                Map.copyOf(stateProbabilities)
        );
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record PredictionInput(
            EligibilityStatus carEligibility,
            String availabilityStatus,
            Integer availableCarSpaces,
            Integer totalCarSpaces,
            String sourceStatus,
            String etaStatus,
            OffsetDateTime snapshotAt,
            OffsetDateTime generatedAt,
            OffsetDateTime targetArrivalAt,
            String inputSnapshotVersion
    ) {
    }

    public record PredictionResult(
            String status,
            Double probability,
            String modelVersion,
            String inputSnapshotVersion,
            Integer dataAgeSeconds,
            List<String> reasons,
            String message
    ) {
    }

    private record ModelArtifact(
            String modelId,
            String version,
            int leadMinutes,
            double fallbackProbability,
            Map<String, Double> stateProbabilities
    ) {
    }
}
