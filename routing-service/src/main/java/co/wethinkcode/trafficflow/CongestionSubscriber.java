package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionView.CongestionChanged;
import co.wethinkcode.trafficflow.mq.MqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A retroactive, non-durable subscription to {@code congestion-topic}: as it subscribes, the
 * broker hands over the retained latest level, then every change after it.
 *
 * <p>A durable subscription would replay every change missed while this service was down, when
 * only the latest matters, and would still know nothing on the very first start. The retained
 * value covers both.
 *
 * <p>Connects over {@code failover:} on a background thread, so the HTTP API is up even when the
 * broker isn't, and it reconnects on its own; the retained level is handed over again each time.
 */
final class CongestionSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CongestionSubscriber.class);
    // Tolerant of fields it doesn't use, but strict about the ones it does: a message without a
    // level, or with a null one, is unreadable, never silently level 0. (A null int is 0 to
    // Jackson unless told otherwise.)
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    private final String brokerUrl;
    private final Consumer<CongestionChanged> onChange;
    private final CountDownLatch subscribed = new CountDownLatch(1);
    private volatile Connection connection;

    CongestionSubscriber(String brokerUrl, Consumer<CongestionChanged> onChange) {
        this.brokerUrl = brokerUrl;
        this.onChange = onChange;
    }

    void start() {
        Thread thread = new Thread(this::subscribe, "congestion-subscriber");
        thread.setDaemon(true);
        thread.start();
    }

    boolean awaitSubscribed(Duration timeout) throws InterruptedException {
        return subscribed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void subscribe() {
        try {
            connection = new ActiveMQConnectionFactory("failover:(" + brokerUrl + ")?maxReconnectDelay=2000")
                    .createConnection();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createTopic(MqConfig.TOPIC + MqConfig.RETROACTIVE))
                    .setMessageListener(this::onMessage);
            connection.start();
            subscribed.countDown();
            log.info("Subscribed to {} at {}; the current level arrives first", MqConfig.TOPIC, brokerUrl);
        } catch (JMSException e) {
            log.error("Could not subscribe to {}; estimates will assume clear roads, and say so", MqConfig.TOPIC, e);
        }
    }

    /** Retrying won't make an unreadable message readable, so it is logged and dropped. */
    private void onMessage(Message message) {
        if (!(message instanceof TextMessage text)) {
            log.warn("Ignoring a non-text message on {}", MqConfig.TOPIC);
            return;
        }
        try {
            CongestionChanged change = JSON.readValue(text.getText(), CongestionChanged.class);
            onChange.accept(change);
            log.info("Congestion level {} (changed at {})", change.level(), change.changedAt());
        } catch (JMSException | JsonProcessingException e) {
            log.warn("Ignoring an unreadable message on {}: {}", MqConfig.TOPIC, e.getMessage());
        }
    }

    @Override
    public void close() {
        Connection current = connection;
        if (current != null) {
            try {
                current.close();
            } catch (JMSException e) {
                log.debug("Ignoring an error while closing the broker connection", e);
            }
        }
    }
}
