package com.newrelic.app;

import static java.util.stream.Collectors.toList;

import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.metrics.internal.view.ExponentialHistogramAggregation;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;

public class Application {

  public static void main(String[] args) {
    var id = UUID.randomUUID().toString();

    var otlpMetricExporter =
        OtlpGrpcMetricExporter.builder()
            .setEndpoint("https://otlp.nr-data.net:4317")
            .setCompression("gzip")
            .addHeader("api-key", System.getenv("NEW_RELIC_API_KEY"))
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .build();
    var exporter =
        new MetricExporter() {
          @Override
          public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return otlpMetricExporter.getAggregationTemporality(instrumentType);
          }

          @Override
          public CompletableResultCode export(Collection<MetricData> metrics) {
            var exponentialMarshaler =
                MetricsRequestMarshaler.create(
                    metrics.stream()
                        .filter(metric -> metric.getName().contains("exponential-ms"))
                        .collect(toList()));
            var explicitMarshaler =
                MetricsRequestMarshaler.create(
                    metrics.stream()
                        .filter(metric -> metric.getName().contains("explicit-ms"))
                        .collect(toList()));
            System.out.format(
                "Exponential raw: %s, compressed: %s%n",
                exponentialMarshaler.getBinarySerializedSize(), gzipSize(exponentialMarshaler));
            System.out.format(
                "Explicit raw: %s, compressed: %s%n",
                explicitMarshaler.getBinarySerializedSize(), gzipSize(explicitMarshaler));
            return otlpMetricExporter.export(metrics);
          }

          private int gzipSize(MetricsRequestMarshaler marshaler) {
            var baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
              marshaler.writeBinaryTo(gzip);
              gzip.finish();
              return baos.size();
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }

          @Override
          public CompletableResultCode flush() {
            return otlpMetricExporter.flush();
          }

          @Override
          public CompletableResultCode shutdown() {
            return otlpMetricExporter.shutdown();
          }
        };

    System.out.println("service id: " + id);
    var meterProvider =
        SdkMeterProvider.builder()
            .setResource(
                Resource.getDefault().toBuilder()
                    .put(ResourceAttributes.SERVICE_NAME, "exp-histogram-test")
                    .put(ResourceAttributes.SERVICE_INSTANCE_ID, id)
                    .build())
            .registerMetricReader(
                PeriodicMetricReader.builder(exporter)
                    .setInterval(Duration.ofSeconds(10000))
                    .build())
            .registerView(
                InstrumentSelector.builder().setName("*exponential*").build(),
                View.builder().setAggregation(ExponentialHistogramAggregation.create(40)).build())
            .registerView(
                InstrumentSelector.builder().setName("*explicit*").build(),
                View.builder()
                    .setAggregation(
                        Aggregation.explicitBucketHistogram(
                            List.of(0d, 5d, 10d, 25d, 50d, 75d, 100d, 250d, 500d, 1000d)))
                    .build())
            .build();
    var exponentialMs = meterProvider.get("meter").histogramBuilder("exponential-ms").build();
    var explicitMs = meterProvider.get("meter").histogramBuilder("explicit-ms").build();
    var exponentialNs = meterProvider.get("meter").histogramBuilder("exponential-ns").build();
    var explicitNs = meterProvider.get("meter").histogramBuilder("explicit-ns").build();

    // Low latency
    var gammaDistribution = new GammaDistribution(1.0, 2.0);
    Supplier<Long> lowLatencyGenerator = () -> (long) Math.floor(gammaDistribution.sample() * 10.0);

    // Middle latency
    var middleNormalDistribution = new NormalDistribution(250, 75);
    Supplier<Long> middleLatencyGenerator =
        () -> (long) Math.floor(middleNormalDistribution.sample());

    // High latency
    var highNormalDistribution = new NormalDistribution(800, 30);
    Supplier<Long> highLatencyGenerator = () -> (long) Math.floor(highNormalDistribution.sample());

    // 20% low latency, 60% middle latency, 20% high latency
    List<Supplier<Long>> generators =
        List.of(
            lowLatencyGenerator,
            lowLatencyGenerator,
            middleLatencyGenerator,
            middleLatencyGenerator,
            middleLatencyGenerator,
            middleLatencyGenerator,
            middleLatencyGenerator,
            middleLatencyGenerator,
            highLatencyGenerator,
            highLatencyGenerator);
    var random = new Random();

    DoubleSummaryStatistics summary = new DoubleSummaryStatistics();
    for (int i = 0; i < 1000000; i++) {
      long sampleMs = Math.max(generators.get(random.nextInt(generators.size())).get(), 1);
      summary.accept(sampleMs);
      exponentialMs.record(sampleMs);
      explicitMs.record(sampleMs);
      exponentialNs.record(TimeUnit.MILLISECONDS.toNanos(sampleMs));
      explicitNs.record(TimeUnit.MILLISECONDS.toNanos(sampleMs));
    }
    System.out.format(
        "Min: %s, max: %s, average: %s%n",
        summary.getMin(), summary.getMax(), summary.getAverage());
    meterProvider.forceFlush().join(100, TimeUnit.SECONDS);
  }
}
