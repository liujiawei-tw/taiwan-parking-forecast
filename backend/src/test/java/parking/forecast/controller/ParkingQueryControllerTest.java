package parking.forecast.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.CreateParkingQueryRequest.DepartureInput;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;
import parking.forecast.service.ParkingQueryMockService;

class ParkingQueryControllerTest {

    private final ParkingQueryController controller = new ParkingQueryController(new ParkingQueryMockService());

    @Test
    void createQueryReturnsMockContract() {
        var response = controller.create(mockRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query().queryId()).isNotBlank();
        assertThat(response.getBody().query().status()).isEqualTo("READY");
        assertThat(response.getBody().area().radiusMeters()).isEqualTo(300);
        assertThat(response.getBody().eta().status()).isEqualTo("READY");
        assertThat(response.getBody().roadside().availableSpots()).hasSize(2);
        assertThat(response.getBody().offStreet().parkingLots()).hasSize(2);
        assertThat(response.getBody().predictions().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void createdQueryCanBeReadAndRefreshed() {
        var created = controller.create(mockRequest()).getBody();

        var read = controller.get(created.query().queryId());
        var refreshed = controller.refresh(created.query().queryId());

        assertThat(read.query().queryId()).isEqualTo(created.query().queryId());
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refreshed.getBody().refresh().roadside().action()).isEqualTo("USED_FRESH_MOCK_SNAPSHOT");
    }

    private CreateParkingQueryRequest mockRequest() {
        return new CreateParkingQueryRequest(
                "mock-token-001",
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("新莊體育館", 25.03600, 121.45050),
                new DepartureInput("NOW", null));
    }
}
