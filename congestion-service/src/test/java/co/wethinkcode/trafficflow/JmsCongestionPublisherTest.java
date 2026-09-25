package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionPublisher.PublishFailed;
import co.wethinkcode.trafficflow.mq.MqConfig;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.io.File;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Against a real ActiveMQ broker, run in-process on a free port. */
class JmsCongestionPublisherTest {

    private static final CongestionChanged TWO = new CongestionChanged(2, 0, "2026-09-24T08:00:00Z");
    private static final CongestionChanged FIVE = new CongestionChanged(5, 2, "2026-09-24T08:05:00Z");

    private BrokerService broker;
    private String brokerUrl;
    private Connection subscriberConnection;

    @BeforeEach
    void startBroker() throws Exception {
        broker = startBroker("tcp://localhost:0");
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();
    }

    @AfterEach
    void stopBroker() throws Exception {
        if (subscriberConnection != null) {
            subscriberConnection.close();
        }
        broker.stop();
        broker.waitUntilStopped();
    }

    /** A non-persistent broker, no JMX: shared with {@link RetainedLevelReaderTest}. */
    static BrokerService startBroker(String connector) throws Exception {
        BrokerService broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setDataDirectoryFile(new File("target/activemq-data"));
        broker.addConnector(connector);
        broker.start();
        broker.waitUntilStarted();
        return broker;
    }

    private MessageConsumer subscribe(String destination) throws JMSException {
        subscriberConnection = new ActiveMQConnectionFactory(brokerUrl).createConnection();
        subscriberConnection.start();
        Session session = subscriberConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        return session.createConsumer(session.createTopic(destination));
    }

    @Test
    void publishesEachChangeToTheTopicAsJson() throws Exception {
        MessageConsumer subscriber = subscribe(MqConfig.TOPIC);

        try (JmsCongestionPublisher publisher = new JmsCongestionPublisher(brokerUrl)) {
            publisher.publish(FIVE);
        }

        TextMessage message = (TextMessage) subscriber.receive(2_000);
        assertNotNull(message, "nothing was published");
        assertEquals("{\"level\":5,\"previousLevel\":2,\"changedAt\":\"2026-09-24T08:05:00Z\"}", message.getText());
    }

    @Test
    void aSubscriberThatJoinsLateIsHandedTheLatestLevelAndOnlyThat() throws Exception {
        try (JmsCongestionPublisher publisher = new JmsCongestionPublisher(brokerUrl)) {
            publisher.publish(TWO);
            publisher.publish(FIVE);
        }

        MessageConsumer late = subscribe(MqConfig.TOPIC + MqConfig.RETROACTIVE);

        TextMessage retained = (TextMessage) late.receive(2_000);
        assertNotNull(retained, "the retained level was not handed over");
        assertEquals("{\"level\":5,\"previousLevel\":2,\"changedAt\":\"2026-09-24T08:05:00Z\"}", retained.getText());
        assertNull(late.receive(300), "only the latest level is retained");
    }

    @Test
    void anUnreachableBrokerFailsFast() throws Exception {
        broker.stop();
        broker.waitUntilStopped();

        try (JmsCongestionPublisher publisher = new JmsCongestionPublisher(brokerUrl)) {
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> assertThrows(PublishFailed.class, () -> publisher.publish(TWO)));
        }
    }

    @Test
    void aFailedPublishReconnectsOnTheNextOne() throws Exception {
        try (JmsCongestionPublisher publisher = new JmsCongestionPublisher(brokerUrl)) {
            publisher.publish(TWO);
            broker.stop();
            broker.waitUntilStopped();
            assertThrows(PublishFailed.class, () -> publisher.publish(FIVE));

            URI address = URI.create(brokerUrl);
            broker = startBroker("tcp://" + address.getHost() + ":" + address.getPort());
            MessageConsumer subscriber = subscribe(MqConfig.TOPIC);
            publisher.publish(FIVE);

            assertNotNull(subscriber.receive(2_000), "the publisher did not reconnect");
        }
    }
}
