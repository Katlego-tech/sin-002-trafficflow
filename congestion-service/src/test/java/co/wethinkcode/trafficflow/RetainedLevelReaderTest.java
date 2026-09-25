package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.LastPublished.ReadFailed;
import co.wethinkcode.trafficflow.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.jms.Connection;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Against a real ActiveMQ broker, run in-process on a free port. */
class RetainedLevelReaderTest {

    private static final Duration WAIT = Duration.ofMillis(500);
    private static final CongestionChanged TWO = new CongestionChanged(2, 0, "2026-09-24T08:00:00Z");
    private static final CongestionChanged FIVE = new CongestionChanged(5, 2, "2026-09-24T08:05:00Z");

    private BrokerService broker;
    private String brokerUrl;

    @BeforeEach
    void startBroker() throws Exception {
        broker = JmsCongestionPublisherTest.startBroker("tcp://localhost:0");
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();
    }

    @AfterEach
    void stopBroker() throws Exception {
        broker.stop();
        broker.waitUntilStopped();
    }

    private void publish(CongestionChanged... changes) {
        try (JmsCongestionPublisher publisher = new JmsCongestionPublisher(brokerUrl)) {
            for (CongestionChanged change : changes) {
                publisher.publish(change);
            }
        }
    }

    /** A retained message that congestion-service didn't write. */
    private void publishRetained(String body) throws Exception {
        Connection connection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            TextMessage message = session.createTextMessage(body);
            message.setBooleanProperty(MqConfig.RETAIN_PROPERTY, true);
            session.createProducer(session.createTopic(MqConfig.TOPIC)).send(message);
        } finally {
            connection.close();
        }
    }

    @Test
    void readsBackTheLatestChangePublished() {
        publish(TWO, FIVE);

        assertEquals(Optional.of(FIVE), new RetainedLevelReader(brokerUrl, WAIT).read());
    }

    @Test
    void readingLeavesTheLevelRetainedForTheNextReader() {
        publish(FIVE);
        RetainedLevelReader reader = new RetainedLevelReader(brokerUrl, WAIT);

        reader.read();

        assertEquals(Optional.of(FIVE), reader.read());
    }

    @Test
    void nothingEverPublishedIsEmpty() {
        assertEquals(Optional.empty(), new RetainedLevelReader(brokerUrl, WAIT).read());
    }

    @Test
    void anUnreachableBrokerFailsFast() throws Exception {
        broker.stop();
        broker.waitUntilStopped();

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(ReadFailed.class, () -> new RetainedLevelReader(brokerUrl, WAIT).read()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"previousLevel\": 2, \"changedAt\": \"2026-09-24T08:05:00Z\"}",
            "{\"level\": null, \"previousLevel\": 2, \"changedAt\": \"2026-09-24T08:05:00Z\"}",
            "{\"level\": 5, \"previousLevel\": 2}",
            "{\"level\": 9, \"previousLevel\": 2, \"changedAt\": \"2026-09-24T08:05:00Z\"}",
            "{\"level\": -1, \"previousLevel\": 2, \"changedAt\": \"2026-09-24T08:05:00Z\"}"})
    void aRetainedMessageItCannotReadFailsNeverReadsAsZero(String body) throws Exception {
        publishRetained(body);

        assertThrows(ReadFailed.class, () -> new RetainedLevelReader(brokerUrl, WAIT).read());
    }
}
