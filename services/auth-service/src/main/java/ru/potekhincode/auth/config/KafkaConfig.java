package ru.potekhincode.auth.config;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Value("${app.kafka.topic.replicas:3}")
    private int topicReplicas;

    @Bean
    public ProducerFactory<String, SpecificRecord> avroProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, SpecificRecord> avroKafkaTemplate() {
        return new KafkaTemplate<>(avroProducerFactory());
    }

    @Bean
    public NewTopic userCreatedTopic() {
        return TopicBuilder.name("user.created")
                .partitions(3)
                .replicas(topicReplicas)
                .build();
    }
}
