package parking.forecast.adapter.google.dto;

public record ComputeRoutesRequest(
        RouteWaypoint origin,
        RouteWaypoint destination,
        String travelMode,
        String routingPreference,
        String departureTime) {

    public record RouteWaypoint(RouteLocation location) {

        public static RouteWaypoint of(double latitude, double longitude) {
            return new RouteWaypoint(new RouteLocation(new LatLng(latitude, longitude)));
        }
    }

    public record RouteLocation(LatLng latLng) {
    }

    public record LatLng(double latitude, double longitude) {
    }
}
