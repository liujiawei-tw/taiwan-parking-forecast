package parking.forecast.service;

import java.util.Optional;

import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.ParkingQueryResponse;

public interface ParkingQueryStateStore {

    Optional<String> findQueryIdByRequestToken(String requestToken);

    Optional<StoredParkingQuery> findByQueryId(String queryId);

    void save(String requestToken, StoredParkingQuery query);

    record StoredParkingQuery(
            ParkingQueryResponse response,
            CreateParkingQueryRequest request
    ) {
    }
}
