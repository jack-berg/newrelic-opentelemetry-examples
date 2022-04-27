"use strict";

const { v4: uuidv4 } = require("uuid");
const { Resource } = require("@opentelemetry/resources");
const { SemanticResourceAttributes } = require("@opentelemetry/semantic-conventions");
const { OTLPTraceExporter } = require("@opentelemetry/exporter-trace-otlp-http");
const { diag, context, trace, DiagConsoleLogger, DiagLogLevel} = require("@opentelemetry/api");
const { WebTracerProvider } = require('@opentelemetry/sdk-trace-web');
const { getWebAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-web');
const { SimpleSpanProcessor, ConsoleSpanExporter } = require('@opentelemetry/sdk-trace-base');
const { registerInstrumentations } = require('@opentelemetry/instrumentation');
const { ZoneContextManager } = require('@opentelemetry/context-zone');
const { B3Propagator } = require('@opentelemetry/propagator-b3');

// enable logging ONLY for developement
// this is useful for debugging instrumentation issues
// remove from production after issues (if any) are resolved
// view more logging levels here: https://github.com/open-telemetry/opentelemetry-js-api/blob/main/src/diag/types.ts#L67
// diag.setLogger(
//   new DiagConsoleLogger(),
//   DiagLogLevel.DEBUG,
// );

const resource = new Resource({
  [SemanticResourceAttributes.SERVICE_NAME]: "OpenTelemetry-Web-Example",
  [SemanticResourceAttributes.SERVICE_INSTANCE_ID]: uuidv4(),
});

const exporter = new OTLPTraceExporter({
  // uncomment this code if not using environment variables
  // url: '<Add Endpoint Here>',
  // headers: {
  //   'api-key': '<New Relic License Key Here>'
  // }
});

const provider = new WebTracerProvider({
  resource
});

provider.addSpanProcessor(new SimpleSpanProcessor(exporter));
provider.register({
  contextManager: new ZoneContextManager(),
  propagator: new B3Propagator(),
});

registerInstrumentations({
  instrumentations: [
    getWebAutoInstrumentations({
      // load custom configuration for xml-http-request instrumentation
      '@opentelemetry/instrumentation-xml-http-request': {
        clearTimingResources: true,
      },
    }),
  ],
});

const webTracer = provider.getTracer('example-tracer-web');

const getData = (url) => new Promise((resolve, reject) => {
  const req = new XMLHttpRequest();
  req.open('GET', url, true);
  req.setRequestHeader('Content-Type', 'application/json');
  req.setRequestHeader('Accept', 'application/json');
  req.onload = () => {
    resolve();
  };
  req.onerror = () => {
    reject();
  };
  req.send();
});

// example of keeping track of context between async operations
const prepareClickEvent = () => {
  const url1 = 'https://httpbin.org/get';

  const element = document.getElementById('button1');

  const onClick = () => {
    for (let i = 0, j = 2; i < j; i += 1) {
      const span1 = webTracer.startSpan(`files-series-info-${i}`);
      context.with(trace.setSpan(context.active(), span1), () => {
        getData(url1).then((_data) => {
          trace.getSpan(context.active()).addEvent('fetching-span1-completed');
          span1.end();
        }, () => {
          trace.getSpan(context.active()).addEvent('fetching-error');
          span1.end();
        });
      });
    }
  };
  element.addEventListener('click', onClick);
};

window.addEventListener('load', prepareClickEvent);