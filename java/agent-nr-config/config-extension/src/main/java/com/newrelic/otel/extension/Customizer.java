package com.newrelic.otel.extension;

import static io.opentelemetry.semconv.resource.attributes.ResourceAttributes.SERVICE_INSTANCE_ID;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.contrib.sampler.RuleBasedRoutingSampler;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import java.util.Collection;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Note this class is wired into SPI via {@code
 * resources/META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider}
 */
public class Customizer implements AutoConfigurationCustomizerProvider {

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    // Add additional resource attributes programmatically
    autoConfiguration.addResourceCustomizer(
        (resource, configProperties) ->
            resource.merge(
                Resource.builder().put(SERVICE_INSTANCE_ID, UUID.randomUUID().toString()).build()));

    // Set the sampler to be the default parentbased_always_on, but drop calls to spring
    // boot actuator endpoints
    autoConfiguration.addTracerProviderCustomizer(
        (sdkTracerProviderBuilder, configProperties) ->
            sdkTracerProviderBuilder.setSampler(
                Sampler.parentBased(
                    RuleBasedRoutingSampler.builder(SpanKind.SERVER, Sampler.alwaysOn())
                        .drop(SemanticAttributes.HTTP_TARGET, "/actuator.*")
                        .build())));

    autoConfiguration.addLogRecordExporterCustomizer(new BiFunction<LogRecordExporter, ConfigProperties, LogRecordExporter>() {
      @Override
      public LogRecordExporter apply(LogRecordExporter logRecordExporter, ConfigProperties configProperties) {
        return new LogRecordExporter() {
          @Override
          public CompletableResultCode export(Collection<LogRecordData> logs) {
            return logRecordExporter.export(logs.stream().filter(new Predicate<LogRecordData>() {
              @Override
              public boolean test(LogRecordData logRecordData) {
                return logRecordData.getAttributes().get(AttributeKey.stringKey("event.name")) != null;
              }
            }).collect(Collectors.toList()));
          }

          @Override
          public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
          }

          @Override
          public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
          }
        };
      }
    });
  }
}
