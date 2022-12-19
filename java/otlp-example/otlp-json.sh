# /bin/bash

curl -v -X POST 'https://otlp.nr-data.net:4318/v1/traces' \
  -H "api-key: ${NEW_RELIC_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d @payload.json