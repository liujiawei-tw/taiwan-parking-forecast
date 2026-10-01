package parking.forecast.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.AvailabilityObservationEntity;
import parking.forecast.domain.AvailabilitySnapshotEntity;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SnapshotStatus;
import parking.forecast.repository.AvailabilityObservationRepository;

@Service
public class TrainingLabelDryRunService {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final AvailabilityObservationRepository observationRepository;

    public TrainingLabelDryRunService(AvailabilityObservationRepository observationRepository) {
        this.observationRepository = observationRepository;
    }

    @Transactional(readOnly = true)
    public LabelDryRunReport dryRunRoadsideArea(RoadsideAreaDryRunCommand command) {
        command.validate();
        List<AvailabilityObservationEntity> observations = fetchPublishedObservations(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                command.fetchedFromInclusive(),
                command.fetchedToExclusive()
        );
        List<LabelCandidate> candidates = observations.stream()
                .collect(Collectors.groupingBy(
                        AvailabilityObservationEntity::getSnapshot,
                        Collectors.collectingAndThen(Collectors.toList(), snapshotObservations -> toRoadsideCandidate(command, snapshotObservations))
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(LabelCandidate::sampleTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return buildReport(LabelTarget.ROADSIDE_AREA, observations, candidates);
    }

    @Transactional(readOnly = true)
    public LabelDryRunReport dryRunOffStreetLots(OffStreetLotDryRunCommand command) {
        command.validate();
        List<AvailabilityObservationEntity> observations = fetchPublishedObservations(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                command.fetchedFromInclusive(),
                command.fetchedToExclusive()
        );
        List<LabelCandidate> candidates = observations.stream()
                .map(observation -> toOffStreetCandidate(command, observation))
                .sorted(Comparator.comparing(LabelCandidate::sampleTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(LabelCandidate::parkingPlaceId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return buildReport(LabelTarget.OFF_STREET_LOT, observations, candidates);
    }

    private List<AvailabilityObservationEntity> fetchPublishedObservations(
            DatasetCode datasetCode,
            Instant fetchedFromInclusive,
            Instant fetchedToExclusive
    ) {
        return observationRepository.findBySnapshot_SnapshotStatusAndDatasetCodeAndSystemFetchedAtGreaterThanEqualAndSystemFetchedAtLessThan(
                SnapshotStatus.PUBLISHED,
                datasetCode,
                fetchedFromInclusive,
                fetchedToExclusive
        );
    }

    private LabelCandidate toRoadsideCandidate(
            RoadsideAreaDryRunCommand command,
            List<AvailabilityObservationEntity> snapshotObservations
    ) {
        AvailabilitySnapshotEntity snapshot = snapshotObservations.getFirst().getSnapshot();
        Instant sampleTime = sampleTime(snapshot, snapshotObservations);
        List<AvailabilityObservationEntity> inRadius = snapshotObservations.stream()
                .filter(observation -> observation.getParkingPlace().getPlaceType() == ParkingPlaceType.ON_STREET_SPOT)
                .filter(observation -> command.serviceArea().equals(observation.getParkingPlace().getServiceArea()))
                .filter(observation -> isActiveCarEligible(observation.getParkingPlace()))
                .filter(observation -> distanceMeters(
                        command.destinationLatitude(),
                        command.destinationLongitude(),
                        observation.getParkingPlace()) <= command.radiusMeters())
                .toList();

        if (command.coverageStatus() != CoverageStatus.COMPLETE) {
            return rejectedAreaCandidate(sampleTime, inRadius, LabelRejectReason.CATALOG_COVERAGE_PARTIAL);
        }
        if (inRadius.isEmpty()) {
            return rejectedAreaCandidate(sampleTime, inRadius, LabelRejectReason.NO_OBSERVATIONS_IN_RADIUS);
        }

        List<AvailabilityObservationEntity> valid = inRadius.stream()
                .filter(observation -> isFresh(sampleTime, observation, command.maxObservationAge()))
                .filter(TrainingLabelDryRunService::isUsableQuality)
                .filter(TrainingLabelDryRunService::isRoadsideKnownStatus)
                .toList();
        double validRatio = ratio(valid.size(), inRadius.size());
        if (validRatio < command.minimumValidObservationRatio()) {
            return rejectedAreaCandidate(sampleTime, inRadius, LabelRejectReason.INSUFFICIENT_VALID_OBSERVATION_RATIO);
        }

        boolean positive = valid.stream().anyMatch(TrainingLabelDryRunService::isAvailable);
        return new LabelCandidate(
                sampleTime,
                null,
                true,
                positive,
                inRadius.size(),
                valid.size(),
                validRatio,
                null
        );
    }

    private LabelCandidate rejectedAreaCandidate(
            Instant sampleTime,
            List<AvailabilityObservationEntity> observations,
            LabelRejectReason reason
    ) {
        return new LabelCandidate(sampleTime, null, false, null, observations.size(), 0, 0.0, reason);
    }

    private LabelCandidate toOffStreetCandidate(
            OffStreetLotDryRunCommand command,
            AvailabilityObservationEntity observation
    ) {
        ParkingPlaceEntity place = observation.getParkingPlace();
        Instant sampleTime = sampleTime(observation.getSnapshot(), List.of(observation));
        if (place.getPlaceType() != ParkingPlaceType.OFF_STREET_LOT) {
            return rejectedLotCandidate(sampleTime, place.getId(), LabelRejectReason.PLACE_TYPE_MISMATCH);
        }
        if (!isActiveCarEligible(place)) {
            return rejectedLotCandidate(sampleTime, place.getId(), LabelRejectReason.NOT_CAR_ELIGIBLE);
        }
        if (!isFresh(sampleTime, observation, command.maxObservationAge())) {
            return rejectedLotCandidate(sampleTime, place.getId(), LabelRejectReason.OBSERVATION_STALE);
        }
        if (!isUsableQuality(observation)) {
            return rejectedLotCandidate(sampleTime, place.getId(), LabelRejectReason.QUALITY_NOT_USABLE);
        }
        if (!isOffStreetKnownStatus(observation)) {
            return rejectedLotCandidate(sampleTime, place.getId(), LabelRejectReason.UNKNOWN_AVAILABILITY);
        }

        return new LabelCandidate(
                sampleTime,
                place.getId(),
                true,
                isAvailable(observation),
                1,
                1,
                1.0,
                null
        );
    }

    private LabelCandidate rejectedLotCandidate(Instant sampleTime, Long parkingPlaceId, LabelRejectReason reason) {
        return new LabelCandidate(sampleTime, parkingPlaceId, false, null, 1, 0, 0.0, reason);
    }

    private LabelDryRunReport buildReport(
            LabelTarget target,
            List<AvailabilityObservationEntity> observations,
            List<LabelCandidate> candidates
    ) {
        Map<LabelRejectReason, Long> rejectionReasons = new EnumMap<>(LabelRejectReason.class);
        for (LabelCandidate candidate : candidates) {
            if (candidate.rejectReason() != null) {
                rejectionReasons.merge(candidate.rejectReason(), 1L, Long::sum);
            }
        }
        long usable = candidates.stream().filter(LabelCandidate::usable).count();
        long positive = candidates.stream().filter(candidate -> Boolean.TRUE.equals(candidate.positiveLabel())).count();
        Instant firstSample = candidates.stream()
                .map(LabelCandidate::sampleTime)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        Instant lastSample = candidates.stream()
                .map(LabelCandidate::sampleTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);

        long uniqueParkingPlaces = observations.stream()
                .map(AvailabilityObservationEntity::getParkingPlace)
                .map(ParkingPlaceEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        return new LabelDryRunReport(
                target,
                observations.size(),
                uniqueParkingPlaces,
                candidates.size(),
                usable,
                candidates.size() - usable,
                ratio(usable, candidates.size()),
                positive,
                firstSample,
                lastSample,
                Map.copyOf(rejectionReasons),
                candidates
        );
    }

    private Instant sampleTime(AvailabilitySnapshotEntity snapshot, List<AvailabilityObservationEntity> observations) {
        if (snapshot.getSystemFetchedAt() != null) {
            return snapshot.getSystemFetchedAt();
        }
        return observations.stream()
                .map(AvailabilityObservationEntity::getSystemFetchedAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private static boolean isActiveCarEligible(ParkingPlaceEntity place) {
        return place.getActiveStatus() == ActiveStatus.ACTIVE
                && place.getCarEligibleStatus() == EligibilityStatus.CONFIRMED;
    }

    private static boolean isFresh(
            Instant sampleTime,
            AvailabilityObservationEntity observation,
            Duration maxObservationAge
    ) {
        if (sampleTime == null || observation.getSystemFetchedAt() == null) {
            return false;
        }
        Duration age = Duration.between(observation.getSystemFetchedAt(), sampleTime);
        return !age.isNegative() && age.compareTo(maxObservationAge) <= 0;
    }

    private static boolean isUsableQuality(AvailabilityObservationEntity observation) {
        return observation.getQualityStatus() == QualityStatus.PASS
                || observation.getQualityStatus() == QualityStatus.PARTIAL_PASS;
    }

    private static boolean isRoadsideKnownStatus(AvailabilityObservationEntity observation) {
        return observation.getAvailabilityStatus() == AvailabilityStatus.AVAILABLE
                || observation.getAvailabilityStatus() == AvailabilityStatus.OCCUPIED;
    }

    private static boolean isOffStreetKnownStatus(AvailabilityObservationEntity observation) {
        return observation.getAvailabilityStatus() == AvailabilityStatus.AVAILABLE
                || observation.getAvailabilityStatus() == AvailabilityStatus.FULL
                || observation.getAvailableCarSpaces() != null && observation.getAvailableCarSpaces() >= 0;
    }

    private static boolean isAvailable(AvailabilityObservationEntity observation) {
        if (Boolean.TRUE.equals(observation.getAvailableSpot())) {
            return true;
        }
        if (observation.getAvailableCarSpaces() != null) {
            return observation.getAvailableCarSpaces() > 0;
        }
        return observation.getAvailabilityStatus() == AvailabilityStatus.AVAILABLE;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / (double) denominator;
    }

    private double distanceMeters(double originLatitude, double originLongitude, ParkingPlaceEntity place) {
        double targetLatitude = place.getLatitude().doubleValue();
        double targetLongitude = place.getLongitude().doubleValue();
        double originLatitudeRadians = Math.toRadians(originLatitude);
        double targetLatitudeRadians = Math.toRadians(targetLatitude);
        double deltaLatitude = Math.toRadians(targetLatitude - originLatitude);
        double deltaLongitude = Math.toRadians(targetLongitude - originLongitude);

        double haversine = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(originLatitudeRadians) * Math.cos(targetLatitudeRadians)
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    public enum LabelTarget {
        ROADSIDE_AREA,
        OFF_STREET_LOT
    }

    public enum CoverageStatus {
        COMPLETE,
        PARTIAL,
        UNKNOWN
    }

    public enum LabelRejectReason {
        CATALOG_COVERAGE_PARTIAL,
        NO_OBSERVATIONS_IN_RADIUS,
        INSUFFICIENT_VALID_OBSERVATION_RATIO,
        PLACE_TYPE_MISMATCH,
        NOT_CAR_ELIGIBLE,
        OBSERVATION_STALE,
        QUALITY_NOT_USABLE,
        UNKNOWN_AVAILABILITY
    }

    public record RoadsideAreaDryRunCommand(
            String serviceArea,
            double destinationLatitude,
            double destinationLongitude,
            int radiusMeters,
            Instant fetchedFromInclusive,
            Instant fetchedToExclusive,
            Duration maxObservationAge,
            double minimumValidObservationRatio,
            CoverageStatus coverageStatus
    ) {
        public void validate() {
            validateCommon(fetchedFromInclusive, fetchedToExclusive, maxObservationAge);
            if (serviceArea == null || serviceArea.isBlank()) {
                throw new IllegalArgumentException("serviceArea is required");
            }
            if (destinationLatitude < -90.0 || destinationLatitude > 90.0) {
                throw new IllegalArgumentException("destinationLatitude must be between -90 and 90");
            }
            if (destinationLongitude < -180.0 || destinationLongitude > 180.0) {
                throw new IllegalArgumentException("destinationLongitude must be between -180 and 180");
            }
            if (radiusMeters <= 0) {
                throw new IllegalArgumentException("radiusMeters must be greater than 0");
            }
            if (minimumValidObservationRatio < 0.0 || minimumValidObservationRatio > 1.0) {
                throw new IllegalArgumentException("minimumValidObservationRatio must be between 0 and 1");
            }
            if (coverageStatus == null) {
                throw new IllegalArgumentException("coverageStatus is required");
            }
        }
    }

    public record OffStreetLotDryRunCommand(
            Instant fetchedFromInclusive,
            Instant fetchedToExclusive,
            Duration maxObservationAge
    ) {
        public void validate() {
            validateCommon(fetchedFromInclusive, fetchedToExclusive, maxObservationAge);
        }
    }

    public record LabelDryRunReport(
            LabelTarget target,
            long inputObservations,
            long uniqueParkingPlaces,
            long candidateSamples,
            long usableSamples,
            long rejectedSamples,
            double usableRatio,
            long positiveLabels,
            Instant firstSampleAt,
            Instant lastSampleAt,
            Map<LabelRejectReason, Long> rejectionReasons,
            List<LabelCandidate> candidates
    ) {
        public LabelDryRunReport {
            rejectionReasons = rejectionReasons == null ? Map.of() : Map.copyOf(rejectionReasons);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record LabelCandidate(
            Instant sampleTime,
            Long parkingPlaceId,
            boolean usable,
            Boolean positiveLabel,
            long observedPlaces,
            long validObservations,
            double validObservationRatio,
            LabelRejectReason rejectReason
    ) {
    }

    private static void validateCommon(
            Instant fetchedFromInclusive,
            Instant fetchedToExclusive,
            Duration maxObservationAge
    ) {
        if (fetchedFromInclusive == null || fetchedToExclusive == null) {
            throw new IllegalArgumentException("fetched time window is required");
        }
        if (!fetchedFromInclusive.isBefore(fetchedToExclusive)) {
            throw new IllegalArgumentException("fetchedFromInclusive must be before fetchedToExclusive");
        }
        if (maxObservationAge == null || maxObservationAge.isNegative() || maxObservationAge.isZero()) {
            throw new IllegalArgumentException("maxObservationAge must be positive");
        }
    }
}
