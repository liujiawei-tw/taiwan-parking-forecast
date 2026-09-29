package parking.forecast.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
import parking.forecast.service.SourceRefreshCoordinationService.SourceRefreshResult;

@Service
public class ParkingQueryMockService {

    private static final String MVP_SERVICE_AREA = "NEW_TAIPEI_XINZHUANG";
    private static final String MVP_AREA_CODE = "NEW_TAIPEI_XINZHUANG_MVP";
    private static final int SEARCH_RADIUS_METERS = 300;

    private final Clock clock;
    private final NearbyParkingSearchService nearbyParkingSearchService;
    private final PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService;
    private final SourceRefreshCoordinationService sourceRefreshCoordinationService;
    private final Map<String, ParkingQueryResponse> queries = new ConcurrentHashMap<>();
    private final Map<String, QueryContext> queryContexts = new ConcurrentHashMap<>();

    @Autowired
    public ParkingQueryMockService(
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService
    ) {
        this(Clock.systemUTC(), nearbyParkingSearchService, publishedAvailabilitySnapshotService, sourceRefreshCoordinationService);
    }

    ParkingQueryMockService(
            Clock clock,
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService
    ) {
        this.clock = clock;
        this.nearbyParkingSearchService = nearbyParkingSearchService;
        this.publishedAvailabilitySnapshotService = publishedAvailabilitySnapshotService;
        this.sourceRefreshCoordinationService = sourceRefreshCoordinationService;
    }

    public ParkingQueryResponse create(CreateParkingQueryRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departureAt = "SCHEDULED".equalsIgnoreCase(request.departure().type())
                && request.departure().departureAt() != null
                        ? OffsetDateTime.parse(request.departure().departureAt())
                        : now;
        OffsetDateTime estimatedArrivalAt = departureAt.plusMinutes(18);
        String queryId = UUID.randomUUID().toString();
        NearbyParkingSearchResult nearby = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                MVP_SERVICE_AREA,
                request.destination().latitude(),
                request.destination().longitude(),
                SEARCH_RADIUS_METERS
        ));
        RoadsideSection roadsideSection = buildRoadsideSection(nearby);
        OffStreetSection offStreetSection = buildOffStreetSection(nearby);

        ParkingQueryResponse response = new ParkingQueryResponse(
                new QuerySection(queryId, "READY", false, now, now, now.plusMinutes(30), "Mock query is ready."),
                new DestinationSection(
                        request.destination().label(),
                        request.destination().latitude(),
                        request.destination().longitude(),
                        "SUPPORTED",
                        "Response uses the Xinzhuang MVP catalog sample."),
                new AreaSection(
                        nearby.radiusMeters(),
                        nearby.distanceMode(),
                        MVP_AREA_CODE,
                        resolveCoverageStatus(nearby),
                        "Catalog coverage uses the S2-011 Xinzhuang seed sample. It is not complete city coverage."),
                new EtaSection(
                        "READY",
                        "MOCK_GOOGLE_ROUTES",
                        departureAt,
                        estimatedArrivalAt,
                        18 * 60,
                        "Mock ETA. Google Routes is not connected yet."),
                roadsideSection,
                offStreetSection,
                buildPredictionsSection(nearby, estimatedArrivalAt),
                buildRefreshSection(roadsideSection, offStreetSection));

        queries.put(queryId, response);
        queryContexts.put(queryId, new QueryContext(request, departureAt, estimatedArrivalAt));
        return response;
    }

    public ParkingQueryResponse findById(String queryId) {
        ParkingQueryResponse response = queries.get(queryId);
        if (response == null) {
            throw new ParkingQueryNotFoundException(queryId);
        }
        return response;
    }

    public ParkingQueryResponse refresh(String queryId) {
        ParkingQueryResponse existing = findById(queryId);
        QueryContext context = queryContexts.get(queryId);
        if (context == null) {
            throw new ParkingQueryNotFoundException(queryId);
        }

        SourceRefreshResult roadsideRefresh = sourceRefreshCoordinationService.refresh(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                TriggerType.MANUAL_REFRESH
        );
        SourceRefreshResult offStreetRefresh = sourceRefreshCoordinationService.refresh(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                TriggerType.MANUAL_REFRESH
        );

        OffsetDateTime now = OffsetDateTime.now(clock);
        NearbyParkingSearchResult nearby = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                MVP_SERVICE_AREA,
                context.request().destination().latitude(),
                context.request().destination().longitude(),
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
                        "Catalog coverage uses the S2-011 Xinzhuang seed sample. It is not complete city coverage."),
                existing.eta(),
                roadsideSection,
                offStreetSection,
                buildPredictionsSection(nearby, context.estimatedArrivalAt()),
                buildRefreshSection(roadsideRefresh, offStreetRefresh));
        queries.put(queryId, refreshed);
        return refreshed;
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
                    "LOCAL_CATALOG_SEED",
                    null,
                    null,
                    List.of(),
                    "No roadside catalog records were found inside 300 meters."
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
                    "LOCAL_CATALOG_SEED",
                    null,
                    List.of(),
                    "No off street catalog records were found inside 300 meters."
            );
        }

        List<ParkingLot> parkingLots = offStreetPlaces.stream()
                .map(place -> toParkingLot(place, availability))
                .toList();

        return new OffStreetSection(
                resolveOffStreetStatus(availability, parkingLots),
                resolveSource(availability),
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
                ? "LOCAL_CATALOG_SEED"
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

    private PredictionsSection buildPredictionsSection(NearbyParkingSearchResult nearby, OffsetDateTime estimatedArrivalAt) {
        List<OffStreetPrediction> offStreetPredictions = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .map(place -> new OffStreetPrediction(
                        place.parkingPlaceId().toString(),
                        "INSUFFICIENT_DATA",
                        null,
                        "No validated model or baseline is connected yet."
                ))
                .toList();

        return new PredictionsSection(
                "INSUFFICIENT_DATA",
                estimatedArrivalAt,
                new RoadsidePrediction("INSUFFICIENT_DATA", null, "No validated model or baseline is connected yet."),
                offStreetPredictions,
                "Prediction contract is present, but S2-017 does not output probabilities."
        );
    }

    private String resolveCoverageStatus(NearbyParkingSearchResult nearby) {
        return nearby.places().isEmpty() ? "NO_CATALOG_RESULTS" : "PARTIAL";
    }

    private record QueryContext(
            CreateParkingQueryRequest request,
            OffsetDateTime departureAt,
            OffsetDateTime estimatedArrivalAt
    ) {
    }

    public static class ParkingQueryNotFoundException extends RuntimeException {

        public ParkingQueryNotFoundException(String queryId) {
            super("Parking query not found: " + queryId);
        }
    }
}
