package co.wethinkcode.trafficflow.mq;

/**
 * Shared by every producer/consumer service that talks to the "congestion-topic"
 * ActiveMQ topic. Duplicated into each participating service's own source tree,
 * since these are independent Maven projects with no shared parent pom.
 */
public final class MqConfig {

    public static final String BROKER_URL = "tcp://localhost:61616";
    public static final String TOPIC = "congestion-topic";

    /**
     * Set to true on each congestion message, so the broker keeps the latest one as the topic's
     * current value. Congestion is one city-wide number, so the latest message is the whole state.
     */
    public static final String RETAIN_PROPERTY = "ActiveMQ.Retain";

    /**
     * Subscribe to {@code TOPIC + RETROACTIVE} to be handed that retained value as you subscribe,
     * so a subscriber that starts, or restarts, after a change still knows the current level.
     */
    public static final String RETROACTIVE = "?consumer.retroactive=true";

    private MqConfig() {
    }
}
