package parking.forecast.adapter.google.dto;

import java.util.List;

public record ComputeRoutesResponse(List<Route> routes) {

    public record Route(
            String duration,
            String staticDuration,
            Integer distanceMeters) {
    }
}
