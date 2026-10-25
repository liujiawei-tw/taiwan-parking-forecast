package parking.forecast.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import parking.forecast.config.ParkingQueryStateProperties;
import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.ParkingQueryResponse;
import parking.forecast.controller.dto.ParkingQueryResponse.AreaSection;
import parking.forecast.controller.dto.ParkingQueryResponse.DestinationSection;
import parking.forecast.controller.dto.ParkingQueryResponse.EtaSection;
import parking.forecast.controller.dto.ParkingQueryResponse.GooglePlacesSection;
import parking.forecast.controller.dto.ParkingQueryResponse.OffStreetPrediction;
import parking.forecast.controller.dto.ParkingQueryResponse.OffStreetSection;
import parking.forecast.controller.dto.ParkingQueryResponse.MapDataResponse;
import parking.forecast.controller.dto.ParkingQueryResponse.MapBounds;
import parking.forecast.controller.dto.ParkingQueryResponse.MapMarker;
import parking.forecast.controller.dto.ParkingQueryResponse.MapSource;
import parking.forecast.controller.dto.ParkingQueryResponse.MapViewportResponse;
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
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.QualityStatus;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;
import parking.forecast.domain.TriggerType;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingPlace;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchRequest;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchResult;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingViewportRequest;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingViewportResult;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedAvailabilityView;
import parking.forecast.service.PublishedAvailabilitySnapshotService.PublishedObservation;
import parking.forecast.service.GoogleEtaService.EtaResult;
import parking.forecast.service.ExperimentalOffStreetPredictionService.PredictionInput;
import parking.forecast.service.ExperimentalOffStreetPredictionService.PredictionResult;
import parking.forecast.service.ParkingQueryStateStore.StoredParkingQuery;
import parking.forecast.repository.SourceMappingRepository;
import parking.forecast.service.SourceRefreshCoordinationService.SourceRefreshResult;

@Service
public class ParkingQueryService {

    private static final String MVP_SERVICE_AREA = "NEW_TAIPEI_XINZHUANG";
    private static final String MVP_AREA_CODE = "NEW_TAIPEI_XINZHUANG_MVP";
    private static final String LOCAL_CATALOG_SOURCE = "LOCAL_NTPC_CATALOG";
    private static final int SEARCH_RADIUS_METERS = 500;
    private static final int MAX_ROADSIDE_MAP_MARKERS = 400;
    private static final int MAX_OFF_STREET_MAP_MARKERS = 100;
    private static final int MAX_MAP_VIEWPORT_PAGE_SIZE = 500;
    private static final int MAX_MAP_VIEWPORT_OFFSET = 20_000;
    private static final int SOURCE_MAPPING_QUERY_BATCH_SIZE = 500;
    private static final int MAX_REQUEST_TOKEN_LENGTH = 120;
    private static final double MVP_MIN_LATITUDE = 24.995;
    private static final double MVP_MAX_LATITUDE = 25.075;
    private static final double MVP_MIN_LONGITUDE = 121.405;
    private static final double MVP_MAX_LONGITUDE = 121.490;

    private final Clock clock;
    private final NearbyParkingSearchService nearbyParkingSearchService;
    private final PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService;
    private final SourceRefreshCoordinationService sourceRefreshCoordinationService;
    private final GoogleEtaService googleEtaService;
    private final GoogleParkingPlaceSearchService googleParkingPlaceSearchService;
    private final ParkingQueryStateStore parkingQueryStateStore;
    private final ParkingQueryStateProperties queryStateProperties;
    private final SourceMappingRepository sourceMappingRepository;
    private final NtpcRoadsideFeeRateLookupService ntpcRoadsideFeeRateLookupService;
    private final ExperimentalOffStreetPredictionService experimentalOffStreetPredictionService;

    @Autowired
    public ParkingQueryService(
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            GoogleParkingPlaceSearchService googleParkingPlaceSearchService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties,
            SourceMappingRepository sourceMappingRepository,
            NtpcRoadsideFeeRateLookupService ntpcRoadsideFeeRateLookupService,
            ExperimentalOffStreetPredictionService experimentalOffStreetPredictionService
    ) {
        this(Clock.systemUTC(), nearbyParkingSearchService, publishedAvailabilitySnapshotService,
                sourceRefreshCoordinationService, googleEtaService, googleParkingPlaceSearchService,
                parkingQueryStateStore, queryStateProperties, sourceMappingRepository, ntpcRoadsideFeeRateLookupService,
                experimentalOffStreetPredictionService);
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
        this(clock, nearbyParkingSearchService, publishedAvailabilitySnapshotService,
                sourceRefreshCoordinationService, googleEtaService, null, parkingQueryStateStore,
                queryStateProperties, null, null);
    }

    ParkingQueryService(
            Clock clock,
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties,
            SourceMappingRepository sourceMappingRepository
    ) {
        this(clock, nearbyParkingSearchService, publishedAvailabilitySnapshotService,
                sourceRefreshCoordinationService, googleEtaService, null, parkingQueryStateStore,
                queryStateProperties, sourceMappingRepository, null);
    }

