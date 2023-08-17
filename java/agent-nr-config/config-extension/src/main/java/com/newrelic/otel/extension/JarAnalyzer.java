package com.newrelic.otel.extension;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.events.EventEmitter;
import io.opentelemetry.api.events.GlobalEventEmitterProvider;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JarAnalyzer {

    private static JarAnalyzer INSTANCE = new JarAnalyzer();

    private static final String jarExtension = ".jar";

    private final Set<String> seenPaths = new HashSet<>();
    private final BlockingQueue<URL> toProcess = new LinkedBlockingDeque<>();
    private final AtomicBoolean isStarted = new AtomicBoolean(false);
    private EventEmitter eventEmitter;

    private JarAnalyzer() {
    }

    public static JarAnalyzer getInstance() {
        return INSTANCE;
    }

    public void start(OpenTelemetry openTelemetry) {
        if (isStarted.compareAndSet(false, true)) {
            eventEmitter = GlobalEventEmitterProvider.get().eventEmitterBuilder("event-emitter").setEventDomain("event-domain").build();
            System.out.println("start");
            System.out.println(eventEmitter);
            Thread thread = new Thread(INSTANCE::processUrls);
            thread.setDaemon(true);
            thread.start();
        }
    }

    void handle(ProtectionDomain protectionDomain) {
        if (protectionDomain != null) {
            CodeSource codeSource = protectionDomain.getCodeSource();
            if (codeSource != null) {
                URL location = codeSource.getLocation();
                if (location != null) {
                    try {
                        if (location.getProtocol().equals("jar")) {
                            // addJarProtocolURL
                            String path = location.getFile();
                            int index = path.lastIndexOf(jarExtension);
                            if (index > 0) {
                                path = path.substring(0, index + jarExtension.length());
                            }
                            if (seenPaths.add(path)) {
                                toProcess.add(new URL(path));
                            }
                        } else if (location.getFile().endsWith(jarExtension)) {
                            // addURLEndingWithJar
                            if (seenPaths.add(location.getFile())) {
                                toProcess.add(location);
                            }
                        } else {
                            // addOtherURL
                            String path = location.getFile();
                            int index = path.lastIndexOf(jarExtension);
                            if (index > 0) {
                                path = path.substring(0, index + jarExtension.length());
                            }
                            if (seenPaths.add(path)) {
                                toProcess.add(new URL(location.getProtocol(), location.getHost(), path));
                            }
                        }
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void processUrls() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                URL urlToProcess = toProcess.poll(100, TimeUnit.MILLISECONDS);
                if (urlToProcess == null) {
                    continue;
                }
                JarInfo jarInfo = JarInfo.create(urlToProcess);
                System.out.println("url: " + urlToProcess + System.lineSeparator() + "jarInfo: " + jarInfo + System.lineSeparator());

                AttributesBuilder builder = Attributes.builder();
                builder.put("jar.version", jarInfo.getVersion());
                jarInfo.getAttributes().forEach((key, value) -> builder.put("jar." + key, value));
                eventEmitter.emit("JarAnalyzed", builder.build());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
