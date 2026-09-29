package parking.forecast.adapter.google;

public class GoogleRoutesClientException extends RuntimeException {

    public GoogleRoutesClientException(String message) {
        super(message);
    }

    public GoogleRoutesClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
