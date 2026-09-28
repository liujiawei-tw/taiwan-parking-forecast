package parking.forecast.adapter.ntpc;

public class NtpcOpenDataClientException extends RuntimeException {

    public NtpcOpenDataClientException(String message) {
        super(message);
    }

    public NtpcOpenDataClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
