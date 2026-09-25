package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionSource.Reading;
import co.wethinkcode.trafficflow.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against a real ActiveMQ broker, run in-process on a free port. */
class CongestionSubscriberTest {

    private final CongestionView view = new CongestionView();
    private BrokerService broker;
    private String brokerUrl;
    private CongestionSubscriber subscriber;

    @BeforeEach
    void startBroker() throws Exception {
        broker = startBroker("tcp://localhost:0");
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();
    }

    @AfterEach
    void stop() throws Exception {
        if (subscriber != null) {
            subscriber.close();
        }
        broker.stop();
        broker.waitUntilStopped();
    }

    private static BrokerService startBroker(String connector) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setDataDirectoryFile(new File("target/activemq-data"));
        broker.addConnector(connector);
        broker.start();
        broker.waitUntilStarted();
        return broker;
    }

    /** Publishes the way congestion-service does: JSON, retained as the topic's current value. */
    private void publish(String json) throws JMSException {
        Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            TextMessage message = session.createTextMessage(json);
            message.setBooleanProperty(MqConfig.RETAIN_PROPERTY, true);
            session.createProducer(session.createTopic(MqConfig.TOPIC)).send(message);
        } finally {
            connection.close();
        }
    }

    private static String change(int level, int previous, String at) {
        return "{\"level\":" + level + ",\"previousLevel\":" + previous + ",\"changedAt\":\"" + at + "\"}";
    }

    private void subscribe() throws InterruptedException {
        subscriber = new CongestionSubscriber(brokerUrl, view::apply);
        subscriber.start();
        assertTrue(subscriber.awaitSubscribed(Duration.ofSeconds(5)), "never subscribed");
    }

    private static void eventually(Supplier<Boolean> condition, String what) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("within 10s: " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void startingAfterTheLevelWasSetStillLearnsItAtOnce() throws Exception {
        publish(change(3, 0, "2026-09-24T08:00:00Z"));
        publish(change(6, 3, "2026-09-24T09:00:00Z"));

        subscribe();

        eventually(() -> view.current().equals(new Reading(6, "2026-09-24T09:00:00Z")), "the retained level 6");
    }

    @Test
    void liveChangesKeepArriving() throws Exception {
        subscribe();

        publish(change(2, 0, "2026-09-24T08:00:00Z"));
        eventually(() -> Integer.valueOf(2).equals(view.current().level()), "level 2");
        publish(change(7, 2, "2026-09-24T08:30:00Z"));
        eventually(() -> Integer.valueOf(7).equals(view.current().level()), "level 7");
    }

    @Test
    void anUnreadableMessageIsSkippedNeverTakenAsClearRoads() throws Exception {
        subscribe();
        publish(change(5, 0, "2026-09-24T08:00:00Z"));
        eventually(() -> view.current().known(), "level 5");

        publish("not json");
        publish("{\"changedAt\":\"2026-09-24T08:10:00Z\"}");
        publish(change(9, 5, "2026-09-24T08:20:00Z"));
        publish("{\"level\":1}");
        publish(change(4, 5, "2026-09-24T08:30:00Z"));

        eventually(() -> Integer.valueOf(4).equals(view.current().level()), "level 4, after the bad ones");
        assertEquals(new Reading(4, "2026-09-24T08:30:00Z"), view.current());
    }

    @Test
    void aNullLevelIsSkippedNeverTakenAsClearRoads() throws Exception {
        subscribe();
        publish(change(5, 0, "2026-09-24T08:00:00Z"));
        eventually(() -> view.current().known(), "level 5");

        publish("{\"level\":null,\"changedAt\":\"2026-09-24T08:20:00Z\"}");
        // Older than the null one, so it applies only if the null one was skipped.
        publish(change(6, 5, "2026-09-24T08:10:00Z"));

        eventually(() -> Integer.valueOf(6).equals(view.current().level()), "level 6, the null level skipped");
    }

    @Test
    void itReconnectsOnItsOwnAfterTheBrokerComesBack() throws Exception {
        subscribe();
        broker.stop();
        broker.waitUntilStopped();

        URI address = URI.create(brokerUrl);
        broker = startBroker("tcp://" + address.getHost() + ":" + address.getPort());
        eventually(() -> {
            try {
                publish(change(8, 0, "2026-09-24T10:00:00Z"));
                Thread.sleep(200);
            } catch (JMSException | InterruptedException e) {
                return false;
            }
            return Integer.valueOf(8).equals(view.current().level());
        }, "level 8 after the broker restarted");
    }
}
