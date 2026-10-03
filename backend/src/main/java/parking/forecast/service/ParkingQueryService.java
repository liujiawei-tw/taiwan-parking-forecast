package parking.forecast.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import parking.forecast.config.ParkingQueryStateProperties;
import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.ParkingQueryResponse;
import parking.forecast.controller.dto.ParkingQueryResponse.AreaSection;
import parking.forecast.controller.dto.ParkingQueryResponse.DestinationSection;
import parking.forecast.controller.dto.ParkingQueryResponse.EtaSection;
import parking.forecast.controller.dto.ParkingQueryResponse.OffStreetPrediction;
import parking.forecast.controller.dto.ParkingQueryResponse.OffStreetSection;
import parking.forecast.controller.dto.ParkingQueryResponse.ParkingLot;
import parking.forecast.controller.dto.ParkingQueryResponse.PredictionsSection;
import parking.forecast.controller.dto.ParkingQueryResponse.QuerySection;
import parking.forecast.controller.dto.ParkingQueryResponse.RefreshSection;
import parking.forecast.controller.dto.ParkingQueryResponse.RoadsidePrediction;
import parking.forecast.controller.dto.ParkingQueryResponse.RoadsideSection;
import parking.forecast.controller.dto.ParkingQueryResponse.RoadsideSpot;
import parking.forecast.controller.dto.ParkingQueryResponse.SourceRefresh;
import parking.forecast.domain.AvailabilityStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.TriggerType;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingPlace;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchRequest;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchResult;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedAvailabilityView;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedObservation;
import parking.forecast.service.GoogleEtaService.EtaResult;
import parking.forecast.service.ParkingQueryStateStore.StoredParkingQuery;
import parking.forecast.service.SourceRefreshCoordinationService.SourceRefreshResult;

@Service
public class ParkingQueryService {

    private static final String MVP_SERVICE_AREA = "NEW_TAIPEI_XINZHUANG";
    private static final String MVP_AREA_CODE = "NEW_TAIPEI_XINZHUANG_MVP";
    private static final String LOCAL_CATALOG_SOURCE = "LOCAL_NTPC_CATALOG";
    private static final int SEARCH_RADIUS_METERS = 500;
    private static final int MAX_REQUEST_TOKEN_LENGTH = 120;
    private static final double MVP_MIN_LATITUDE = 25.015;
    private static final double MVP_MAX_LATITUDE = 25.075;
    private static final double MVP_MIN_LONGITUDE = 121.415;
    private static final double MVP_MAX_LONGITUDE = 121.490;

    private final Clock clock;
    private final NearbyParkingSearchService nearbyParkingSearchService;
    private final PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService;
    private final SourceRefreshCoordinationService sourceRefreshCoordinationService;
    private final GoogleEtaService googleEtaService;
    private final ParkingQueryStateStore parkingQueryStateStore;
    private final ParkingQueryStateProperties queryStateProperties;

    @Autowired
    public ParkingQueryService(
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties
    ) {
        this(Clock.systemUTC(), nearbyParkingSearchService, publishedAvailabilitySnapshotService, sourceRefreshCoordinationService, googleEtaService, parkingQueryStateStore, queryStateProperties);
    }

    ParkingQueryService(
            Clock clock,
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties
    ) {
        this.clock = clock;
        this.nearbyParkingSearchService = nearbyParkingSearchService;
        this.publishedAvailabilitySnapshotService = publishedAvailabilitySnapshotService;
        this.sourceRefreshCoordinationService = sourceRefreshCoordinationService;
        this.googleEtaService = googleEtaService;
        this.parkingQueryStateStore = parkingQueryStateStore;
        this.queryStateProperties = queryStateProperties;
    }

    public ParkingQueryResponse create(CreateParkingQueryRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        validateRequest(request, now);

        return parkingQueryStateStore.findQueryIdByRequestToken(request.requestToken())
                .map(this::findById)
                .orElseGet(() -> createNewQuery(request, now));
    }

