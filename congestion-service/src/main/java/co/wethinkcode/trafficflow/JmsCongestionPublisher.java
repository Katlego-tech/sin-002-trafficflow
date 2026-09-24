package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.mq.MqConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

/**
 * Publishes {@link CongestionChanged} events to {@code congestion-topic} as JSON text messages,
 * each marked to be retained as the topic's current value ({@link MqConfig#RETAIN_PROPERTY}).
 *
 * <p>Connects on first use over plain {@code tcp://}, not {@code failover:}: a failover connection
 * would hold the HTTP request until the broker came back, where the caller should get a prompt
 * 503. Messages go persistent, which ActiveMQ sends synchronously, so the broker's acknowledgement
 * is what tells us the change arrived. After a failure the connection is dropped and rebuilt on the
 * next publish.
 */
final class JmsCongestionPublisher implements CongestionPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JmsCongestionPublisher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ActiveMQConnectionFactory factory;
    private Connection connection;
    private Session session;
    private MessageProducer producer;

    JmsCongestionPublisher(String brokerUrl) {
        factory = new ActiveMQConnectionFactory(brokerUrl);
        // A broker that hangs fails the request rather than hanging it.
        factory.setConnectResponseTimeout(3_000);
        factory.setSendTimeout(3_000);
    }

    @Override
    public synchronized void publish(CongestionChanged event) {
        try {
            if (producer == null) {
                connect();
            }
            TextMessage message = session.createTextMessage(JSON.writeValueAsString(event));
            message.setBooleanProperty(MqConfig.RETAIN_PROPERTY, true);
            producer.send(message);
            log.info("Published to {}: {}", MqConfig.TOPIC, message.getText());
        } catch (JMSException | JsonProcessingException e) {
            close();
            throw new PublishFailed("could not publish to " + MqConfig.TOPIC + ": " + e.getMessage(), e);
        }
    }

    private void connect() throws JMSException {
        connection = factory.createConnection();
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        producer = session.createProducer(session.createTopic(MqConfig.TOPIC));
        log.info("Connected to {}", factory.getBrokerURL());
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException e) {
                log.debug("Ignoring an error while closing the broker connection", e);
            }
        }
        connection = null;
        session = null;
        producer = null;
    }
}
