#!/bin/bash

#   --set global.nrStaging=${{ inputs.is-staging }} \
helm upgrade --install newrelic-bundle newrelic/nri-bundle \
  -f values-newrelic.yaml \
  --set global.licenseKey=${NEW_RELIC_API_KEY} \
  --set global.cluster=localhost \
  --namespace nr-host-monitoring --create-namespace