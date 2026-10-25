package parking.forecast.adapter.google;

public class GooglePlacesClientException extends RuntimeException {

    private final String resultStatus;

    public GooglePlacesClientException(String message, String resultStatus) {
        super(message);
        this.resultStatus = resultStatus;
    }

    public GooglePlacesClientException(String message, Throwable cause) {
        super(message, cause);
        this.resultStatus = "FAILED";
    }

    public String resultStatus() { return resultStatus; }
}
