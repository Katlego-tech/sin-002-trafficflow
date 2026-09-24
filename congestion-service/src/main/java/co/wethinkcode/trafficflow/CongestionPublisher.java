package co.wethinkcode.trafficflow;

/** Tells the rest of the city that the level changed. */
@FunctionalInterface
interface CongestionPublisher {

    /** @throws PublishFailed if the event did not reach the broker */
    void publish(CongestionChanged event);

    class PublishFailed extends RuntimeException {
        PublishFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
