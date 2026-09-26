package parking.forecast.adapter.ntpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import parking.forecast.adapter.ntpc.dto.NtpcOffStreetParkingAvailabilityDto;
import parking.forecast.adapter.ntpc.dto.NtpcRoadsideParkingAvailabilityDto;

class NtpcOpenDataDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesRoadsideAvailabilityRawFields() throws Exception {
        var resource = new ClassPathResource("fixtures/ntpc/roadside-availability.json");

        List<NtpcRoadsideParkingAvailabilityDto> rows = objectMapper.readValue(
                resource.getInputStream(),
                new TypeReference<>() {
                });

        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.id()).isEqualTo("1");
        assertThat(row.cellId()).isEqualTo("RA-001");
        assertThat(row.roadName()).isEqualTo("新莊路");
        assertThat(row.cellStatus()).isEqualTo("0");
        assertThat(row.parkingStatus()).isEqualTo("0");
        assertThat(row.latitude()).isEqualTo("25.0372");
        assertThat(row.longitude()).isEqualTo("121.4525");
        assertThat(row.areaCode()).isEqualTo("65000050");
    }

    @Test
    void parsesOffStreetAvailabilityAvailableCarAliases() throws Exception {
        var resource = new ClassPathResource("fixtures/ntpc/off-street-availability.json");

        List<NtpcOffStreetParkingAvailabilityDto> rows = objectMapper.readValue(
                resource.getInputStream(),
                new TypeReference<>() {
                });

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).id()).isEqualTo("P001");
        assertThat(rows.get(0).availableCar()).isEqualTo("12");
        assertThat(rows.get(1).id()).isEqualTo("P002");
        assertThat(rows.get(1).availableCar()).isEqualTo("-9");
    }
}
