package parking.forecast.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateParkingQueryRequest(
        @NotBlank String requestToken,
        @Valid LocationInput origin,
        @Valid @NotNull LocationInput destination,
        @Valid @NotNull DepartureInput departure) {

    public record LocationInput(
            @NotBlank String label,
            @NotNull Double latitude,
            @NotNull Double longitude) {
    }

    public record DepartureInput(
            @NotBlank String type,
            String departureAt) {
    }
}
