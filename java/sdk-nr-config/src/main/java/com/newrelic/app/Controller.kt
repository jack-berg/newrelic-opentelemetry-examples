package com.newrelic.app

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import org.apache.logging.log4j.LogManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
class Controller {
    private val MY_COUNTER = METER.counterBuilder("my-custom-counter").setDescription("A counter to count things").build()
    @GetMapping("/ping")
    @Throws(InterruptedException::class)
    fun ping(): String {
        val span = TRACER.spanBuilder("ping").startSpan()
        try {
            span.makeCurrent().use { scope ->
                val sleepTime = Random().nextInt(200)
                Thread.sleep(sleepTime.toLong())
                MY_COUNTER.add(sleepTime.toLong(), Attributes.of(AttributeKey.stringKey("method"), "ping"))
                LOGGER.info("A sample log message!")

                // Throw an exception ~25% of the time
                check(Random().nextInt(4) != 0) { "Error!" }
                return "pong"
            }
        } finally {
            span.end()
        }
    }

    companion object {
        private val TRACER = GlobalOpenTelemetry.getTracerProvider()[Application::class.java.name]
        private val METER = GlobalOpenTelemetry.getMeterProvider()[Application::class.java.name]
        private val LOGGER = LogManager.getLogger(Controller::class.java)
    }
}