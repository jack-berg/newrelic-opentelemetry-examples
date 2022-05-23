package com.newrelic.app;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.micrometer1shim.OpenTelemetryMeterRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public OpenTelemetry openTelemetry() {
    return OpenTelemetrySdk.builder()
        .setMeterProvider(
            SdkMeterProvider.builder()
                .setResource(
                    Resource.getDefault().toBuilder()
                        .put("service.name", "my-micrometer-service-otel")
                        // Include instrumentation.provider=micrometer to enable micrometer metrics
                        // experience in New Relic
                        .put("instrumentation.provider", "micrometer")
                        .build())
                .registerMetricReader(
                    PeriodicMetricReader.builder(
                            OtlpGrpcMetricExporter.builder()
                                .setEndpoint("https://otlp.nr-data.net:4317")
                                .addHeader("api-key", System.getenv("NEW_RELIC_API_KEY"))
                                .setAggregationTemporalitySelector(
                                    AggregationTemporalitySelector.deltaPreferred())
                                .build())
                        .build())
                .build())
        .build();
  }

  @Bean
  public MeterRegistry meterRegistry(OpenTelemetry openTelemetry) {
    return OpenTelemetryMeterRegistry.builder(openTelemetry).build();
  }
}
