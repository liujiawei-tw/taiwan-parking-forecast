package parking.forecast.domain;

import java.time.Instant;

public record NormalizedAvailabilityObservation(
        SourceSystem sourceSystem,
        DatasetCode datasetCode,
        String sourcePlaceId,
        AvailabilityStatus availabilityStatus,
        Integer availableCarSpaces,
        Boolean availableSpot,
        String rawStatusCode,
        String rawAvailabilityValue,
        Instant sourceObservedAt,
        Instant systemFetchedAt,
        QualityStatus qualityStatus,
        String qualityReason,
        EligibilityStatus carEligibleStatus,
        boolean currentlyRecommendable) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private SourceSystem sourceSystem;
        private DatasetCode datasetCode;
        private String sourcePlaceId;
        private AvailabilityStatus availabilityStatus = AvailabilityStatus.UNKNOWN;
        private Integer availableCarSpaces;
        private Boolean availableSpot;
        private String rawStatusCode;
        private String rawAvailabilityValue;
        private Instant sourceObservedAt;
        private Instant systemFetchedAt;
        private QualityStatus qualityStatus = QualityStatus.UNKNOWN;
        private String qualityReason;
        private EligibilityStatus carEligibleStatus = EligibilityStatus.UNKNOWN;
        private boolean currentlyRecommendable;

        public Builder sourceSystem(SourceSystem sourceSystem) {
            this.sourceSystem = sourceSystem;
            return this;
        }

        public Builder datasetCode(DatasetCode datasetCode) {
            this.datasetCode = datasetCode;
            return this;
        }

        public Builder sourcePlaceId(String sourcePlaceId) {
            this.sourcePlaceId = sourcePlaceId;
            return this;
        }

        public Builder availabilityStatus(AvailabilityStatus availabilityStatus) {
            this.availabilityStatus = availabilityStatus;
            return this;
        }

        public Builder availableCarSpaces(Integer availableCarSpaces) {
            this.availableCarSpaces = availableCarSpaces;
            return this;
        }

        public Builder availableSpot(Boolean availableSpot) {
            this.availableSpot = availableSpot;
            return this;
        }

        public Builder rawStatusCode(String rawStatusCode) {
            this.rawStatusCode = rawStatusCode;
            return this;
        }

        public Builder rawAvailabilityValue(String rawAvailabilityValue) {
            this.rawAvailabilityValue = rawAvailabilityValue;
            return this;
        }

        public Builder sourceObservedAt(Instant sourceObservedAt) {
            this.sourceObservedAt = sourceObservedAt;
            return this;
        }

        public Builder systemFetchedAt(Instant systemFetchedAt) {
            this.systemFetchedAt = systemFetchedAt;
            return this;
        }

        public Builder qualityStatus(QualityStatus qualityStatus) {
            this.qualityStatus = qualityStatus;
            return this;
        }

        public Builder qualityReason(String qualityReason) {
            this.qualityReason = qualityReason;
            return this;
        }

        public Builder carEligibleStatus(EligibilityStatus carEligibleStatus) {
            this.carEligibleStatus = carEligibleStatus;
            return this;
        }

        public Builder currentlyRecommendable(boolean currentlyRecommendable) {
            this.currentlyRecommendable = currentlyRecommendable;
            return this;
        }

        public NormalizedAvailabilityObservation build() {
            return new NormalizedAvailabilityObservation(
                    sourceSystem,
                    datasetCode,
                    sourcePlaceId,
                    availabilityStatus,
                    availableCarSpaces,
                    availableSpot,
                    rawStatusCode,
                    rawAvailabilityValue,
                    sourceObservedAt,
                    systemFetchedAt,
                    qualityStatus,
                    qualityReason,
                    carEligibleStatus,
                    currentlyRecommendable);
        }
    }
}
