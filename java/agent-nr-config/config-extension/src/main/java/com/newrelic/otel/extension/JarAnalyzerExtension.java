package com.newrelic.otel.extension;

import io.opentelemetry.javaagent.bootstrap.InstrumentationHolder;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.javaagent.tooling.AgentExtension;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import net.bytebuddy.agent.builder.AgentBuilder;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class JarAnalyzerExtension implements AgentExtension, AgentListener {
  @Override
  public AgentBuilder extend(AgentBuilder agentBuilder, ConfigProperties config) {
    Instrumentation inst = InstrumentationHolder.getInstrumentation();
    if (inst != null) {
      inst.addTransformer(new ClassFileTransformer() {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
          JarAnalyzer.getInstance().handle(protectionDomain);
          return null;
        }
      });
    }
    return agentBuilder;
  }

  @Override
  public String extensionName() {
    return "jar-analyzer";
  }

  @Override
  public void afterAgent(
      io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk autoConfiguredOpenTelemetrySdk) {
    JarAnalyzer.getInstance().start(autoConfiguredOpenTelemetrySdk.getOpenTelemetrySdk());

  }
}
