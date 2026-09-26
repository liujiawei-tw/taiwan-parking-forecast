package parking.forecast.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import parking.forecast.controller.dto.CreateParkingQueryRequest;
import parking.forecast.controller.dto.CreateParkingQueryRequest.DepartureInput;
import parking.forecast.controller.dto.CreateParkingQueryRequest.LocationInput;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ParkingQueryControllerTest {

    @Autowired
    private ParkingQueryController controller;

    @Test
    void createQueryReturnsLocalCatalogResultsInsideThreeHundredMeters() {
        var response = controller.create(mockRequestNearSeedCatalog());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().query().queryId()).isNotBlank();
        assertThat(response.getBody().query().status()).isEqualTo("READY");
        assertThat(response.getBody().area().radiusMeters()).isEqualTo(300);
        assertThat(response.getBody().area().distanceMode()).isEqualTo("STRAIGHT_LINE");
        assertThat(response.getBody().area().coverageStatus()).isEqualTo("PARTIAL");
        assertThat(response.getBody().eta().status()).isEqualTo("READY");
        assertThat(response.getBody().roadside().source()).isEqualTo("LOCAL_CATALOG_SEED");
        assertThat(response.getBody().roadside().currentlyAvailableSpaces()).isNull();
        assertThat(response.getBody().roadside().availableSpots())
                .extracting(spot -> spot.label())
                .contains("新莊中正路示範路邊汽車格 A");
        assertThat(response.getBody().offStreet().source()).isEqualTo("LOCAL_CATALOG_SEED");
        assertThat(response.getBody().offStreet().parkingLots())
                .extracting(lot -> lot.name())
                .contains("新莊區公所周邊示範停車場");
        assertThat(response.getBody().offStreet().parkingLots())
                .allSatisfy(lot -> assertThat(lot.availableCarSpaces()).isNull());
        assertThat(response.getBody().predictions().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void createdQueryCanBeReadAndRefreshed() {
        var created = controller.create(mockRequestNearSeedCatalog()).getBody();

        var read = controller.get(created.query().queryId());
        var refreshed = controller.refresh(created.query().queryId());

        assertThat(read.query().queryId()).isEqualTo(created.query().queryId());
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refreshed.getBody().refresh().roadside().action()).isEqualTo("USED_LOCAL_CATALOG");
    }

    @Test
    void createQueryReturnsNoCatalogResultsWhenDestinationIsOutsideSeedArea() {
        var response = controller.create(mockRequestOutsideSeedCatalog());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().area().coverageStatus()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().roadside().status()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().roadside().availableSpots()).isEmpty();
        assertThat(response.getBody().offStreet().status()).isEqualTo("NO_CATALOG_RESULTS");
        assertThat(response.getBody().offStreet().parkingLots()).isEmpty();
        assertThat(response.getBody().predictions().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    private CreateParkingQueryRequest mockRequestNearSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-001",
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("新莊體育館", 25.03720, 121.45250),
                new DepartureInput("NOW", null));
    }

    private CreateParkingQueryRequest mockRequestOutsideSeedCatalog() {
        return new CreateParkingQueryRequest(
                "mock-token-002",
                new LocationInput("目前位置", 25.04000, 121.45000),
                new LocationInput("新莊遠端測試點", 25.08000, 121.50000),
                new DepartureInput("NOW", null));
    }
}
