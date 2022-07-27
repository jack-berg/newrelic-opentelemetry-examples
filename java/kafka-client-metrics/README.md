# OpenTelemetry Kafka Client Metrics

## Introduction

Demonstrate OpenTelemetry [kafka client metrics](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/kafka/kafka-clients/kafka-clients-2.6/library).

Requires docker.

## Run

Set the following environment variables:
* `NEW_RELIC_LICENSE_KEY=<your_license_key>`
  * Replace `<your_license_key>` with your [Account License Key](https://one.newrelic.com/launcher/api-keys-ui.launcher).
* Optional `OTLP_HOST=http://your-collector:4317`
  * The application is [configured](./src/main/java/com/newrelic/app/Application.java) to export to New Relic via OTLP by default. Optionally change it by setting this environment variable.

Run the application from a shell in the [java root](../) via:
```
./gradlew kafka-client-metrics:run
```

The application will:
- Spin up kafka via test containers
- Consumer and produce messages with random values to topics `red`, `green` and `blue`
- Report kafka metrics to new relic via OTLP

Check New Relic to confirm data is flowing.