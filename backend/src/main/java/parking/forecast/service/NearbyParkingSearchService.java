package parking.forecast.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import parking.forecast.domain.ActiveStatus;
import parking.forecast.domain.EligibilityStatus;
import parking.forecast.domain.ParkingPlaceEntity;
import parking.forecast.domain.ParkingPlaceType;
import parking.forecast.repository.ParkingPlaceRepository;

@Service
public class NearbyParkingSearchService {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double METERS_PER_LATITUDE_DEGREE = 111_320.0;

    private final ParkingPlaceRepository parkingPlaceRepository;

    public NearbyParkingSearchService(ParkingPlaceRepository parkingPlaceRepository) {
        this.parkingPlaceRepository = parkingPlaceRepository;
    }

    @Transactional(readOnly = true)
    public NearbyParkingSearchResult findNearby(NearbyParkingSearchRequest request) {
        validateRequest(request);

        BoundingBox boundingBox = BoundingBox.from(request.latitude(), request.longitude(), request.radiusMeters());
        List<ParkingPlaceEntity> candidates = parkingPlaceRepository
                .findByServiceAreaAndActiveStatusAndLatitudeBetweenAndLongitudeBetween(
                        request.serviceArea(),
                        ActiveStatus.ACTIVE,
                        BigDecimal.valueOf(boundingBox.minLatitude()),
                        BigDecimal.valueOf(boundingBox.maxLatitude()),
                        BigDecimal.valueOf(boundingBox.minLongitude()),
                        BigDecimal.valueOf(boundingBox.maxLongitude())
                );

        List<NearbyParkingPlace> places = candidates.stream()
                .map(place -> toNearbyPlace(place, request.latitude(), request.longitude()))
                .filter(place -> place.distanceMeters() <= request.radiusMeters())
                .sorted(Comparator.comparingInt(NearbyParkingPlace::distanceMeters)
                        .thenComparing(NearbyParkingPlace::displayName))
                .toList();

        long roadsideCount = places.stream()
                .filter(place -> place.placeType() == ParkingPlaceType.ON_STREET_SPOT)
                .count();
        long offStreetCount = places.stream()
                .filter(place -> place.placeType() == ParkingPlaceType.OFF_STREET_LOT)
                .count();

        return new NearbyParkingSearchResult(
                request.serviceArea(),
                request.latitude(),
                request.longitude(),
                request.radiusMeters(),
                "STRAIGHT_LINE",
                places,
                roadsideCount,
                offStreetCount
        );
    }

    private void validateRequest(NearbyParkingSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nearby parking search request is required.");
        }
        if (request.serviceArea() == null || request.serviceArea().isBlank()) {
            throw new IllegalArgumentException("Service area is required.");
        }
        if (request.radiusMeters() <= 0) {
            throw new IllegalArgumentException("Radius meters must be greater than 0.");
        }
        if (request.latitude() < -90.0 || request.latitude() > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90.");
        }
        if (request.longitude() < -180.0 || request.longitude() > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180.");
        }
    }

    private NearbyParkingPlace toNearbyPlace(ParkingPlaceEntity place, double originLatitude, double originLongitude) {
        double latitude = place.getLatitude().doubleValue();
        double longitude = place.getLongitude().doubleValue();
        Integer distanceMeters = (int) Math.round(calculateDistanceMeters(originLatitude, originLongitude, latitude, longitude));

        return new NearbyParkingPlace(
                place.getId(),
                place.getPlaceType(),
                place.getDisplayName(),
                latitude,
                longitude,
                place.getEntranceLatitude() == null ? null : place.getEntranceLatitude().doubleValue(),
                place.getEntranceLongitude() == null ? null : place.getEntranceLongitude().doubleValue(),
                place.getCarEligibleStatus(),
                distanceMeters
        );
    }

    private double calculateDistanceMeters(double originLatitude, double originLongitude, double targetLatitude, double targetLongitude) {
        double originLatitudeRadians = Math.toRadians(originLatitude);
        double targetLatitudeRadians = Math.toRadians(targetLatitude);
        double deltaLatitude = Math.toRadians(targetLatitude - originLatitude);
        double deltaLongitude = Math.toRadians(targetLongitude - originLongitude);

        double haversine = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(originLatitudeRadians) * Math.cos(targetLatitudeRadians)
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    public record NearbyParkingSearchRequest(
            String serviceArea,
            double latitude,
            double longitude,
            int radiusMeters
    ) {
    }

    public record NearbyParkingSearchResult(
            String serviceArea,
            double latitude,
            double longitude,
            int radiusMeters,
            String distanceMode,
            List<NearbyParkingPlace> places,
            long roadsideCount,
            long offStreetCount
    ) {
    }

    public record NearbyParkingPlace(
            Long parkingPlaceId,
            ParkingPlaceType placeType,
            String displayName,
            double latitude,
            double longitude,
            Double entranceLatitude,
            Double entranceLongitude,
            EligibilityStatus carEligibleStatus,
            int distanceMeters
    ) {
    }

    private record BoundingBox(
            double minLatitude,
            double maxLatitude,
            double minLongitude,
            double maxLongitude
    ) {
        private static BoundingBox from(double latitude, double longitude, int radiusMeters) {
            double latitudeDelta = radiusMeters / METERS_PER_LATITUDE_DEGREE;
            double latitudeRadians = Math.toRadians(latitude);
            double cosineLatitude = Math.cos(latitudeRadians);
            double longitudeDelta = Math.abs(cosineLatitude) < 0.000001
                    ? 180.0
                    : radiusMeters / (METERS_PER_LATITUDE_DEGREE * cosineLatitude);
            return new BoundingBox(
                    Math.max(-90.0, latitude - latitudeDelta),
                    Math.min(90.0, latitude + latitudeDelta),
                    Math.max(-180.0, longitude - longitudeDelta),
                    Math.min(180.0, longitude + longitudeDelta)
            );
        }
    }
}

