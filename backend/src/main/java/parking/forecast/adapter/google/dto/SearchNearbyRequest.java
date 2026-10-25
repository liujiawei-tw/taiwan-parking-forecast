package parking.forecast.adapter.google.dto;

import java.util.List;

public record SearchNearbyRequest(
        List<String> includedTypes,
        int maxResultCount,
        String languageCode,
        String regionCode,
        LocationRestriction locationRestriction,
        String rankPreference) {

    public record LocationRestriction(Circle circle) { }
    public record Circle(Center center, double radius) { }
    public record Center(double latitude, double longitude) { }
}
