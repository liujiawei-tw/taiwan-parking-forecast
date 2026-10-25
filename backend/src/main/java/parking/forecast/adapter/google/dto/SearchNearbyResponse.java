package parking.forecast.adapter.google.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchNearbyResponse(List<Place> places) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(
            String id,
            DisplayName displayName,
            Location location,
            String formattedAddress,
            String googleMapsUri,
            List<NavigationPoint> navigationPoints) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NavigationPoint(Location location, List<String> travelModes, List<String> usages) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisplayName(String text, String languageCode) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(Double latitude, Double longitude) { }
}
