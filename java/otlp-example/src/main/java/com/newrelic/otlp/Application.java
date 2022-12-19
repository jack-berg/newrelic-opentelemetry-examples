package com.newrelic.otlp;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.api.internal.OtelEncodingUtils;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import io.opentelemetry.sdk.trace.IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Application {

  public static void main(String[] args) {
    var metadata = new Metadata();
    metadata.put(
        Metadata.Key.of("api-key", Metadata.ASCII_STRING_MARSHALLER),
        System.getenv("NEW_RELIC_API_KEY"));

    var managedChannel =
        ManagedChannelBuilder.forAddress("otlp.nr-data.net", 4317)
            .useTransportSecurity()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build();

    var traceServiceStub = TraceServiceGrpc.newBlockingStub(managedChannel);

    var request =
        ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(
                ResourceSpans.newBuilder()
                    .setResource(
                        Resource.newBuilder()
                            .addAttributes(
                                KeyValue.newBuilder()
                                    .setKey("service.name")
                                    .setValue(
                                        AnyValue.newBuilder()
                                            .setStringValue("otlp-example")
                                            .build())
                                    .build()))
                    .addScopeSpans(
                        ScopeSpans.newBuilder()
                            .setScope(
                                InstrumentationScope.newBuilder()
                                    .setName("my-instrumentation-scope")
                                    .build())
                            .addSpans(
                                Span.newBuilder()
                                    .setTraceId(traceIdByteString())
                                    .setSpanId(spanIdByteString())
                                    .setParentSpanId(spanIdByteString())
                                    .setName("my-span")
                                    .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                                    .setStartTimeUnixNano(
                                        TimeUnit.MILLISECONDS.toNanos(Instant.now().toEpochMilli()))
                                    .setEndTimeUnixNano(
                                        TimeUnit.MILLISECONDS.toNanos(
                                            Instant.now().plusSeconds(10).toEpochMilli()))
                                    .addAllAttributes(
                                        List.of(
                                            KeyValue.newBuilder()
                                                .setKey("key")
                                                .setValue(
                                                    AnyValue.newBuilder()
                                                        .setStringValue("value")
                                                        .build())
                                                .build()))
                                    .setStatus(
                                        Status.newBuilder()
                                            .setCode(Status.StatusCode.STATUS_CODE_OK)
                                            .setMessage("status message!")
                                            .build())
                                    .build())
                            .build())
                    .buildPartial())
            .build();
    System.out.print("Exporting trace request:\n" + request + "\n");

    var response = traceServiceStub.export(request);
    System.out.print("Export complete:\n" + response + "\n");
  }

  static ByteString traceIdByteString() {
    return toByteString(IdGenerator.random().generateTraceId(), TraceId.getLength());
  }

  static ByteString spanIdByteString() {
    return toByteString(IdGenerator.random().generateSpanId(), SpanId.getLength());
  }

  static ByteString toByteString(String str, int length) {
    return UnsafeByteOperations.unsafeWrap(OtelEncodingUtils.bytesFromBase16(str, length));
  }
}
