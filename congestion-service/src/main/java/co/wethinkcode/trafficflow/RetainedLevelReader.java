package co.wethinkcode.trafficflow;

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
import java.util.Optional;

/**
 * Reads back the change retained on {@code congestion-topic}: the broker keeps the latest one, so
 * it is the level this service last published, and it survives a restart of this service.
 *
 * <p>A short-lived retroactive subscription: connect, take what the broker hands over within
 * {@code wait}, close. Nothing within {@code wait} means nothing was ever published. Over plain
 * {@code tcp://}, like the publisher, so an unreachable broker fails promptly.
 */
final class RetainedLevelReader implements LastPublished {

    private static final Logger log = LoggerFactory.getLogger(RetainedLevelReader.class);
    // Strict: a retained message without a level, a null one, or one outside 0 to 8 is
    // unreadable, never level 0. (A null int is 0 to Jackson unless told otherwise.)
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    private final ActiveMQConnectionFactory factory;
    private final Duration wait;

    RetainedLevelReader(String brokerUrl, Duration wait) {
        factory = new ActiveMQConnectionFactory(brokerUrl);
        factory.setConnectResponseTimeout(3_000);
        this.wait = wait;
    }

    @Override
    public Optional<CongestionChanged> read() {
        Connection connection = null;
        try {
            connection = factory.createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Message retained = session.createConsumer(session.createTopic(MqConfig.TOPIC + MqConfig.RETROACTIVE))
                    .receive(wait.toMillis());
            return retained == null ? Optional.empty() : Optional.of(parse(retained));
        } catch (JMSException e) {
            throw new ReadFailed("could not read " + MqConfig.TOPIC + ": " + e.getMessage(), e);
        } finally {
            close(connection);
        }
    }

    private static CongestionChanged parse(Message message) throws JMSException {
        if (!(message instanceof TextMessage text)) {
            throw new ReadFailed("the message retained on " + MqConfig.TOPIC + " is not text", null);
        }
        CongestionChanged change;
        try {
            change = JSON.readValue(text.getText(), CongestionChanged.class);
        } catch (JsonProcessingException e) {
            throw new ReadFailed("the message retained on " + MqConfig.TOPIC + " is unreadable: "
                    + e.getOriginalMessage(), e);
        }
        if (change.level() < CongestionLevel.MIN_LEVEL || change.level() > CongestionLevel.MAX_LEVEL) {
            throw new ReadFailed("the message retained on " + MqConfig.TOPIC + " has level " + change.level()
                    + ", outside " + CongestionLevel.MIN_LEVEL + " to " + CongestionLevel.MAX_LEVEL, null);
        }
        return change;
    }

    private static void close(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException e) {
                log.debug("Ignoring an error while closing the broker connection", e);
            }
        }
    }
}
