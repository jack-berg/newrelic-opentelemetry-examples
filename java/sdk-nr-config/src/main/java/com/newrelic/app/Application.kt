package com.newrelic.app

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.internal.retry.RetryPolicy
import io.opentelemetry.exporter.internal.retry.RetryUtil
import io.opentelemetry.exporter.logging.LoggingMetricExporter
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender
import io.opentelemetry.instrumentation.runtimemetrics.GarbageCollector
import io.opentelemetry.instrumentation.runtimemetrics.MemoryPools
import io.opentelemetry.instrumentation.spring.webmvc.SpringWebMvcTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.LogLimits
import io.opentelemetry.sdk.logs.SdkLogEmitterProvider
import io.opentelemetry.sdk.logs.export.BatchLogProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanLimits
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import java.lang.Boolean
import java.util.*
import java.util.function.Function
import javax.servlet.Filter
import kotlin.Array
import kotlin.String

@SpringBootApplication
open class Application {
    /** Add Spring WebMVC instrumentation by registering tracing filter.  */
    @Bean
    open fun webMvcTracingFilter(): Filter {
        return SpringWebMvcTelemetry.create(GlobalOpenTelemetry.get()).newServletFilter()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // Configure OpenTelemetry as early as possible
            val openTelemetrySdk = openTelemetrySdk()

            // Initialize Log4j2 appender
            OpenTelemetryAppender.setSdkLogEmitterProvider(openTelemetrySdk.sdkLogEmitterProvider)

            // Register runtime metrics instrumentation
            MemoryPools.registerObservers(openTelemetrySdk)
            GarbageCollector.registerObservers(openTelemetrySdk)
            SpringApplication.run(Application::class.java, *args)
        }

        private fun openTelemetrySdk(): OpenTelemetrySdk {
            val logExporterEnabled = getEnvOrDefault("LOG_EXPORTER_ENABLED", { s: String? -> Boolean.valueOf(s) }, false)
            val newrelicApiOrLicenseKey = getEnvOrDefault(
                    "NEW_RELIC_API_KEY",
                    Function.identity(),
                    getEnvOrDefault("NEW_RELIC_LICENSE_KEY", Function.identity(), ""))
            val newrelicOtlpEndpoint = getEnvOrDefault("OTLP_HOST", Function.identity(), "https://otlp.nr-data.net:4317")

            // Configure resource
            val resource = Resource.getDefault().toBuilder()
                    .put(ResourceAttributes.SERVICE_NAME, "sdk-nr-config")
                    .put(ResourceAttributes.SERVICE_INSTANCE_ID, UUID.randomUUID().toString())
                    .build()

            // Configure tracer provider
            val sdkTracerProviderBuilder = SdkTracerProvider.builder()
                    .setResource(resource) // New Relic's max attribute length is 4095 characters
                    .setSpanLimits(
                            SpanLimits.getDefault().toBuilder().setMaxAttributeValueLength(4095).build())
            // Add otlp span exporter
            val spanExporterBuilder = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(newrelicOtlpEndpoint)
                    .setCompression("gzip")
                    .addHeader("api-key", newrelicApiOrLicenseKey)
            // Enable retry policy via unstable API
            RetryUtil.setRetryPolicyOnDelegate(spanExporterBuilder, RetryPolicy.getDefault())
            sdkTracerProviderBuilder.addSpanProcessor(
                    BatchSpanProcessor.builder(spanExporterBuilder.build()).build())
            // Maybe add log exporter
            if (logExporterEnabled) {
                sdkTracerProviderBuilder.addSpanProcessor(
                        SimpleSpanProcessor.create(LoggingSpanExporter.create()))
            }

            // Configure meter provider
            val sdkMeterProviderBuilder = SdkMeterProvider.builder().setResource(resource)
            // Add otlp metric exporter
            val metricExporterBuilder = OtlpGrpcMetricExporter.builder()
                    .setEndpoint(newrelicOtlpEndpoint)
                    .setCompression("gzip")
                    .addHeader("api-key", newrelicApiOrLicenseKey) // IMPORTANT: New Relic requires metrics to be delta temporality
                    .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            // Enable retry policy via unstable API
            RetryUtil.setRetryPolicyOnDelegate(metricExporterBuilder, RetryPolicy.getDefault())
            sdkMeterProviderBuilder.registerMetricReader(
                    PeriodicMetricReader.builder(metricExporterBuilder.build()).build())
            // Maybe add log exporter
            if (logExporterEnabled) {
                sdkMeterProviderBuilder.registerMetricReader(
                        PeriodicMetricReader.builder(LoggingMetricExporter.create(AggregationTemporality.DELTA))
                                .build())
            }

            // Configure log emitter provider
            val sdkLogEmitterProvider = SdkLogEmitterProvider.builder() // New Relic's max attribute length is 4095 characters
                    .setLogLimits { LogLimits.getDefault().toBuilder().setMaxAttributeValueLength(4095).build() }
                    .setResource(resource)
            // Add otlp log exporter
            val logExporterBuilder = OtlpGrpcLogExporter.builder()
                    .setEndpoint(newrelicOtlpEndpoint)
                    .setCompression("gzip")
                    .addHeader("api-key", newrelicApiOrLicenseKey)
            // Enable retry policy via unstable API
            RetryUtil.setRetryPolicyOnDelegate(logExporterBuilder, RetryPolicy.getDefault())
            sdkLogEmitterProvider.addLogProcessor(
                    BatchLogProcessor.builder(logExporterBuilder.build()).build())

            // Bring it all together
            return OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(sdkTracerProviderBuilder.build())
                    .setMeterProvider(sdkMeterProviderBuilder.build())
                    .setLogEmitterProvider(sdkLogEmitterProvider.build())
                    .buildAndRegisterGlobal()
        }

        private fun <T> getEnvOrDefault(
                key: String, transformer: Function<String, T>, defaultValue: T): T {
            return Optional.ofNullable(System.getenv(key))
                    .filter { s: String -> !s.isBlank() }
                    .or { Optional.ofNullable(System.getProperty(key)) }
                    .filter { s: String -> !s.isBlank() }
                    .map(transformer)
                    .orElse(defaultValue)
        }
    }
}