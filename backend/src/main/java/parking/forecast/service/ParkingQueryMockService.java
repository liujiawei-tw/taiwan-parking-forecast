package parking.forecast.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

@Service
public class ParkingQueryMockService {

    private final Clock clock;
    private final Map<String, ParkingQueryResponse> queries = new ConcurrentHashMap<>();

    public ParkingQueryMockService() {
        this(Clock.systemUTC());
    }

    ParkingQueryMockService(Clock clock) {
        this.clock = clock;
    }

    public ParkingQueryResponse create(CreateParkingQueryRequest request) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime departureAt = "SCHEDULED".equalsIgnoreCase(request.departure().type())
                && request.departure().departureAt() != null
                        ? OffsetDateTime.parse(request.departure().departureAt())
                        : now;
        OffsetDateTime estimatedArrivalAt = departureAt.plusMinutes(18);
        String queryId = UUID.randomUUID().toString();

        ParkingQueryResponse response = new ParkingQueryResponse(
                new QuerySection(queryId, "READY", false, now, now, now.plusMinutes(30), "Mock query is ready."),
                new DestinationSection(
                        request.destination().label(),
                        request.destination().latitude(),
                        request.destination().longitude(),
                        "SUPPORTED",
                        "Mock response assumes the destination is inside the Xinzhuang MVP area."),
                new AreaSection(
                        300,
                        "STRAIGHT_LINE",
                        "NEW_TAIPEI_XINZHUANG_MVP",
                        "PARTIAL",
                        "Mock coverage includes sample roadside and off street records only."),
                new EtaSection(
                        "READY",
                        "MOCK_GOOGLE_ROUTES",
                        departureAt,
                        estimatedArrivalAt,
                        18 * 60,
                        "Mock ETA. Google Routes is not connected in S2-006."),
                new RoadsideSection(
                        "READY",
                        "MOCK_NTPC_ROADSIDE",
                        now.minusMinutes(2),
                        2,
                        List.of(
                                new RoadsideSpot("mock-roadside-001", "新莊路示範汽車格位 1", 25.03590, 121.45010, 120, true),
                                new RoadsideSpot("mock-roadside-002", "中正路示範汽車格位 2", 25.03625, 121.45125, 210, true)),
                        "Mock roadside availability. Official New Taipei API is not connected yet."),
                new OffStreetSection(
                        "READY",
                        "MOCK_NTPC_OFF_STREET",
                        now.minusMinutes(3),
                        List.of(
                                new ParkingLot("mock-lot-001", "新莊示範停車場 A", 25.03650, 121.44980, 180, 12, true),
                                new ParkingLot("mock-lot-002", "新莊示範停車場 B", 25.03495, 121.45200, 260, 0, true)),
                        "Mock off street availability. Official New Taipei API is not connected yet."),
                new PredictionsSection(
                        "INSUFFICIENT_DATA",
                        estimatedArrivalAt,
                        new RoadsidePrediction("INSUFFICIENT_DATA", null, "No validated model or baseline is connected yet."),
                        List.of(
                                new OffStreetPrediction("mock-lot-001", "INSUFFICIENT_DATA", null, "No validated model or baseline is connected yet."),
                                new OffStreetPrediction("mock-lot-002", "INSUFFICIENT_DATA", null, "No validated model or baseline is connected yet.")),
                        "Prediction contract is present, but S2-006 does not output probabilities."),
                new RefreshSection(
                        new SourceRefresh("ROADSIDE", "USED_MOCK_SNAPSHOT", false, now.minusMinutes(2), now.plusMinutes(3), "Mock refresh only."),
                        new SourceRefresh("OFF_STREET", "USED_MOCK_SNAPSHOT", false, now.minusMinutes(3), now.plusMinutes(2), "Mock refresh only.")));

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
                        "Mock refresh checked the same query."),
                existing.destination(),
                existing.area(),
                existing.eta(),
                existing.roadside(),
                existing.offStreet(),
                existing.predictions(),
                new RefreshSection(
                        new SourceRefresh("ROADSIDE", "USED_FRESH_MOCK_SNAPSHOT", false, now.minusMinutes(2), now.plusMinutes(3), "Mock data is still fresh."),
                        new SourceRefresh("OFF_STREET", "USED_FRESH_MOCK_SNAPSHOT", false, now.minusMinutes(3), now.plusMinutes(2), "Mock data is still fresh.")));
        queries.put(queryId, refreshed);
        return refreshed;
    }

    public static class ParkingQueryNotFoundException extends RuntimeException {

        public ParkingQueryNotFoundException(String queryId) {
            super("Parking query not found: " + queryId);
        }
    }
}
