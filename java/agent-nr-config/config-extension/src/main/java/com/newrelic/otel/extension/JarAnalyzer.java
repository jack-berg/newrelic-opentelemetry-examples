package com.newrelic.otel.extension;

import com.google.common.util.concurrent.RateLimiter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.events.EventEmitter;
import io.opentelemetry.api.events.GlobalEventEmitterProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JarAnalyzer {

  static final Logger JAR_ANALYZER_LOGGER = Logger.getLogger(JarAnalyzer.class.getName());

  private static final JarAnalyzer INSTANCE = new JarAnalyzer();

  static final String JAR_EXTENSION = ".jar";

  private final Set<URI> seenUris = new HashSet<>();
  private final BlockingQueue<URL> toProcess = new LinkedBlockingDeque<>();
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private EventEmitter eventEmitter;

  private JarAnalyzer() {}

  public static JarAnalyzer getInstance() {
    return INSTANCE;
  }

  public void start(OpenTelemetry openTelemetry) {
    if (isStarted.compareAndSet(false, true)) {
      eventEmitter =
          GlobalEventEmitterProvider.get()
              .eventEmitterBuilder("event-emitter")
              .setEventDomain("event-domain")
              .build();
      Thread thread = new Thread(INSTANCE::processUrls);
      thread.setDaemon(true);
      thread.start();
    }
  }

  void handle(ProtectionDomain protectionDomain) {
    if (protectionDomain == null) {
      return;
    }
    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null) {
      return;
    }
    URL location = codeSource.getLocation();
    if (location == null) {
      return;
    }
    URI locationUri;
    try {
      locationUri = location.toURI();
    } catch (URISyntaxException e) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Unable to get uri for url: " + location, e);
      return;
    }

    if (!seenUris.add(locationUri)) {
      return;
    }

    if ("jrt".equals(location.getProtocol())) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Skipping java runtime module: " + location);
      return;
    }
    if (!location.getFile().endsWith(JAR_EXTENSION)) {
      JAR_ANALYZER_LOGGER.log(Level.WARNING, "Skipping unrecognized code location: " + location);
      return;
    }

    toProcess.add(location);
  }

  private void processUrls() {
    RateLimiter rateLimiter = RateLimiter.create(10);

    while (!Thread.currentThread().isInterrupted()) {
      URL url = null;
      try {
        url = toProcess.poll(100, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (url == null) {
        continue;
      }
      rateLimiter.acquire();
      try {
        Attributes jarAttributes = JarUtil.toJarAttributes(url);
        eventEmitter.emit("dependency-detected", jarAttributes);
      } catch (Exception e) {
        JAR_ANALYZER_LOGGER.log(Level.WARNING, "Error processing jar url: " + url);
      }
    }
  }
}
