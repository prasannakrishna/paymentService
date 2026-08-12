package com.bhagwat.scm.paymentService.streaming.config;

import com.bhagwat.scm.paymentService.streaming.event.PaymentSuccessEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JsonSerde;

/**
 * Kafka Streams topology for routing payment success events to the correct
 * sink topic based on the payer type.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                                                                         │
 * │  payment.success.events (source topic)                                  │
 * │           │                                                             │
 * │           ▼  KStream<String, PaymentSuccessEvent>                       │
 * │           │                                                             │
 * │      split()                                                            │
 * │       ├── sourceType == "INDIVIDUAL"                                    │
 * │       │   or "STANDALONE_PG"     ──► payment.customer.transactions      │
 * │       │                                                                 │
 * │       └── sourceType == "ENTERPRISE" ──► payment.org.transactions       │
 * │                                                                         │
 * │  Unknown sourceType → payment.dead.letter (manual review queue)         │
 * │                                                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Kafka Streams guarantees:
 *   - At-least-once delivery (default); configure exactly-once via
 *     spring.kafka.streams.properties.processing.guarantee=exactly_once_v2
 *   - Fault tolerance: replays from committed Kafka offset on restart
 *   - Horizontal scaling: add instances — Kafka distributes partitions
 */
@Slf4j
@Configuration
@EnableKafkaStreams
public class KafkaStreamsTopology {

    @Value("${payment.topics.success:payment.success.events}")
    private String sourceTopic;

    @Value("${payment.topics.customer-tx:payment.customer.transactions}")
    private String customerTxTopic;

    @Value("${payment.topics.org-tx:payment.org.transactions}")
    private String orgTxTopic;

    @Value("${payment.topics.dead-letter:payment.dead.letter}")
    private String deadLetterTopic;

    @Bean
    public KStream<String, PaymentSuccessEvent> paymentSuccessRouter(StreamsBuilder builder) {

        Serde<String>              keySerde   = Serdes.String();
        JsonSerde<PaymentSuccessEvent> valueSerde = new JsonSerde<>(PaymentSuccessEvent.class);
        valueSerde.deserializer().setUseTypeHeaders(false);

        KStream<String, PaymentSuccessEvent> source = builder.stream(
                sourceTopic,
                Consumed.with(keySerde, valueSerde));

        // Tap for logging — does not alter the stream
        source.peek((key, event) ->
                log.debug("Routing payment event: key={} sourceType={} transferId={}",
                        key, event.getSourceType(), event.getTransferId()));

        /*
         * KStream.split() is the Kafka Streams native branching primitive.
         * Events that match no predicate fall through to the default branch (dead-letter).
         */
        source.split(Named.as("payment-router-"))
            .branch(
                (key, event) -> "INDIVIDUAL".equals(event.getSourceType())
                             || "STANDALONE_PG".equals(event.getSourceType()),
                Branched.<String, PaymentSuccessEvent>withConsumer(
                    customerStream -> {
                        log.debug("Routing to customer-tx topic");
                        customerStream.to(customerTxTopic,
                                Produced.with(Serdes.String(), new JsonSerde<>(PaymentSuccessEvent.class)));
                    }).withName("customer")
            )
            .branch(
                (key, event) -> "ENTERPRISE".equals(event.getSourceType()),
                Branched.<String, PaymentSuccessEvent>withConsumer(
                    orgStream -> {
                        log.debug("Routing to org-tx topic");
                        orgStream.to(orgTxTopic,
                                Produced.with(Serdes.String(), new JsonSerde<>(PaymentSuccessEvent.class)));
                    }).withName("org")
            )
            .defaultBranch(
                Branched.<String, PaymentSuccessEvent>withConsumer(
                    unknownStream -> {
                        unknownStream
                            .peek((key, event) ->
                                log.error("Unknown sourceType '{}' for transferId={} — routing to dead-letter",
                                        event.getSourceType(), event.getTransferId()))
                            .to(deadLetterTopic,
                                    Produced.with(Serdes.String(), new JsonSerde<>(PaymentSuccessEvent.class)));
                    }).withName("dead-letter")
            );

        return source;
    }
}
