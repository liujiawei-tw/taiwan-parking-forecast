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
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingPlace;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchRequest;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchResult;

@Service
public class ParkingQueryMockService {

    private static final String MVP_SERVICE_AREA = "NEW_TAIPEI_XINZHUANG";
    private static final String MVP_AREA_CODE = "NEW_TAIPEI_XINZHUANG_MVP";
    private static final int SEARCH_RADIUS_METERS = 300;

    private final Clock clock;
    private final NearbyParkingSearchService nearbyParkingSearchService;
    private final Map<String, ParkingQueryResponse> queries = new ConcurrentHashMap<>();

    @Autowired
    public ParkingQueryMockService(NearbyParkingSearchService nearbyParkingSearchService) {
        this(Clock.systemUTC(), nearbyParkingSearchService);
    }

    ParkingQueryMockService(Clock clock, NearbyParkingSearchService nearbyParkingSearchService) {
        this.clock = clock;
        this.nearbyParkingSearchService = nearbyParkingSearchService;
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
                buildRoadsideSection(nearby),
                buildOffStreetSection(nearby),
                buildPredictionsSection(nearby, estimatedArrivalAt),
                new RefreshSection(
                        new SourceRefresh("ROADSIDE", "USED_LOCAL_CATALOG", false, null, null, "S2-013 uses local catalog only; official roadside availability is not connected yet."),
                        new SourceRefresh("OFF_STREET", "USED_LOCAL_CATALOG", false, null, null, "S2-013 uses local catalog only; official off street availability is not connected yet.")));

        queries.put(queryId, response);
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
        OffsetDateTime now = OffsetDateTime.now(clock);
        ParkingQueryResponse refreshed = new ParkingQueryResponse(
                new QuerySection(
                        existing.query().queryId(),
                        existing.query().status(),
                        false,
                        existing.query().createdAt(),
                        now,
                        existing.query().expiresAt(),
                        "Mock refresh reused the local catalog query result."),
                existing.destination(),
                existing.area(),
                existing.eta(),
                existing.roadside(),
                existing.offStreet(),
                existing.predictions(),
                new RefreshSection(
                        new SourceRefresh("ROADSIDE", "USED_LOCAL_CATALOG", false, null, null, "Local catalog result is unchanged; official roadside availability is not connected yet."),
                        new SourceRefresh("OFF_STREET", "USED_LOCAL_CATALOG", false, null, null, "Local catalog result is unchanged; official off street availability is not connected yet.")));
        queries.put(queryId, refreshed);
        return refreshed;
    }

    private RoadsideSection buildRoadsideSection(NearbyParkingSearchResult nearby) {
        List<RoadsideSpot> roadsideSpots = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .map(place -> new RoadsideSpot(
                        place.parkingPlaceId().toString(),
                        place.displayName(),
                        place.latitude(),
                        place.longitude(),
                        place.distanceMeters(),
                        true
                ))
                .toList();

        return new RoadsideSection(
                roadsideSpots.isEmpty() ? "NO_CATALOG_RESULTS" : "READY",
                "LOCAL_CATALOG_SEED",
                null,
                null,
                roadsideSpots,
                roadsideSpots.isEmpty()
                        ? "No roadside catalog records were found inside 300 meters."
                        : "Catalog roadside records inside 300 meters. Real-time availability is not connected yet."
        );
    }

    private OffStreetSection buildOffStreetSection(NearbyParkingSearchResult nearby) {
        List<ParkingLot> parkingLots = nearby.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .map(place -> new ParkingLot(
                        place.parkingPlaceId().toString(),
                        place.displayName(),
                        place.entranceLatitude() == null ? place.latitude() : place.entranceLatitude(),
                        place.entranceLongitude() == null ? place.longitude() : place.entranceLongitude(),
                        place.distanceMeters(),
                        null,
                        true
                ))
                .toList();

        return new OffStreetSection(
                parkingLots.isEmpty() ? "NO_CATALOG_RESULTS" : "READY",
                "LOCAL_CATALOG_SEED",
                null,
                parkingLots,
                parkingLots.isEmpty()
                        ? "No off street catalog records were found inside 300 meters."
                        : "Catalog off street parking lots inside 300 meters. Real-time availability is not connected yet."
        );
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
                "Prediction contract is present, but S2-013 does not output probabilities."
        );
    }

    private String resolveCoverageStatus(NearbyParkingSearchResult nearby) {
        return nearby.places().isEmpty() ? "NO_CATALOG_RESULTS" : "PARTIAL";
    }

    public static class ParkingQueryNotFoundException extends RuntimeException {

        public ParkingQueryNotFoundException(String queryId) {
            super("Parking query not found: " + queryId);
        }
    }
}
