package parking.forecast.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.DatasetCode;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.MappingStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.domain.SourceMappingEntity;
import parking.forecast.domain.SourceSystem;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class XinzhuangSeedRepositoryTest {

    @Autowired
    private ParkingPlaceRepository parkingPlaceRepository;

    @Autowired
    private SourceMappingRepository sourceMappingRepository;

    @Test
    void testProfileLoadsXinzhuangCatalogSeed() {
        assertThat(parkingPlaceRepository.findByServiceAreaAndActiveStatus(
                "NEW_TAIPEI_XINZHUANG",
                ActiveStatus.ACTIVE
        ))
                .extracting(ParkingPlaceEntity::getDisplayName)
                .contains(
                        "中華路一段路邊汽車格 20",
                        "化成路路邊汽車格 2",
                        "宜家家居股份有限公司新莊分公司停車場",
                        "新莊區農會停車場",
                        "博客停車場藏壽司思源場停車場"
                );

        assertThat(parkingPlaceRepository.findByServiceAreaAndPlaceTypeAndActiveStatus(
                "NEW_TAIPEI_XINZHUANG",
                ParkingPlaceType.ON_STREET_SPOT,
                ActiveStatus.ACTIVE
        ))
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(place -> assertThat(place.getCarEligibleStatus()).isEqualTo(EligibilityStatus.CONFIRMED));

        assertThat(parkingPlaceRepository.findByServiceAreaAndPlaceTypeAndActiveStatus(
                "NEW_TAIPEI_XINZHUANG",
                ParkingPlaceType.OFF_STREET_LOT,
                ActiveStatus.ACTIVE
        ))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(place -> {
                    assertThat(place.getCarEligibleStatus()).isEqualTo(EligibilityStatus.CONFIRMED);
                    assertThat(place.getEntranceLatitude()).isNotNull();
                    assertThat(place.getEntranceLongitude()).isNotNull();
                });
    }

    @Test
    void seedCatalogHasSourceMappingsForRoadsideAndOffStreetPlaces() {
        SourceMappingEntity roadsideMapping = sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_ROADSIDE_SPOT,
                "96316"
        ).orElseThrow();

        SourceMappingEntity offStreetMapping = sourceMappingRepository.findBySourceSystemAndDatasetCodeAndSourcePlaceId(
                SourceSystem.NTPC_OPEN_DATA,
                DatasetCode.NTPC_OFF_STREET_AVAILABILITY,
                "050087"
        ).orElseThrow();

        assertThat(roadsideMapping.getMappingStatus()).isEqualTo(MappingStatus.VERIFIED);
        assertThat(roadsideMapping.getParkingPlace().getPlaceType()).isEqualTo(ParkingPlaceType.ON_STREET_SPOT);
        assertThat(roadsideMapping.getParkingPlace().getServiceArea()).isEqualTo("NEW_TAIPEI_XINZHUANG");

        assertThat(offStreetMapping.getMappingStatus()).isEqualTo(MappingStatus.VERIFIED);
        assertThat(offStreetMapping.getParkingPlace().getPlaceType()).isEqualTo(ParkingPlaceType.OFF_STREET_LOT);
        assertThat(offStreetMapping.getParkingPlace().getServiceArea()).isEqualTo("NEW_TAIPEI_XINZHUANG");
    }
}
