package parking.forecast.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import parking.forecast.adapter.google.GooglePlacesClient;
import parking.forecast.adapter.google.GooglePlacesClientException;
import parking.forecast.adapter.google.dto.SearchNearbyResponse;
import parking.forecast.adapter.google.dto.SearchNearbyResponse.NavigationPoint;
import parking.forecast.adapter.google.dto.SearchNearbyResponse.Place;
import parking.forecast.controller.dto.ParkingQueryResponse.GoogleParkingPlace;
import parking.forecast.controller.dto.ParkingQueryResponse.GooglePlacesSection;

@Service
public class GoogleParkingPlaceSearchService {

    private static final String PROVIDER = "GOOGLE_PLACES";
    private static final int MAX_RADIUS_METERS = 500;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final GooglePlacesClient googlePlacesClient;
    private final Clock clock;

    @Autowired
    public GoogleParkingPlaceSearchService(GooglePlacesClient googlePlacesClient) {
        this(googlePlacesClient, Clock.systemUTC());
    }

    GoogleParkingPlaceSearchService(GooglePlacesClient googlePlacesClient, Clock clock) {
        this.googlePlacesClient = googlePlacesClient;
        this.clock = clock;
    }

    public GooglePlacesSection findNearby(double latitude, double longitude, int radiusMeters) {
        if (radiusMeters <= 0 || radiusMeters > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException("Google Places parking search radius must be between 1 and 500 meters.");
        }

        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        try {
            SearchNearbyResponse response = googlePlacesClient.searchParkingFacilities(latitude, longitude, radiusMeters);
            List<GoogleParkingPlace> items = response.places() == null
                    ? List.of()
                    : response.places().stream()
                            .filter(Objects::nonNull)
                            .filter(this::hasValidLocation)
                            .filter(place -> StringUtils.hasText(place.id()))
                            .filter(place -> distanceMeters(latitude, longitude,
                                    place.location().latitude(), place.location().longitude()) <= radiusMeters)
                            .map(place -> toGoogleParkingPlace(place, latitude, longitude, generatedAt))
                            .sorted(Comparator.comparingInt(GoogleParkingPlace::distanceMeters)
                                    .thenComparing(GoogleParkingPlace::name))
                            .toList();
            return new GooglePlacesSection(
                    items.isEmpty() ? "NO_RESULTS" : "READY",
                    PROVIDER,
                    radiusMeters,
                    generatedAt,
                    items,
                    items.isEmpty()
                            ? "Google Places 未回傳 500 公尺內可辨識的路外停車設施；這不代表該範圍沒有停車位。"
                            : "Google Places 只補充停車設施目錄地點，非官方即時車位資料；Google 項目的可用狀態一律為 UNKNOWN。");
        } catch (GooglePlacesClientException exception) {
            return new GooglePlacesSection(
                    exception.resultStatus(),
                    PROVIDER,
                    radiusMeters,
                    generatedAt,
                    List.of(),
                    exception.getMessage());
        }
    }

    public GooglePlacesSection notRetrieved(int radiusMeters) {
        return new GooglePlacesSection(
                "NOT_RETRIEVED",
                PROVIDER,
                radiusMeters,
                null,
                List.of(),
                "Places 地點內容不寫入查詢快取；若需 Google 輔助地點，請明確重新規劃查詢。");
    }

    private boolean hasValidLocation(Place place) {
        return place.location() != null && hasValidLocation(place.location());
    }

    private boolean hasValidLocation(SearchNearbyResponse.Location location) {
        return location.latitude() != null
                && location.longitude() != null
                && Double.isFinite(location.latitude())
                && Double.isFinite(location.longitude())
                && location.latitude() >= -90.0
                && location.latitude() <= 90.0
                && location.longitude() >= -180.0
                && location.longitude() <= 180.0;
    }

    private NavigationPoint findDrivingParkingNavigationPoint(Place place) {
        List<NavigationPoint> points = place.navigationPoints() == null ? List.of() : place.navigationPoints();
        return points.stream()
                .filter(this::hasValidNavigationLocation)
                .filter(point -> point.travelModes() != null && point.travelModes().contains("DRIVE"))
                .filter(point -> point.usages() != null && point.usages().contains("PARKING"))
                .findFirst()
                .orElseGet(() -> points.stream()
                        .filter(this::hasValidNavigationLocation)
                        .filter(point -> point.travelModes() != null && point.travelModes().contains("DRIVE"))
                        .filter(point -> point.usages() == null || point.usages().isEmpty())
                        .findFirst()
                        .orElse(null));
    }

    private boolean hasValidNavigationLocation(NavigationPoint point) {
        return point != null && point.location() != null && hasValidLocation(point.location());
    }

    private GoogleParkingPlace toGoogleParkingPlace(
            Place place,
            double centerLatitude,
            double centerLongitude,
            OffsetDateTime generatedAt) {
        double latitude = place.location().latitude();
        double longitude = place.location().longitude();
        NavigationPoint navigationPoint = findDrivingParkingNavigationPoint(place);
        Double navigationLatitude = navigationPoint == null ? null : navigationPoint.location().latitude();
        Double navigationLongitude = navigationPoint == null ? null : navigationPoint.location().longitude();
        return new GoogleParkingPlace(
                place.id(),
                place.displayName() == null || !StringUtils.hasText(place.displayName().text())
                        ? "未命名停車設施"
                        : place.displayName().text(),
                latitude,
                longitude,
                navigationLatitude,
                navigationLongitude,
                navigationPoint != null,
                Math.toIntExact(Math.round(distanceMeters(centerLatitude, centerLongitude, latitude, longitude))),
                place.formattedAddress(),
                place.googleMapsUri(),
                "UNKNOWN",
                PROVIDER,
                null,
                generatedAt,
                "Google Places 目錄地點，沒有即時車位觀測。"
        );
    }

    private double distanceMeters(double latitude1, double longitude1, double latitude2, double longitude2) {
        double latitude1Radians = Math.toRadians(latitude1);
        double latitude2Radians = Math.toRadians(latitude2);
        double deltaLatitude = Math.toRadians(latitude2 - latitude1);
        double deltaLongitude = Math.toRadians(longitude2 - longitude1);
        double haversine = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(latitude1Radians) * Math.cos(latitude2Radians)
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}
