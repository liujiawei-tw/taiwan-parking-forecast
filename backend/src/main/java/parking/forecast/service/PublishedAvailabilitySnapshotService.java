package parking.forecast.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.domain.SourceStateEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.repository.AvailabilityObservationRepository;
import parking.forecast.repository.SourceStateRepository;

@Service
public class PublishedAvailabilitySnapshotService {

    public static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    private static final SourceSystem USER_QUERY_SOURCE = SourceSystem.NTPC_OPEN_DATA;

    private final SourceStateRepository sourceStateRepository;
    private final AvailabilityObservationRepository observationRepository;
    private final Clock clock;

    @Autowired
    public PublishedAvailabilitySnapshotService(
            SourceStateRepository sourceStateRepository,
            AvailabilityObservationRepository observationRepository
    ) {
        this(sourceStateRepository, observationRepository, Clock.systemUTC());
    }

    PublishedAvailabilitySnapshotService(
            SourceStateRepository sourceStateRepository,
            AvailabilityObservationRepository observationRepository,
            Clock clock
    ) {
        this.sourceStateRepository = sourceStateRepository;
        this.observationRepository = observationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublishedAvailabilityView findLatest(DatasetCode datasetCode, Collection<Long> parkingPlaceIds) {
        if (parkingPlaceIds.isEmpty()) {
            return PublishedAvailabilityView.noCatalog(datasetCode);
        }

        return sourceStateRepository.findBySourceSystemAndDatasetCode(USER_QUERY_SOURCE, datasetCode)
                .map(state -> toView(datasetCode, parkingPlaceIds, state))
                .orElseGet(() -> PublishedAvailabilityView.noPublishedSnapshot(datasetCode));
    }

    private PublishedAvailabilityView toView(
            DatasetCode datasetCode,
            Collection<Long> parkingPlaceIds,
            SourceStateEntity state
    ) {
        AvailabilitySnapshotEntity snapshot = state.getActiveSnapshot();
        if (snapshot == null || snapshot.getSnapshotStatus() != SnapshotStatus.PUBLISHED) {
            return PublishedAvailabilityView.noPublishedSnapshot(datasetCode);
        }

        List<AvailabilityObservationEntity> observations = observationRepository
                .findBySnapshotAndParkingPlace_IdIn(snapshot, parkingPlaceIds);
        Map<Long, PublishedObservation> observationMap = observations.stream()
                .map(this::toObservation)
                .collect(Collectors.toMap(
                        PublishedObservation::parkingPlaceId,
                        Function.identity(),
                        (left, ignored) -> left
                ));

        OffsetDateTime systemCollectedAt = toOffsetDateTime(snapshot.getSystemFetchedAt());
        OffsetDateTime sourceObservedAt = toOffsetDateTime(snapshot.getSourceObservedAt());
        boolean stale = isStale(snapshot.getSystemFetchedAt());
        String status = resolveStatus(stale, observationMap);
        String message = resolveMessage(status);
        return new PublishedAvailabilityView(
                datasetCode,
                status,
                systemCollectedAt,
                sourceObservedAt,
                observationMap,
                message
        );
    }

    private PublishedObservation toObservation(AvailabilityObservationEntity entity) {
        return new PublishedObservation(
                entity.getParkingPlace().getId(),
                entity.getAvailabilityStatus(),
                entity.getAvailableCarSpaces(),
                entity.getAvailableSpot(),
                toOffsetDateTime(entity.getSystemFetchedAt()),
                toOffsetDateTime(entity.getSourceObservedAt()),
                entity.getQualityStatus(),
                entity.getQualityReason()
        );
    }

    private boolean isStale(Instant systemFetchedAt) {
        return systemFetchedAt == null || systemFetchedAt.plus(STALE_THRESHOLD).isBefore(clock.instant());
    }

    private String resolveStatus(boolean stale, Map<Long, PublishedObservation> observationMap) {
        if (stale) {
            return "STALE";
        }
        if (observationMap.isEmpty()) {
            return "NO_OBSERVATION";
        }
        boolean hasUsableObservation = observationMap.values().stream()
                .anyMatch(observation -> observation.availabilityStatus() != AvailabilityStatus.UNKNOWN
                        && observation.qualityStatus() != QualityStatus.FAIL);
        return hasUsableObservation ? "READY" : "UNKNOWN";
    }

    private String resolveMessage(String status) {
        return switch (status) {
            case "STALE" -> "Latest published snapshot is older than 5 minutes; current availability is not treated as fresh.";
            case "NO_OBSERVATION" -> "A published snapshot exists, but no matching observation was found for nearby catalog records.";
            case "UNKNOWN" -> "Published snapshot contains only unknown or failed observations for nearby records.";
            default -> "Latest published snapshot is available for nearby records.";
        };
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record PublishedAvailabilityView(
            DatasetCode datasetCode,
            String status,
            OffsetDateTime systemCollectedAt,
            OffsetDateTime sourceObservedAt,
            Map<Long, PublishedObservation> observations,
            String message
    ) {
        static PublishedAvailabilityView noCatalog(DatasetCode datasetCode) {
            return new PublishedAvailabilityView(
                    datasetCode,
                    "NO_CATALOG_RESULTS",
                    null,
                    null,
                    Map.of(),
                    "No catalog records were found inside the query radius."
            );
        }

        static PublishedAvailabilityView noPublishedSnapshot(DatasetCode datasetCode) {
            return new PublishedAvailabilityView(
                    datasetCode,
                    "NO_REALTIME_SNAPSHOT",
                    null,
                    null,
                    Map.of(),
                    "No published real-time snapshot is available yet."
            );
        }

        public boolean isFreshReady() {
            return "READY".equals(status);
        }
    }

    public record PublishedObservation(
            Long parkingPlaceId,
            AvailabilityStatus availabilityStatus,
            Integer availableCarSpaces,
            Boolean availableSpot,
            OffsetDateTime systemFetchedAt,
            OffsetDateTime sourceObservedAt,
            QualityStatus qualityStatus,
            String qualityReason
    ) {
    }
}