    private ParkingQueryResponse createNewQuery(CreateParkingQueryRequest request, OffsetDateTime now) {
        boolean scheduledDeparture = "SCHEDULED".equalsIgnoreCase(request.departure().type())
                && request.departure().departureAt() != null;
        OffsetDateTime departureAt = scheduledDeparture
                ? OffsetDateTime.parse(request.departure().departureAt())
                : now;
        EtaResult eta = googleEtaService.estimate(
                request.origin(),
                request.destination(),
                departureAt,
                scheduledDeparture);
        String queryId = UUID.randomUUID().toString();
        NearbyParkingSearchResult nearby = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                MVP_SERVICE_AREA,
                request.destination().latitude(),
                request.destination().longitude(),
                SEARCH_RADIUS_METERS
        ));
        RoadsideSection roadsideSection = buildRoadsideSection(nearby);
        OffStreetSection offStreetSection = buildOffStreetSection(nearby);
        EtaSection etaSection = new EtaSection(
                eta.status(),
                eta.provider(),
                eta.departureAt(),
                eta.estimatedArrivalAt(),
                eta.durationSeconds(),
                eta.message());

        ParkingQueryResponse response = new ParkingQueryResponse(
                new QuerySection(queryId, "READY", false, now, now, now.plus(queryStateProperties.getTtl()), "Parking query is ready."),
                new DestinationSection(
                        request.destination().label(),
                        request.destination().latitude(),
                        request.destination().longitude(),
                        "SUPPORTED",
                        "Response uses the Xinzhuang MVP catalog seeded from NTPC Open Data."),
                new AreaSection(
                        nearby.radiusMeters(),
                        nearby.distanceMode(),
                        MVP_AREA_CODE,
                        resolveCoverageStatus(nearby),
                        "Catalog coverage uses a S2-020 traceable NTPC Open Data subset. It is not complete city coverage."),
                etaSection,
                roadsideSection,
                offStreetSection,
                buildPredictionsSection(nearby, etaSection, roadsideSection, offStreetSection, now),
                buildRefreshSection(roadsideSection, offStreetSection));

        parkingQueryStateStore.save(request.requestToken(), new StoredParkingQuery(response, request));
        return response;
    }

    public ParkingQueryResponse findById(String queryId) {
        ParkingQueryResponse response = loadStoredQuery(queryId).response();
        requireNotExpired(response);
        return response;
    }

    public ParkingQueryResponse refresh(String queryId) {
        return refresh(queryId, TriggerType.MANUAL_REFRESH);
    }

    public ParkingQueryResponse refresh(String queryId, TriggerType triggerType) {
        StoredParkingQuery storedQuery = loadStoredQuery(queryId);
        ParkingQueryResponse existing = storedQuery.response();
        requireNotExpired(existing);
        CreateParkingQueryRequest request = storedQuery.request();

        SourceRefreshResult roadsideRefresh = sourceRefreshCoordinationService.refresh(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                triggerType
        );
        SourceRefreshResult offStreetRefresh = sourceRefreshCoordinationService.refresh(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                triggerType
        );

        OffsetDateTime now = OffsetDateTime.now(clock);
        NearbyParkingSearchResult nearby = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                MVP_SERVICE_AREA,
                request.destination().latitude(),
                request.destination().longitude(),
                SEARCH_RADIUS_METERS
        ));
        RoadsideSection roadsideSection = buildRoadsideSection(nearby);
        OffStreetSection offStreetSection = buildOffStreetSection(nearby);

        ParkingQueryResponse refreshed = new ParkingQueryResponse(
                new QuerySection(
                        existing.query().queryId(),
                        existing.query().status(),
                        roadsideRefresh.refreshInProgress() || offStreetRefresh.refreshInProgress(),
                        existing.query().createdAt(),
                        now,
                        existing.query().expiresAt(),
                        "已完成受控刷新檢查；查詢結果只讀取最新已發布快照。"),
                existing.destination(),
                new AreaSection(
                        nearby.radiusMeters(),
                        nearby.distanceMode(),
                        MVP_AREA_CODE,
                        resolveCoverageStatus(nearby),
                        "Catalog coverage uses a S2-020 traceable NTPC Open Data subset. It is not complete city coverage."),
                existing.eta(),
                roadsideSection,
                offStreetSection,
                buildPredictionsSection(nearby, existing.eta(), roadsideSection, offStreetSection, now),
                buildRefreshSection(roadsideRefresh, offStreetRefresh));
        parkingQueryStateStore.save(request.requestToken(), new StoredParkingQuery(refreshed, request));
        return refreshed;
    }

    private StoredParkingQuery loadStoredQuery(String queryId) {
        return parkingQueryStateStore.findByQueryId(queryId)
                .orElseThrow(() -> new ParkingQueryNotFoundException(queryId));
    }

    private void validateRequest(CreateParkingQueryRequest request, OffsetDateTime now) {
        validateRequestToken(request.requestToken());
        validateLocation(request.origin(), "origin");
        validateLocation(request.destination(), "destination");
        validateSupportedDestination(request.destination());
        validateDeparture(request.departure(), now);
    }

    private void validateRequestToken(String requestToken) {
        if (requestToken == null
                || requestToken.isBlank()
                || requestToken.length() > MAX_REQUEST_TOKEN_LENGTH
                || !requestToken.matches("[A-Za-z0-9._:-]+")) {
            throw new ParkingQueryContractException(
                    "INVALID_REQUEST_TOKEN",
                    "requestToken is missing or invalid.",
                    false
            );
        }
    }

    private void validateLocation(CreateParkingQueryRequest.LocationInput location, String fieldName) {
        if (location == null
                || location.latitude() == null
                || location.longitude() == null
                || location.latitude() < -90
                || location.latitude() > 90
                || location.longitude() < -180
                || location.longitude() > 180) {
            throw new ParkingQueryContractException(
                    "INVALID_COORDINATE",
                    fieldName + " latitude or longitude is invalid.",
                    false
            );
        }
    }

    private void validateSupportedDestination(CreateParkingQueryRequest.LocationInput destination) {
        if (destination.latitude() < MVP_MIN_LATITUDE
                || destination.latitude() > MVP_MAX_LATITUDE
                || destination.longitude() < MVP_MIN_LONGITUDE
                || destination.longitude() > MVP_MAX_LONGITUDE) {
            throw new ParkingQueryContractException(
                    "UNSUPPORTED_AREA",
                    "Destination is outside the Xinzhuang MVP service area.",
                    false
            );
        }
    }

    private void validateDeparture(CreateParkingQueryRequest.DepartureInput departure, OffsetDateTime now) {
        if (departure == null || departure.type() == null || departure.type().isBlank()) {
            throw new ParkingQueryContractException("INVALID_DEPARTURE_TIME", "departure.type is required.", false);
        }
        if ("NOW".equalsIgnoreCase(departure.type())) {
            if (departure.departureAt() != null && !departure.departureAt().isBlank()) {
                throw new ParkingQueryContractException("INVALID_DEPARTURE_TIME", "NOW departure must not include departureAt.", false);
            }
            return;
        }
        if (!"SCHEDULED".equalsIgnoreCase(departure.type())) {
            throw new ParkingQueryContractException("INVALID_DEPARTURE_TIME", "departure.type must be NOW or SCHEDULED.", false);
        }
        try {
            OffsetDateTime departureAt = OffsetDateTime.parse(departure.departureAt());
            if (!departureAt.isAfter(now)) {
                throw new ParkingQueryContractException("INVALID_DEPARTURE_TIME", "Scheduled departure must be in the future.", false);
            }
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new ParkingQueryContractException("INVALID_DEPARTURE_TIME", "Scheduled departureAt must include a valid offset date time.", false);
        }
    }

    private void requireNotExpired(ParkingQueryResponse response) {
        if (!response.query().expiresAt().isAfter(OffsetDateTime.now(clock))) {
            throw new ParkingQueryExpiredException(response.query().queryId());
        }
    }

    private RoadsideSection buildRoadsideSection(NearbyParkingSearchResult nearby) {
        List<NearbyParkingPlace> roadsidePlaces = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .toList();
        PublishedAvailabilityView availability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                roadsidePlaces.stream().map(NearbyParkingPlace::parkingPlaceId).toList()
        );

        if (roadsidePlaces.isEmpty()) {
            return new RoadsideSection(
                    "NO_CATALOG_RESULTS",
                    LOCAL_CATALOG_SOURCE,
                    null,
                    null,
                    null,
                    List.of(),
                    "No roadside catalog records were found inside 500 meters."
            );
        }

        String status = resolveRoadsideStatus(availability);
        Integer currentlyAvailableSpaces = resolveRoadsideAvailableCount(availability);
        List<RoadsideSpot> availableSpots = availability.isFreshReady()
                ? roadsidePlaces.stream()
                        .filter(place -> isFreshAvailableRoadsideSpot(availability, place.parkingPlaceId()))
                        .map(place -> toRoadsideSpot(place, availability))
                        .toList()
                : List.of();

        return new RoadsideSection(
                status,
                resolveSource(availability),
                availability.snapshotId(),
                availability.systemCollectedAt(),
                currentlyAvailableSpaces,
                availableSpots,
                roadsideMessage(status, availability)
        );
    }

    private OffStreetSection buildOffStreetSection(NearbyParkingSearchResult nearby) {
        List<NearbyParkingPlace> offStreetPlaces = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .toList();
        PublishedAvailabilityView availability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                offStreetPlaces.stream().map(NearbyParkingPlace::parkingPlaceId).toList()
        );

        if (offStreetPlaces.isEmpty()) {
            return new OffStreetSection(
                    "NO_CATALOG_RESULTS",
                    LOCAL_CATALOG_SOURCE,
                    null,
                    null,
                    List.of(),
                    "No off street catalog records were found inside 500 meters."
            );
        }

        List<ParkingLot> parkingLots = offStreetPlaces.stream()
                .map(place -> toParkingLot(place, availability))
                .toList();

        return new OffStreetSection(
                resolveOffStreetStatus(availability, parkingLots),
                resolveSource(availability),
                availability.snapshotId(),
                availability.systemCollectedAt(),
                parkingLots,
                offStreetMessage(availability)
        );
    }

    private RefreshSection buildRefreshSection(RoadsideSection roadsideSection, OffStreetSection offStreetSection) {
        return new RefreshSection(
                new SourceRefresh(
                        "ROADSIDE",
                        refreshAction(roadsideSection.status()),
                        false,
                        roadsideSection.systemCollectedAt(),
                        null,
                        refreshMessage(roadsideSection.status())
                ),
                new SourceRefresh(
                        "OFF_STREET",
                        refreshAction(offStreetSection.status()),
                        false,
                        offStreetSection.systemCollectedAt(),
                        null,
                        refreshMessage(offStreetSection.status())
                ));
    }

    private RefreshSection buildRefreshSection(SourceRefreshResult roadsideRefresh, SourceRefreshResult offStreetRefresh) {
        return new RefreshSection(
                toSourceRefresh("ROADSIDE", roadsideRefresh),
                toSourceRefresh("OFF_STREET", offStreetRefresh)
        );
    }

    private SourceRefresh toSourceRefresh(String sourceType, SourceRefreshResult result) {
        return new SourceRefresh(
                sourceType,
                result.action(),
                result.refreshInProgress(),
                result.lastSucceededAt(),
                result.nextAllowedAt(),
                result.message()
        );
    }

    private String refreshAction(String status) {
        return switch (status) {
            case "READY", "UNKNOWN" -> "USED_PUBLISHED_SNAPSHOT";
            case "STALE" -> "USED_STALE_PUBLISHED_SNAPSHOT";
            case "NO_REALTIME_SNAPSHOT" -> "NO_PUBLISHED_SNAPSHOT";
            default -> "USED_LOCAL_CATALOG";
        };
    }

    private String refreshMessage(String status) {
        return switch (status) {
            case "READY" -> "Published snapshot was used for the query response.";
            case "STALE" -> "Published snapshot is stale; current available count is intentionally hidden.";
            case "UNKNOWN" -> "Published snapshot has only unknown nearby observations.";
            case "NO_REALTIME_SNAPSHOT" -> "No published snapshot is available yet. S2-017 does not trigger official API refresh.";
            default -> "Local catalog result was used.";
        };
    }

    private String resolveRoadsideStatus(PublishedAvailabilityView availability) {
        if (!availability.isFreshReady()) {
            return availability.status();
        }
        boolean hasOnlyUnknown = !availability.observations().isEmpty()
                && availability.observations().values().stream()
                        .allMatch(observation -> observation.availabilityStatus() == AvailabilityStatus.UNKNOWN);
        return hasOnlyUnknown ? "UNKNOWN" : "READY";
    }

    private Integer resolveRoadsideAvailableCount(PublishedAvailabilityView availability) {
        if (!availability.isFreshReady()) {
            return null;
        }
        boolean hasKnownObservation = availability.observations().values().stream()
                .anyMatch(observation -> observation.availabilityStatus() != AvailabilityStatus.UNKNOWN);
        if (!hasKnownObservation) {
            return null;
        }
        return (int) availability.observations().values().stream()
                .filter(this::isAvailableRoadsideObservation)
                .count();
    }

    private boolean isFreshAvailableRoadsideSpot(PublishedAvailabilityView availability, Long parkingPlaceId) {
        PublishedObservation observation = availability.observations().get(parkingPlaceId);
        return observation != null && isAvailableRoadsideObservation(observation);
    }

    private boolean isAvailableRoadsideObservation(PublishedObservation observation) {
        return observation.availabilityStatus() == AvailabilityStatus.AVAILABLE && Boolean.TRUE.equals(observation.availableSpot());
    }

    private RoadsideSpot toRoadsideSpot(NearbyParkingPlace place, PublishedAvailabilityView availability) {
        return new RoadsideSpot(
                place.parkingPlaceId().toString(),
                place.displayName(),
                place.latitude(),
                place.longitude(),
                place.distanceMeters(),
                true,
                AvailabilityStatus.AVAILABLE.name(),
                availability.systemCollectedAt()
        );
    }

    private ParkingLot toParkingLot(NearbyParkingPlace place, PublishedAvailabilityView availability) {
        PublishedObservation observation = availability.observations().get(place.parkingPlaceId());
        String availabilityStatus = resolveParkingLotAvailabilityStatus(availability, observation);
        Integer availableCarSpaces = resolveParkingLotAvailableCarSpaces(availability, observation);
        return new ParkingLot(
                place.parkingPlaceId().toString(),
                place.displayName(),
                place.entranceLatitude() == null ? place.latitude() : place.entranceLatitude(),
                place.entranceLongitude() == null ? place.longitude() : place.entranceLongitude(),
                place.distanceMeters(),
                availableCarSpaces,
                true,
                availabilityStatus,
                availability.systemCollectedAt(),
                parkingLotMessage(availabilityStatus)
        );
    }

    private String resolveParkingLotAvailabilityStatus(PublishedAvailabilityView availability, PublishedObservation observation) {
        if ("STALE".equals(availability.status())) {
            return "STALE";
        }
        if ("NO_REALTIME_SNAPSHOT".equals(availability.status())) {
            return "NO_REALTIME_SNAPSHOT";
        }
        if (observation == null) {
            return "NO_OBSERVATION";
        }
        return observation.availabilityStatus().name();
    }

    private Integer resolveParkingLotAvailableCarSpaces(PublishedAvailabilityView availability, PublishedObservation observation) {
        if (!availability.isFreshReady() || observation == null) {
            return null;
        }
        if (observation.availabilityStatus() == AvailabilityStatus.AVAILABLE
                || observation.availabilityStatus() == AvailabilityStatus.FULL) {
            return observation.availableCarSpaces();
        }
        return null;
    }

    private String resolveOffStreetStatus(PublishedAvailabilityView availability, List<ParkingLot> parkingLots) {
        if (!availability.isFreshReady()) {
            return availability.status();
        }
        boolean hasUnknown = parkingLots.stream().anyMatch(lot -> "UNKNOWN".equals(lot.availabilityStatus()));
        boolean hasKnown = parkingLots.stream().anyMatch(lot -> "AVAILABLE".equals(lot.availabilityStatus())
                || "FULL".equals(lot.availabilityStatus())
                || "OUT_OF_SERVICE".equals(lot.availabilityStatus()));
        if (hasKnown && hasUnknown) {
            return "PARTIAL";
        }
        return hasKnown ? "READY" : "UNKNOWN";
    }

    private String resolveSource(PublishedAvailabilityView availability) {
        return "NO_REALTIME_SNAPSHOT".equals(availability.status()) || "NO_CATALOG_RESULTS".equals(availability.status())
                ? LOCAL_CATALOG_SOURCE
                : "NTPC_OPEN_DATA";
    }

    private String roadsideMessage(String status, PublishedAvailabilityView availability) {
        return switch (status) {
            case "READY" -> "Fresh published roadside snapshot is used. Currently available spots can be shown and navigated.";
            case "STALE" -> "Roadside snapshot is older than 5 minutes. Current available count and spot navigation are hidden.";
            case "UNKNOWN" -> "Nearby roadside observations are unknown; current availability cannot be confirmed.";
            case "NO_OBSERVATION" -> availability.message();
            case "NO_REALTIME_SNAPSHOT" -> "Roadside catalog records exist, but no published real-time snapshot is available yet.";
            default -> availability.message();
        };
    }

    private String offStreetMessage(PublishedAvailabilityView availability) {
        return switch (availability.status()) {
            case "READY" -> "Fresh published off street snapshot is used for nearby parking lots.";
            case "STALE" -> "Off street snapshot is older than 5 minutes. Current available car spaces are hidden.";
            case "UNKNOWN" -> "Nearby off street observations are unknown; current availability cannot be confirmed.";
            case "NO_REALTIME_SNAPSHOT" -> "Off street catalog records exist, but no published real-time snapshot is available yet.";
            default -> availability.message();
        };
    }

    private String parkingLotMessage(String availabilityStatus) {
        return switch (availabilityStatus) {
            case "AVAILABLE" -> "Fresh snapshot reports available car spaces.";
            case "FULL" -> "Fresh snapshot reports 0 available car spaces.";
            case "STALE" -> "Snapshot is stale; this value is not shown as current availability.";
            case "UNKNOWN" -> "Source observation is unknown.";
            case "OUT_OF_SERVICE" -> "Source reports this parking lot is temporarily not providing real-time availability.";
            case "NO_OBSERVATION" -> "No matching observation was found in the published snapshot.";
            case "NO_REALTIME_SNAPSHOT" -> "No published real-time snapshot is available yet.";
            default -> "Availability status is carried from the published snapshot.";
        };
    }

    private PredictionsSection buildPredictionsSection(
            NearbyParkingSearchResult nearby,
            EtaSection eta,
            RoadsideSection roadsideSection,
            OffStreetSection offStreetSection,
            OffsetDateTime generatedAt
    ) {
        List<String> commonReasons = predictionReasons(eta);
        boolean hasRoadsideCatalog = nearby.places().stream()
                .anyMatch(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT);
        List<String> roadsideReasons = new ArrayList<>(commonReasons);
        roadsideReasons.addAll(sourcePredictionReasons(roadsideSection.status(), hasRoadsideCatalog));
        if (hasRoadsideCatalog) {
            roadsideReasons.add("CATALOG_COVERAGE_PARTIAL");
        }

        List<OffStreetPrediction> offStreetPredictions = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .map(place -> new OffStreetPrediction(
                        place.parkingPlaceId().toString(),
                        "INSUFFICIENT_DATA",
                        null,
                        null,
                        inputSnapshotVersion(offStreetSection),
                        dataAgeSeconds(offStreetSection.systemCollectedAt(), generatedAt),
                        offStreetPredictionReasons(commonReasons, offStreetSection.status(), place.parkingPlaceId(), offStreetSection),
                        "No validated model or baseline is connected yet."
                ))
                .toList();

        List<String> sectionReasons = new ArrayList<>(roadsideReasons);
        offStreetPredictions.stream()
                .flatMap(prediction -> prediction.reasons().stream())
                .filter(reason -> !sectionReasons.contains(reason))
                .forEach(sectionReasons::add);

        return new PredictionsSection(
                "INSUFFICIENT_DATA",
                eta.estimatedArrivalAt(),
                null,
                generatedAt,
                List.copyOf(sectionReasons),
                new RoadsidePrediction(
                        "INSUFFICIENT_DATA",
                        null,
                        null,
                        inputSnapshotVersion(roadsideSection),
                        dataAgeSeconds(roadsideSection.systemCollectedAt(), generatedAt),
                        List.copyOf(roadsideReasons),
                        "No validated model or baseline is connected yet."
                ),
                offStreetPredictions,
                "Prediction contract is present, but no validated model is loaded. Current availability and ETA remain independent."
        );
    }

    private List<String> predictionReasons(EtaSection eta) {
        List<String> reasons = new ArrayList<>(List.of("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED"));
        if (!"READY".equals(eta.status()) || eta.estimatedArrivalAt() == null) {
            reasons.add("ETA_NOT_READY");
        }
        return reasons;
    }

    private List<String> sourcePredictionReasons(String sourceStatus, boolean hasCatalogResults) {
        if (!hasCatalogResults) {
            return List.of("NO_CATALOG_RESULTS");
        }
        return switch (sourceStatus) {
            case "READY", "PARTIAL" -> List.of();
            case "STALE" -> List.of("REALTIME_SNAPSHOT_EXPIRED");
            case "NO_REALTIME_SNAPSHOT" -> List.of("NO_REALTIME_SNAPSHOT");
            case "UNKNOWN" -> List.of("UNKNOWN_AVAILABILITY");
            case "NO_OBSERVATION" -> List.of("NO_OBSERVATION");
            default -> List.of("MODEL_INPUT_NOT_READY");
        };
    }

    private List<String> offStreetPredictionReasons(
            List<String> commonReasons,
            String sourceStatus,
            Long parkingPlaceId,
            OffStreetSection offStreetSection
    ) {
        List<String> reasons = new ArrayList<>(commonReasons);
        reasons.addAll(sourcePredictionReasons(sourceStatus, true));
        offStreetSection.parkingLots().stream()
                .filter(lot -> lot.id().equals(parkingPlaceId.toString()))
                .findFirst()
                .map(ParkingLot::availabilityStatus)
                .ifPresent(status -> {
                    switch (status) {
                        case "STALE" -> reasons.add("REALTIME_SNAPSHOT_EXPIRED");
                        case "UNKNOWN" -> reasons.add("UNKNOWN_AVAILABILITY");
                        case "NO_OBSERVATION" -> reasons.add("NO_OBSERVATION");
                        case "NO_REALTIME_SNAPSHOT" -> reasons.add("NO_REALTIME_SNAPSHOT");
                        default -> {
                        }
                    }
                });
        return reasons.stream().distinct().toList();
    }

    private String inputSnapshotVersion(RoadsideSection section) {
        return section.snapshotId() == null ? null : "NTPC_ROADSIDE_SPOT:%d".formatted(section.snapshotId());
    }

    private String inputSnapshotVersion(OffStreetSection section) {
        return section.snapshotId() == null ? null : "NTPC_OFF_STREET_AVAILABILITY:%d".formatted(section.snapshotId());
    }

    private Integer dataAgeSeconds(OffsetDateTime systemCollectedAt, OffsetDateTime generatedAt) {
        if (systemCollectedAt == null || generatedAt == null) {
            return null;
        }
        Duration age = Duration.between(systemCollectedAt, generatedAt);
        if (age.isNegative()) {
            return null;
        }
        long ageSeconds = age.toSeconds();
        return ageSeconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ageSeconds;
    }

    private String resolveCoverageStatus(NearbyParkingSearchResult nearby) {
        return nearby.places().isEmpty() ? "NO_CATALOG_RESULTS" : "PARTIAL";
    }

    public static class ParkingQueryNotFoundException extends RuntimeException {

        public ParkingQueryNotFoundException(String queryId) {
            super("Parking query not found: " + queryId);
        }
    }

    public static class ParkingQueryExpiredException extends RuntimeException {

        public ParkingQueryExpiredException(String queryId) {
            super("Parking query expired: " + queryId);
        }
    }

    public static class ParkingQueryContractException extends RuntimeException {

        private final String code;
        private final boolean retryable;

        public ParkingQueryContractException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