    ParkingQueryService(
            Clock clock,
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            GoogleParkingPlaceSearchService googleParkingPlaceSearchService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties,
            SourceMappingRepository sourceMappingRepository
    ) {
        this(clock, nearbyParkingSearchService, publishedAvailabilitySnapshotService,
                sourceRefreshCoordinationService, googleEtaService, googleParkingPlaceSearchService,
                parkingQueryStateStore, queryStateProperties, sourceMappingRepository, null);
    }

    ParkingQueryService(
            Clock clock,
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            GoogleParkingPlaceSearchService googleParkingPlaceSearchService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties,
            SourceMappingRepository sourceMappingRepository,
            NtpcRoadsideFeeRateLookupService ntpcRoadsideFeeRateLookupService
    ) {
        this(clock, nearbyParkingSearchService, publishedAvailabilitySnapshotService, sourceRefreshCoordinationService,
                googleEtaService, googleParkingPlaceSearchService, parkingQueryStateStore, queryStateProperties,
                sourceMappingRepository, ntpcRoadsideFeeRateLookupService, ExperimentalOffStreetPredictionService.disabled());
    }

    ParkingQueryService(
            Clock clock,
            NearbyParkingSearchService nearbyParkingSearchService,
            PublishedAvailabilitySnapshotService publishedAvailabilitySnapshotService,
            SourceRefreshCoordinationService sourceRefreshCoordinationService,
            GoogleEtaService googleEtaService,
            GoogleParkingPlaceSearchService googleParkingPlaceSearchService,
            ParkingQueryStateStore parkingQueryStateStore,
            ParkingQueryStateProperties queryStateProperties,
            SourceMappingRepository sourceMappingRepository,
            NtpcRoadsideFeeRateLookupService ntpcRoadsideFeeRateLookupService,
            ExperimentalOffStreetPredictionService experimentalOffStreetPredictionService
    ) {
        this.clock = clock;
        this.nearbyParkingSearchService = nearbyParkingSearchService;
        this.publishedAvailabilitySnapshotService = publishedAvailabilitySnapshotService;
        this.sourceRefreshCoordinationService = sourceRefreshCoordinationService;
        this.googleEtaService = googleEtaService;
        this.googleParkingPlaceSearchService = googleParkingPlaceSearchService;
        this.parkingQueryStateStore = parkingQueryStateStore;
        this.queryStateProperties = queryStateProperties;
        this.sourceMappingRepository = sourceMappingRepository;
        this.ntpcRoadsideFeeRateLookupService = ntpcRoadsideFeeRateLookupService;
        this.experimentalOffStreetPredictionService = experimentalOffStreetPredictionService;
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

        GooglePlacesSection googlePlaces = googleParkingPlaceSearchService == null
                ? new GooglePlacesSection("NOT_REQUESTED", "GOOGLE_PLACES", SEARCH_RADIUS_METERS, null,
                        List.of(), "Google Places search was not configured in this service instance.")
                : googleParkingPlaceSearchService.findNearby(
                        request.destination().latitude(),
                        request.destination().longitude(),
                        SEARCH_RADIUS_METERS);

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
                buildRefreshSection(roadsideSection, offStreetSection),
                googlePlaces);

