package parking.forecast.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingPlace;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchRequest;
import parking.forecast.service.NearbyParkingSearchService.NearbyParkingSearchResult;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NearbyParkingSearchServiceTest {

    @Autowired
    private NearbyParkingSearchService nearbyParkingSearchService;

    @Test
    void findsSeedPlacesWithinThreeHundredMetersAndSortsByDistance() {
        NearbyParkingSearchResult result = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                "NEW_TAIPEI_XINZHUANG",
                25.0372000,
                121.4525000,
                300
        ));

        assertThat(result.distanceMode()).isEqualTo("STRAIGHT_LINE");
        assertThat(result.radiusMeters()).isEqualTo(300);
        assertThat(result.roadsideCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.offStreetCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.places())
                .extracting(NearbyParkingPlace::displayName)
                .contains(
                        "新莊中正路示範路邊汽車格 A",
                        "新莊區公所周邊示範停車場"
                )
                .doesNotContain("新莊運動中心周邊示範停車場");

        List<Integer> distances = result.places().stream()
                .map(NearbyParkingPlace::distanceMeters)
                .toList();
        assertThat(distances).isSorted();
        assertThat(distances).allSatisfy(distance -> assertThat(distance).isBetween(0, 300));
    }

    @Test
    void canLimitResultsToRoadsideOrOffStreetFromSearchResult() {
        NearbyParkingSearchResult result = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                "NEW_TAIPEI_XINZHUANG",
                25.0372000,
                121.4525000,
                300
        ));

        List<NearbyParkingPlace> roadsidePlaces = result.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .toList();
        List<NearbyParkingPlace> offStreetPlaces = result.places().stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .toList();

        assertThat(roadsidePlaces).isNotEmpty();
        assertThat(offStreetPlaces).isNotEmpty();
        assertThat(roadsidePlaces).hasSize((int) result.roadsideCount());
        assertThat(offStreetPlaces).hasSize((int) result.offStreetCount());
    }

    @Test
    void returnsEmptyResultWhenNoPlaceFallsInsideRadius() {
        NearbyParkingSearchResult result = nearbyParkingSearchService.findNearby(new NearbyParkingSearchRequest(
                "NEW_TAIPEI_XINZHUANG",
                25.0800000,
                121.5000000,
                300
        ));

        assertThat(result.places()).isEmpty();
        assertThat(result.roadsideCount()).isZero();
        assertThat(result.offStreetCount()).isZero();
    }

    @Test
    void rejectsInvalidRadius() {
        assertThatIllegalArgumentException().isThrownBy(() -> nearbyParkingSearchService.findNearby(
                new NearbyParkingSearchRequest("NEW_TAIPEI_XINZHUANG", 25.0372000, 121.4525000, 0)
        ));
    }
}
