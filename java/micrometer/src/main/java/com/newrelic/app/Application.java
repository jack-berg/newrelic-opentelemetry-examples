package com.newrelic.app;

import com.newrelic.telemetry.Attributes;
import com.newrelic.telemetry.micrometer.NewRelicRegistry;
import com.newrelic.telemetry.micrometer.NewRelicRegistryConfig;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public NewRelicRegistryConfig newRelicConfig() {
    return new NewRelicRegistryConfig() {
      @Override
      public String get(String key) {
        return null;
      }

      @Override
      public String apiKey() {
        return System.getenv("NEW_RELIC_API_KEY");
      }

      @Override
      public Duration step() {
        return Duration.ofSeconds(5);
      }

      @Override
      public String serviceName() {
        return "my-micrometer-service";
      }
    };
  }

  @Bean
  public NewRelicRegistry newRelicMeterRegistry(NewRelicRegistryConfig config)
      throws UnknownHostException {
    NewRelicRegistry newRelicRegistry =
        NewRelicRegistry.builder(config)
            .commonAttributes(
                new Attributes().put("host", InetAddress.getLocalHost().getHostName()))
            .build();
    newRelicRegistry.config().meterFilter(MeterFilter.ignoreTags("plz_ignore_me"));
    newRelicRegistry.config().meterFilter(MeterFilter.denyNameStartsWith("jvm.threads"));
    newRelicRegistry.start(new NamedThreadFactory("newrelic.micrometer.registry"));
    return newRelicRegistry;
  }
}