        ParkingQueryResponse stateResponse = googlePlaces.items().isEmpty()
                || googleParkingPlaceSearchService == null
                ? response
                : response.withGooglePlaces(googleParkingPlaceSearchService.notRetrieved(SEARCH_RADIUS_METERS));
        parkingQueryStateStore.save(request.requestToken(), new StoredParkingQuery(stateResponse, request));
        return response;
    }

    public ParkingQueryResponse findById(String queryId) {
        ParkingQueryResponse response = loadStoredQuery(queryId).response();
        requireNotExpired(response);
        return response;
    }

    public MapDataResponse findMapData(String queryId) {
        StoredParkingQuery storedQuery = loadStoredQuery(queryId);
        requireNotExpired(storedQuery.response());
        var destination = storedQuery.request().destination();
        NearbyParkingSearchResult nearby = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                MVP_SERVICE_AREA,
                destination.latitude(),
                destination.longitude(),
                SEARCH_RADIUS_METERS
        ));

        List<NearbyParkingPlace> roadsidePlaces = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .toList();
        List<NearbyParkingPlace> offStreetPlaces = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .toList();
        Map<Long, SourceMappingEntity> roadsideMappings = findUniqueVerifiedMappings(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                roadsidePlaces
        );
        Map<Long, SourceMappingEntity> offStreetMappings = findUniqueVerifiedMappings(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                offStreetPlaces
        );
        List<NearbyParkingPlace> selectedRoadside = selectVerifiedMapPlaces(
                roadsidePlaces,
                roadsideMappings,
                MAX_ROADSIDE_MAP_MARKERS
        );
        List<NearbyParkingPlace> selectedOffStreet = selectVerifiedMapPlaces(
                offStreetPlaces,
                offStreetMappings,
                MAX_OFF_STREET_MAP_MARKERS
        );
        PublishedAvailabilityView roadsideAvailability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                selectedRoadside.stream().map(NearbyParkingPlace::parkingPlaceId).toList()
        );
        PublishedAvailabilityView offStreetAvailability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                selectedOffStreet.stream().map(NearbyParkingPlace::parkingPlaceId).toList()
        );

        List<MapMarker> items = new ArrayList<>();
        selectedRoadside.stream()
                .filter(place -> roadsideMappings.containsKey(place.parkingPlaceId()))
                .map(place -> toMapMarker(place, roadsideMappings.get(place.parkingPlaceId()), roadsideAvailability, true))
                .forEach(items::add);
        selectedOffStreet.stream()
                .filter(place -> offStreetMappings.containsKey(place.parkingPlaceId()))
                .map(place -> toMapMarker(place, offStreetMappings.get(place.parkingPlaceId()), offStreetAvailability, false))
                .forEach(items::add);
        items.sort(java.util.Comparator.comparingInt(MapMarker::distanceMeters)
                .thenComparing(MapMarker::label)
                .thenComparing(MapMarker::id));

        int candidateCount = roadsidePlaces.size() + offStreetPlaces.size();
        int verifiedCandidateCount = roadsideMappings.size() + offStreetMappings.size();
        int returnedItemCount = items.size();
        int omittedItemCount = verifiedCandidateCount - returnedItemCount;
        int excludedUnverifiedCount = candidateCount - verifiedCandidateCount;
        return new MapDataResponse(
                queryId,
                SEARCH_RADIUS_METERS,
                storedQuery.response().area().coverageStatus(),
                OffsetDateTime.now(clock),
                candidateCount,
                verifiedCandidateCount,
                excludedUnverifiedCount,
                omittedItemCount,
                returnedItemCount,
                "標記只讀取新莊審查目錄與 500 公尺內最新已發布快照；此端點不會觸發官方來源刷新。",
                toMapSource(roadsideAvailability),
                toMapSource(offStreetAvailability),
                List.copyOf(items)
        );
    }

    public MapViewportResponse findMapViewport(
            String queryId,
            double south,
            double west,
            double north,
            double east,
            int offset,
            int pageSize
    ) {
        StoredParkingQuery storedQuery = loadStoredQuery(queryId);
        requireNotExpired(storedQuery.response());
        validateMapViewport(south, west, north, east, offset, pageSize);

        MapBounds requestedBounds = new MapBounds(south, west, north, east);
        MapBounds supportedArea = supportedAreaBounds();
        MapBounds clippedBounds = intersect(requestedBounds, supportedArea);
        if (clippedBounds == null) {
            return emptyMapViewport(storedQuery, requestedBounds, supportedArea, offset, pageSize);
        }

        var destination = storedQuery.request().destination();
        NearbyParkingViewportResult viewport = nearbyParkingSearchService.findInViewport(
                new NearbyParkingViewportRequest(
                        MVP_SERVICE_AREA,
                        destination.latitude(),
                        destination.longitude(),
                        clippedBounds.south(),
                        clippedBounds.west(),
                        clippedBounds.north(),
                        clippedBounds.east()
                )
        );
        List<NearbyParkingPlace> roadsidePlaces = viewport.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .toList();
        List<NearbyParkingPlace> offStreetPlaces = viewport.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .toList();
        Map<Long, SourceMappingEntity> roadsideMappings = findUniqueVerifiedMappings(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                roadsidePlaces
        );
        Map<Long, SourceMappingEntity> offStreetMappings = findUniqueVerifiedMappings(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                offStreetPlaces
        );

        List<NearbyParkingPlace> verifiedPlaces = new ArrayList<>();
        roadsidePlaces.stream().filter(place -> roadsideMappings.containsKey(place.parkingPlaceId())).forEach(verifiedPlaces::add);
        offStreetPlaces.stream().filter(place -> offStreetMappings.containsKey(place.parkingPlaceId())).forEach(verifiedPlaces::add);
        verifiedPlaces.sort(Comparator.comparing(NearbyParkingPlace::placeType)
                .thenComparing(NearbyParkingPlace::parkingPlaceId));

        int verifiedCount = verifiedPlaces.size();
        int pageStart = Math.min(offset, verifiedCount);
        int pageEnd = Math.min(pageStart + pageSize, verifiedCount);
        List<NearbyParkingPlace> selectedPlaces = verifiedPlaces.subList(pageStart, pageEnd);
        List<NearbyParkingPlace> selectedRoadside = selectedPlaces.stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .toList();
        List<NearbyParkingPlace> selectedOffStreet = selectedPlaces.stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .toList();
        PublishedAvailabilityView roadsideAvailability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                selectedRoadside.stream().map(NearbyParkingPlace::parkingPlaceId).toList()
        );
        PublishedAvailabilityView offStreetAvailability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                selectedOffStreet.stream().map(NearbyParkingPlace::parkingPlaceId).toList()
        );

        List<MapMarker> items = new ArrayList<>();
        selectedRoadside.stream()
                .map(place -> toMapMarker(place, roadsideMappings.get(place.parkingPlaceId()), roadsideAvailability, true))
                .forEach(items::add);
        selectedOffStreet.stream()
                .map(place -> toMapMarker(place, offStreetMappings.get(place.parkingPlaceId()), offStreetAvailability, false))
                .forEach(items::add);
        int nextOffset = pageEnd < verifiedCount ? pageEnd : -1;
        boolean clipped = !clippedBounds.equals(requestedBounds);
        String message = clipped
                ? "地圖標記只讀取支援範圍內的審查目錄與已發布快照；視窗超出支援範圍的部分已排除，不會觸發官方來源刷新。"
                : "地圖標記只讀取可視範圍內的審查目錄與已發布快照；此唯讀端點不會觸發官方來源刷新。";
        return new MapViewportResponse(
                queryId,
                MVP_SERVICE_AREA,
                clippedBounds,
                supportedArea,
                storedQuery.response().area().coverageStatus(),
                OffsetDateTime.now(clock),
                viewport.places().size(),
                verifiedCount,
                Math.max(0, viewport.places().size() - verifiedCount),
                offset,
                pageSize,
                items.size(),
                nextOffset < 0 ? null : nextOffset,
                nextOffset >= 0,
                message,
                toMapSource(roadsideAvailability),
                toMapSource(offStreetAvailability),
                List.copyOf(items)
        );
    }

    private MapViewportResponse emptyMapViewport(
            StoredParkingQuery storedQuery,
            MapBounds requestedBounds,
            MapBounds supportedArea,
            int offset,
            int pageSize
    ) {
        String queryId = storedQuery.response().query().queryId();
        PublishedAvailabilityView roadsideAvailability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                List.of()
        );
        PublishedAvailabilityView offStreetAvailability = publishedAvailabilitySnapshotService.findLatest(
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                List.of()
        );
        return new MapViewportResponse(
                queryId,
                MVP_SERVICE_AREA,
                requestedBounds,
                supportedArea,
                storedQuery.response().area().coverageStatus(),
                OffsetDateTime.now(clock),
                0,
                0,
                0,
                offset,
                pageSize,
                0,
                null,
                false,
                "目前地圖範圍不與新莊試點支援範圍相交；可移回支援範圍查看，且不會觸發官方來源刷新。",
                toMapSource(roadsideAvailability),
                toMapSource(offStreetAvailability),
                List.of()
        );
    }

    private void validateMapViewport(
            double south,
            double west,
            double north,
            double east,
            int offset,
            int pageSize
    ) {
        boolean validBounds = Double.isFinite(south) && Double.isFinite(west)
                && Double.isFinite(north) && Double.isFinite(east)
                && south >= -90 && north <= 90 && west >= -180 && east <= 180
                && south < north && west < east;
        if (!validBounds) {
            throw new ParkingQueryContractException(
                    "INVALID_MAP_VIEWPORT",
                    "Map viewport bounds must be valid, ordered latitude and longitude coordinates.",
                    false
            );
        }
        if (offset < 0 || offset > MAX_MAP_VIEWPORT_OFFSET
                || pageSize <= 0 || pageSize > MAX_MAP_VIEWPORT_PAGE_SIZE) {
            throw new ParkingQueryContractException(
                    "INVALID_MAP_VIEWPORT_PAGE",
                    "Map viewport page size must be between 1 and 500 and offset must not exceed 20000.",
                    false
            );
        }
    }

    private MapBounds supportedAreaBounds() {
        return new MapBounds(MVP_MIN_LATITUDE, MVP_MIN_LONGITUDE, MVP_MAX_LATITUDE, MVP_MAX_LONGITUDE);
    }

    private MapBounds intersect(MapBounds first, MapBounds second) {
        double south = Math.max(first.south(), second.south());
        double west = Math.max(first.west(), second.west());
        double north = Math.min(first.north(), second.north());
        double east = Math.min(first.east(), second.east());
        return south < north && west < east ? new MapBounds(south, west, north, east) : null;
    }

    private Map<Long, SourceMappingEntity> findUniqueVerifiedMappings(
            DatasetCode datasetCode,
            List<NearbyParkingPlace> places
    ) {
        if (sourceMappingRepository == null || places.isEmpty()) {
            return Map.of();
        }
        Map<Long, SourceMappingEntity> uniqueMappings = new HashMap<>();
        Set<Long> duplicatePlaceIds = new HashSet<>();
        List<Long> parkingPlaceIds = places.stream().map(NearbyParkingPlace::parkingPlaceId).toList();
        for (int start = 0; start < parkingPlaceIds.size(); start += SOURCE_MAPPING_QUERY_BATCH_SIZE) {
            List<Long> batchIds = parkingPlaceIds.subList(
                    start,
                    Math.min(start + SOURCE_MAPPING_QUERY_BATCH_SIZE, parkingPlaceIds.size())
            );
            List<SourceMappingEntity> mappings = sourceMappingRepository
                    .findBySourceSystemAndDatasetCodeAndMappingStatusAndParkingPlace_IdIn(
                            SourceSystem.NTPC_OPEN_DATA,
                            datasetCode,
                            MappingStatus.VERIFIED,
                            batchIds
                    );
            mappings.forEach(mapping -> {
                Long parkingPlaceId = mapping.getParkingPlace().getId();
                if (uniqueMappings.putIfAbsent(parkingPlaceId, mapping) != null) {
                    duplicatePlaceIds.add(parkingPlaceId);
                }
            });
        }
        duplicatePlaceIds.forEach(uniqueMappings::remove);
        return Map.copyOf(uniqueMappings);
    }

    private List<NearbyParkingPlace> selectVerifiedMapPlaces(
            List<NearbyParkingPlace> places,
            Map<Long, SourceMappingEntity> verifiedMappings,
            int maxMarkers
    ) {
        return places.stream()
                .filter(place -> verifiedMappings.containsKey(place.parkingPlaceId()))
                .sorted(Comparator.comparingInt(NearbyParkingPlace::distanceMeters)
                        .thenComparing(NearbyParkingPlace::parkingPlaceId))
                .limit(maxMarkers)
                .toList();
    }

    private MapMarker toMapMarker(
            NearbyParkingPlace place,
            SourceMappingEntity mapping,
            PublishedAvailabilityView availability,
            boolean roadside
    ) {
        PublishedObservation observation = availability.observations().get(place.parkingPlaceId());
        boolean observationMatchesMapping = observation != null
                && mapping.getSourcePlaceId().equals(observation.sourcePlaceId());
        String status = resolveMapAvailabilityStatus(availability, observation, observationMatchesMapping, roadside);
        Integer availableCarSpaces = null;
        Integer totalCarSpaces = place.totalCarSpaces();
        Boolean availableSpot = null;
        String qualityStatus = null;
        String qualityReason = null;
        OffsetDateTime sourceObservedAt = null;
        OffsetDateTime systemCollectedAt = availability.systemCollectedAt();
        boolean usableQuality = observationMatchesMapping && isUsableMapQuality(observation.qualityStatus());
        if (observationMatchesMapping) {
            qualityStatus = observation.qualityStatus().name();
            qualityReason = observation.qualityReason();
            sourceObservedAt = observation.sourceObservedAt();
            systemCollectedAt = observation.systemFetchedAt() == null
                    ? availability.systemCollectedAt()
                    : observation.systemFetchedAt();
            if (usableQuality && availability.isFreshReady() && place.carEligibleStatus() == EligibilityStatus.CONFIRMED) {
                if (roadside) {
                    availableSpot = observation.availableSpot();
                    if (Boolean.TRUE.equals(availableSpot) && totalCarSpaces != null) {
                        availableCarSpaces = totalCarSpaces;
                    } else if (Boolean.FALSE.equals(availableSpot)) {
                        availableCarSpaces = 0;
                    }
                } else if ((AvailabilityStatus.AVAILABLE == observation.availabilityStatus()
                        || AvailabilityStatus.FULL == observation.availabilityStatus())
                        && observation.availableCarSpaces() != null
                        && observation.availableCarSpaces() >= 0) {
                    availableCarSpaces = observation.availableCarSpaces();
                }
            }
        }
        boolean hasEntranceCoordinates = hasValidCoordinates(place.entranceLatitude(), place.entranceLongitude());
        Double navigationLatitude = roadside || !hasEntranceCoordinates
                ? place.latitude()
                : place.entranceLatitude();
        Double navigationLongitude = roadside || !hasEntranceCoordinates
                ? place.longitude()
                : place.entranceLongitude();
        boolean navigationAvailable = place.carEligibleStatus() == EligibilityStatus.CONFIRMED
                && hasValidCoordinates(navigationLatitude, navigationLongitude);
        if (!navigationAvailable) {
            navigationLatitude = null;
            navigationLongitude = null;
        }
        String dataSource = availability.snapshotId() == null ? LOCAL_CATALOG_SOURCE : "NTPC_OPEN_DATA";
        String itemId = (roadside ? "spot-" : "lot-") + place.parkingPlaceId();
        RoadsideFeeDetails feeDetails = roadside
                ? resolveRoadsideFeeDetails(place, mapping, false)
                : RoadsideFeeDetails.from(place);
        return new MapMarker(
                itemId,
                roadside ? "ROADSIDE" : "OFF_STREET",
                place.displayName(),
                place.latitude(),
                place.longitude(),
                navigationLatitude,
                navigationLongitude,
                place.distanceMeters(),
                status,
                availableCarSpaces,
                totalCarSpaces,
                availableSpot,
                place.carEligibleStatus().name(),
                qualityStatus,
                qualityReason,
                navigationAvailable,
                dataSource + "/" + availability.datasetCode().name(),
                sourceObservedAt,
                systemCollectedAt,
                place.address(),
                place.parkingSpaceType(),
                feeDetails.feeStatus(),
                feeDetails.feeDescription(),
                feeDetails.chargingDays(),
                feeDetails.chargingHours(),
                place.notes(),
                mapMarkerMessage(status, place.carEligibleStatus())
        );
    }

    private String resolveMapAvailabilityStatus(
            PublishedAvailabilityView availability,
            PublishedObservation observation,
            boolean observationMatchesMapping,
            boolean roadside
    ) {
        if ("STALE".equals(availability.status())) {
            return AvailabilityStatus.STALE.name();
        }
        if ("NO_REALTIME_SNAPSHOT".equals(availability.status())) {
            return AvailabilityStatus.NO_REALTIME_AVAILABILITY.name();
        }
        if (!availability.isFreshReady() || !observationMatchesMapping || !isUsableMapQuality(observation.qualityStatus())) {
            return AvailabilityStatus.UNKNOWN.name();
        }
        if (roadside && AvailabilityStatus.AVAILABLE == observation.availabilityStatus()
                && !Boolean.TRUE.equals(observation.availableSpot())) {
            return AvailabilityStatus.UNKNOWN.name();
        }
        return observation.availabilityStatus().name();
    }

    private boolean isUsableMapQuality(QualityStatus qualityStatus) {
        return qualityStatus == QualityStatus.PASS || qualityStatus == QualityStatus.PARTIAL_PASS;
    }

    private MapSource toMapSource(PublishedAvailabilityView availability) {
        String source = availability.snapshotId() == null ? LOCAL_CATALOG_SOURCE : "NTPC_OPEN_DATA";
        return new MapSource(
                availability.status(),
                source,
                availability.snapshotId(),
                availability.sourceObservedAt(),
                availability.systemCollectedAt(),
                availability.message()
        );
    }

    private String mapMarkerMessage(String status, EligibilityStatus eligibilityStatus) {
        if (eligibilityStatus != EligibilityStatus.CONFIRMED) {
            return "一般汽車適用性未確認；不提供一般汽車停車推薦。";
        }
        return switch (status) {
            case "AVAILABLE" -> "目前觀測有位，不代表抵達時仍有位。";
            case "OCCUPIED", "FULL" -> "目前觀測已占用或已滿。";
            case "STALE" -> "觀測資料已過期；不呈現為目前有位。";
            case "NO_REALTIME_AVAILABILITY" -> "尚無已發布的即時觀測。";
            default -> "目前無法確認即時狀態。";
        };
    }

    private boolean hasValidCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
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
                buildRefreshSection(roadsideRefresh, offStreetRefresh),
                existing.googlePlaces());
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
        Map<Long, SourceMappingEntity> roadsideMappings = findUniqueVerifiedMappings(
                DatasetCode.NTPC_ROADSIDE_SPOT,
                roadsidePlaces
        );
        roadsidePlaces.forEach(place -> resolveRoadsideFeeDetails(
                place,
                roadsideMappings.get(place.parkingPlaceId()),
                true
        ));
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
        Integer currentlyAvailableSpaces = resolveRoadsideAvailableCount(availability, roadsidePlaces);
        List<RoadsideSpot> availableSpots = availability.isFreshReady()
                ? roadsidePlaces.stream()
                        .filter(place -> place.carEligibleStatus() == EligibilityStatus.CONFIRMED)
                        .filter(place -> isFreshAvailableRoadsideSpot(availability, place.parkingPlaceId()))
                        .map(place -> toRoadsideSpot(place, roadsideMappings.get(place.parkingPlaceId()), availability))
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

    private Integer resolveRoadsideAvailableCount(
            PublishedAvailabilityView availability,
            List<NearbyParkingPlace> roadsidePlaces
    ) {
        if (!availability.isFreshReady()) {
            return null;
        }
        boolean hasKnownObservation = availability.observations().values().stream()
                .anyMatch(observation -> observation.availabilityStatus() != AvailabilityStatus.UNKNOWN);
        if (!hasKnownObservation) {
            return null;
        }
        Set<Long> confirmedCarPlaces = roadsidePlaces.stream()
                .filter(place -> place.carEligibleStatus() == EligibilityStatus.CONFIRMED)
                .map(NearbyParkingPlace::parkingPlaceId)
                .collect(java.util.stream.Collectors.toSet());
        return (int) availability.observations().values().stream()
                .filter(observation -> confirmedCarPlaces.contains(observation.parkingPlaceId()))
                .filter(this::isAvailableRoadsideObservation)
                .count();
    }

    private boolean isFreshAvailableRoadsideSpot(PublishedAvailabilityView availability, Long parkingPlaceId) {
        PublishedObservation observation = availability.observations().get(parkingPlaceId);
        return observation != null && isAvailableRoadsideObservation(observation);
    }

    private boolean isAvailableRoadsideObservation(PublishedObservation observation) {
        return observation.availabilityStatus() == AvailabilityStatus.AVAILABLE
                && Boolean.TRUE.equals(observation.availableSpot())
                && isUsableMapQuality(observation.qualityStatus());
    }

    private RoadsideFeeDetails resolveRoadsideFeeDetails(
            NearbyParkingPlace place,
            SourceMappingEntity mapping,
            boolean allowSourceRefresh
    ) {
        RoadsideFeeDetails original = RoadsideFeeDetails.from(place);
        if (ntpcRoadsideFeeRateLookupService == null
                || mapping == null
                || hasText(place.feeDescription())) {
            return original;
        }

        String fullRoadName = resolveFullRoadName(place, mapping);
        if (!hasText(fullRoadName)) {
            return original;
        }

        var rateLookup = allowSourceRefresh
                ? ntpcRoadsideFeeRateLookupService.findUniqueRate(MVP_SERVICE_AREA, fullRoadName)
                : ntpcRoadsideFeeRateLookupService.findCachedUniqueRate(MVP_SERVICE_AREA, fullRoadName);
        var officialRate = rateLookup.orElse(null);
        if (officialRate == null) {
            return original;
        }

        String officialSchedule = formatOfficialSchedule(officialRate);
        String description = "官方路段費率：" + officialRate.rates();
        if (!hasText(place.chargingDays()) && !hasText(place.chargingHours()) && hasText(officialSchedule)) {
            description += "（" + officialSchedule + "）";
        }
        return new RoadsideFeeDetails(
                place.feeStatus(),
                description,
                place.chargingDays(),
                hasText(place.chargingHours()) ? place.chargingHours() : officialSchedule
        );
    }

    private String resolveFullRoadName(NearbyParkingPlace place, SourceMappingEntity mapping) {
        String rawNameHint = mapping.getRawNameHint();
        if (hasText(rawNameHint)) {
            int roadNameLabel = indexOfIgnoreCase(rawNameHint, "roadName=");
            if (roadNameLabel >= 0) {
                int valueStart = roadNameLabel + "roadName=".length();
                int valueEnd = rawNameHint.indexOf(';', valueStart);
                return rawNameHint.substring(valueStart, valueEnd < 0 ? rawNameHint.length() : valueEnd).strip();
            }
            int legacyCellId = indexOfIgnoreCase(rawNameHint, " cellid=");
            if (legacyCellId > 0) {
                return rawNameHint.substring(0, legacyCellId).strip();
            }
        }
        return place.displayName();
    }

    private int indexOfIgnoreCase(String value, String target) {
        return value.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
    }

    private String formatOfficialSchedule(NtpcRoadsideFeeRateLookupService.RoadsideFeeRate rate) {
        List<String> periods = new ArrayList<>();
        addSchedulePeriod(periods, "平日", rate.weekdaysTime());
        addSchedulePeriod(periods, "週六", rate.saturdayChargingTime());
        addSchedulePeriod(periods, "週日", rate.sundayChargingTime());
        addSchedulePeriod(periods, "國定假日", rate.nationalHolidaysChargingTime());
        return String.join("；", periods);
    }

    private void addSchedulePeriod(List<String> periods, String label, String value) {
        if (hasText(value)) {
            periods.add(label + " " + value.strip());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private RoadsideSpot toRoadsideSpot(
            NearbyParkingPlace place,
            SourceMappingEntity mapping,
            PublishedAvailabilityView availability
    ) {
        RoadsideFeeDetails feeDetails = resolveRoadsideFeeDetails(place, mapping, false);
        return new RoadsideSpot(
                place.parkingPlaceId().toString(),
                place.displayName(),
                place.latitude(),
                place.longitude(),
                place.distanceMeters(),
                true,
                AvailabilityStatus.AVAILABLE.name(),
                availability.systemCollectedAt(),
                place.totalCarSpaces(),
                place.totalCarSpaces(),
                place.parkingSpaceType(),
                feeDetails.feeStatus(),
                feeDetails.feeDescription(),
                feeDetails.chargingDays(),
                feeDetails.chargingHours(),
                place.notes()
        );
    }

    private ParkingLot toParkingLot(NearbyParkingPlace place, PublishedAvailabilityView availability) {
        PublishedObservation observation = availability.observations().get(place.parkingPlaceId());
        String availabilityStatus = resolveParkingLotAvailabilityStatus(availability, observation);
        Integer availableCarSpaces = resolveParkingLotAvailableCarSpaces(availability, observation);
        return new ParkingLot(
                place.parkingPlaceId().toString(),
                place.displayName(),
                hasValidCoordinates(place.entranceLatitude(), place.entranceLongitude())
                        ? place.entranceLatitude() : place.latitude(),
                hasValidCoordinates(place.entranceLatitude(), place.entranceLongitude())
                        ? place.entranceLongitude() : place.longitude(),
                place.distanceMeters(),
                availableCarSpaces,
                place.totalCarSpaces(),
                place.carEligibleStatus() == EligibilityStatus.CONFIRMED
                        && hasValidCoordinates(
                                hasValidCoordinates(place.entranceLatitude(), place.entranceLongitude())
                                        ? place.entranceLatitude() : place.latitude(),
                                hasValidCoordinates(place.entranceLatitude(), place.entranceLongitude())
                                        ? place.entranceLongitude() : place.longitude()),
                availabilityStatus,
                availability.systemCollectedAt(),
                place.address(),
                place.parkingSpaceType(),
                place.feeDescription(),
                place.chargingHours(),
                place.notes(),
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
        if ((observation.availabilityStatus() == AvailabilityStatus.AVAILABLE
                || observation.availabilityStatus() == AvailabilityStatus.FULL)
                && observation.availableCarSpaces() != null
                && observation.availableCarSpaces() >= 0) {
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
            case "STALE" -> "Roadside snapshot is older than 5 minutes. Current availability is hidden; navigation may remain available for a verified location.";
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
        List<String> commonReasons = predictionReasons(eta, generatedAt);
        boolean hasRoadsideCatalog = nearby.places().stream()
                .anyMatch(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT);
        List<String> roadsideReasons = new ArrayList<>(commonReasons);
        roadsideReasons.addAll(sourcePredictionReasons(roadsideSection.status(), hasRoadsideCatalog));
        if (hasRoadsideCatalog) {
            roadsideReasons.add("CATALOG_COVERAGE_PARTIAL");
        }

        List<OffStreetPrediction> offStreetPredictions = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .map(place -> {
                    ParkingLot lot = offStreetSection.parkingLots().stream()
                            .filter(candidate -> candidate.id().equals(place.parkingPlaceId().toString()))
                            .findFirst()
                            .orElse(null);
                    PredictionResult result = lot == null
                            ? new PredictionResult(
                                    "INSUFFICIENT_DATA", null, null, inputSnapshotVersion(offStreetSection),
                                    dataAgeSeconds(offStreetSection.systemCollectedAt(), generatedAt),
                                    List.of("OFFSTREET_INPUT_MISSING", "BACKTEST_NOT_VERIFIED", "EXPERIMENTAL_MODEL_NOT_PRODUCTION_VALIDATED"),
                                    "A current mapped off-street observation is not available.")
                            : experimentalOffStreetPredictionService.predict(new PredictionInput(
                                    place.carEligibleStatus(),
                                    lot.availabilityStatus(),
                                    lot.availableCarSpaces(),
                                    lot.totalCarSpaces(),
                                    offStreetSection.status(),
                                    eta.status(),
                                    offStreetSection.systemCollectedAt(),
                                    generatedAt,
                                    eta.estimatedArrivalAt(),
                                    inputSnapshotVersion(offStreetSection)));
                    return new OffStreetPrediction(
                            place.parkingPlaceId().toString(),
                            result.status(),
                            result.probability(),
                            result.modelVersion(),
                            result.inputSnapshotVersion(),
                            result.dataAgeSeconds(),
                            result.reasons(),
                            result.message());
                })
                .toList();

        boolean hasExperimentalOffStreetPrediction = offStreetPredictions.stream()
                .anyMatch(prediction -> "EXPERIMENTAL".equals(prediction.status()));
        List<String> sectionReasons = new ArrayList<>();
        if (hasRoadsideCatalog) {
            sectionReasons.addAll(roadsideReasons);
        }
        offStreetPredictions.stream()
                .flatMap(prediction -> prediction.reasons().stream())
                .filter(reason -> !sectionReasons.contains(reason))
                .forEach(sectionReasons::add);
        if (hasExperimentalOffStreetPrediction) {
            sectionReasons.add("EXPERIMENTAL_OFFSTREET_ONLY");
        }

        return new PredictionsSection(
                hasExperimentalOffStreetPrediction ? "EXPERIMENTAL" : "INSUFFICIENT_DATA",
                eta.estimatedArrivalAt(),
                hasExperimentalOffStreetPrediction ? "offstreet-calibrated-persistence-experiment-v1" : null,
                generatedAt,
                List.copyOf(sectionReasons),
                new RoadsidePrediction(
                        "INSUFFICIENT_DATA",
                        null,
                        null,
                        inputSnapshotVersion(roadsideSection),
                        dataAgeSeconds(roadsideSection.systemCollectedAt(), generatedAt),
                        List.copyOf(roadsideReasons),
                        "No roadside model is connected; this area estimate remains unavailable."
                ),
                offStreetPredictions,
                hasExperimentalOffStreetPrediction
                        ? "Experimental off-street estimates are available only for supported cases. They are not production-validated; roadside predictions remain unavailable."
                        : "No supported experimental prediction is available. Current availability and ETA remain independent."
        );
    }

    private List<String> predictionReasons(EtaSection eta, OffsetDateTime generatedAt) {
        List<String> reasons = new ArrayList<>(List.of("MODEL_NOT_LOADED", "BACKTEST_NOT_VERIFIED"));
        if (!"READY".equals(eta.status()) || eta.estimatedArrivalAt() == null) {
            reasons.add("ETA_NOT_READY");
        } else if (!eta.estimatedArrivalAt().isAfter(generatedAt)) {
            reasons.add("ETA_EXPIRED");
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

    private record RoadsideFeeDetails(
            String feeStatus,
            String feeDescription,
            String chargingDays,
            String chargingHours
    ) {
        private static RoadsideFeeDetails from(NearbyParkingPlace place) {
            return new RoadsideFeeDetails(
                    place.feeStatus(),
                    place.feeDescription(),
                    place.chargingDays(),
                    place.chargingHours()
            );
        }
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
