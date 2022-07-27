package com.newrelic.app;

import com.github.javafaker.Faker;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.internal.retry.RetryPolicy;
import io.opentelemetry.exporter.internal.retry.RetryUtil;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.instrumentation.kafkaclients.KafkaTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.internal.DaemonThreadFactory;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

public class Application {

  private static final List<String> TOPICS = List.of("red", "blue", "green");
  private static final Random RANDOM = new Random();
  private static final Faker FAKER = new Faker();

  public static void main(String[] args) throws InterruptedException {
    // Start kafka
    KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
            .withLogConsumer(outputFrame -> System.out.print(outputFrame.getUtf8String()))
            .waitingFor(Wait.forLogMessage(".*started \\(kafka.server.KafkaServer\\).*", 1))
            .withStartupTimeout(Duration.ofMinutes(1));
    kafka.start();
    Runtime.getRuntime().addShutdownHook(new Thread(kafka::stop));

    // Configure OpenTelemetry
    var openTelemetrySdk = openTelemetrySdk();

    // Start producer daemon thread
    System.out.println("Starting kafka producer..");
    new DaemonThreadFactory("producer")
        .newThread(producer(kafka.getBootstrapServers(), openTelemetrySdk))
        .start();

    // Start consumer daemon thread
    System.out.println("Starting kafka consumer..");
    new DaemonThreadFactory("consumer")
        .newThread(consumer(kafka.getBootstrapServers(), openTelemetrySdk))
        .start();

    // Sleep to run program until interrupted
    Thread.sleep(Long.MAX_VALUE);
  }

  private static Runnable producer(String bootStrapServers, OpenTelemetry openTelemetry) {
    return () -> {
      // Configure producer
      Map<String, Object> producerConfig = new HashMap<>();
      producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootStrapServers);
      producerConfig.put(ProducerConfig.CLIENT_ID_CONFIG, "sample-client-id");
      producerConfig.put(
          ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
      producerConfig.put(
          ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
      producerConfig.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
      producerConfig.putAll(
          KafkaTelemetry.create(openTelemetry)
              .metricConfigProperties()); // Inject opentelemetry config into config properties

      try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerConfig)) {
        long count = 0;
        while (true) {
          String topic = TOPICS.get(RANDOM.nextInt(TOPICS.size()));
          String key = "key";
          String value = FAKER.lorem().paragraph();
          producer.send(
              new ProducerRecord<>(
                  topic,
                  0,
                  System.currentTimeMillis(),
                  key.getBytes(StandardCharsets.UTF_8),
                  value.getBytes(StandardCharsets.UTF_8)));
          if (count % 10 == 0) {
            System.out.format("Produced message %s to topic %s: %s%n", count, topic, value);
          }
          try {
            Thread.sleep(RANDOM.nextInt(100));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          count++;
        }
      }
    };
  }

  private static Runnable consumer(String bootStrapServers, OpenTelemetry openTelemetry) {
    return () -> {
      // Configure consumer
      Map<String, Object> consumerConfig = new HashMap<>();
      consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootStrapServers);
      consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "sample-group");
      consumerConfig.put(
          ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
      consumerConfig.put(
          ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
      consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2);
      consumerConfig.putAll(
          KafkaTelemetry.create(openTelemetry)
              .metricConfigProperties()); // Inject opentelemetry config into config properties

      try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerConfig)) {
        consumer.subscribe(TOPICS);
        AtomicLong count = new AtomicLong();
        while (true) {
          var consumerRecords = consumer.poll(Duration.ofSeconds(1));
          consumerRecords.forEach(
              record -> {
                long currentCount = count.get();
                if (currentCount % 10 == 0) {
                  System.out.format(
                      "Consumer message %s from topic %s: %s%n",
                      currentCount, record.topic(), new String(record.value()));
                }
                count.incrementAndGet();
              });
          if (consumerRecords.isEmpty()) {
            try {
              Thread.sleep(RANDOM.nextInt(100));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }
      }
    };
  }

  private static OpenTelemetrySdk openTelemetrySdk() {
    var newrelicApiOrLicenseKey =
        getEnvOrDefault(
            "NEW_RELIC_API_KEY",
            Function.identity(),
            getEnvOrDefault("NEW_RELIC_LICENSE_KEY", Function.identity(), ""));
    var newrelicOtlpEndpoint =
        getEnvOrDefault("OTLP_HOST", Function.identity(), "https://otlp.nr-data.net:4317");

    // Configure resource
    var resource =
        Resource.getDefault().toBuilder()
            .put(ResourceAttributes.SERVICE_NAME, "kafka-client-metrics")
            .put(ResourceAttributes.SERVICE_INSTANCE_ID, UUID.randomUUID().toString())
            .build();

    // Configure meter provider
    var sdkMeterProviderBuilder = SdkMeterProvider.builder().setResource(resource);
    // Add otlp metric exporter
    var metricExporterBuilder =
        OtlpGrpcMetricExporter.builder()
            .setEndpoint(newrelicOtlpEndpoint)
            .setCompression("gzip")
            .addHeader("api-key", newrelicApiOrLicenseKey)
            // IMPORTANT: New Relic requires metrics to be delta temporality
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred());
    // Enable retry policy via unstable API
    RetryUtil.setRetryPolicyOnDelegate(metricExporterBuilder, RetryPolicy.getDefault());
    sdkMeterProviderBuilder.registerMetricReader(
        PeriodicMetricReader.builder(metricExporterBuilder.build()).build());

    // Bring it all together
    return OpenTelemetrySdk.builder()
        .setMeterProvider(sdkMeterProviderBuilder.build())
        .buildAndRegisterGlobal();
  }

  private static <T> T getEnvOrDefault(
      String key, Function<String, T> transformer, T defaultValue) {
    return Optional.ofNullable(System.getenv(key))
        .filter(s -> !s.isBlank())
        .or(() -> Optional.ofNullable(System.getProperty(key)))
        .filter(s -> !s.isBlank())
        .map(transformer)
        .orElse(defaultValue);
  }
}
