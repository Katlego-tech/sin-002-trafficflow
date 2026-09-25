package co.wethinkcode.trafficflow;

import java.util.Optional;

/** The last change this service published: where its level comes from after a restart. */
@FunctionalInterface
interface LastPublished {

    /**
     * @return the last change published, or empty if none ever was
     * @throws ReadFailed if it could not be read, so the level is unknown
     */
    Optional<CongestionChanged> read();

    class ReadFailed extends RuntimeException {
        ReadFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
