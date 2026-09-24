# common — Asynchronous Decoupling (MQ)

## Overview

Part of the [TrafficFlow](../README.md) project. Holds the ActiveMQ broker shared
by the services below — not a service itself, so it has no port of its own. Two
independent integration points share this one broker:

### Topic: `congestion-topic`

Routing Service becomes aware of congestion changes via an ActiveMQ Topic instead of querying Congestion Service directly.

- Producer: `congestion-service` (`../congestion-service`)
- Consumer(s): `routing-service` (`../routing-service`)

Broker URL and topic name are shared via a common `co.wethinkcode.trafficflow.mq.MqConfig` class
(`BROKER_URL`, `TOPIC`). It's identical in every participating service's own source
tree — each service here is an independent Maven project with no shared parent pom,
so the common package is duplicated rather than imported from one place.

How it works:

- On every change of level, congestion-service publishes a JSON text message,
  `{"level": 4, "previousLevel": 6, "changedAt": "..."}`, marked with the property
  `ActiveMQ.Retain` (`MqConfig.RETAIN_PROPERTY`). The broker keeps the latest retained message
  as the topic's current value; congestion is one city-wide number, so that is the whole state.
- routing-service subscribes to `congestion-topic?consumer.retroactive=true`
  (`MqConfig.TOPIC + MqConfig.RETROACTIVE`), so the broker hands it that retained value as it
  subscribes, then every change after. A routing-service that starts or restarts after a change
  still knows the current level, without a durable subscription or a REST call.
- The retained value lives in the broker's memory: restarting the broker forgets it until the
  next change.

### Queue: `intersection-heartbeat-queue`

Intersection Watchdog notices when Intersection Service goes down by watching for
missed heartbeats / dead-lettered messages instead of polling its `/health` endpoint.

- Producer: `intersection-service` (`../intersection-service`)
- Consumer(s): `intersection-watchdog` (`../intersection-watchdog`)

Broker URL and queue name are shared the same way, via each service's own copy of
`co.wethinkcode.trafficflow.mq.MqConfig` (`BROKER_URL`, `HEARTBEAT_QUEUE`).

## Project structure

```
common/
├── docker-compose.yml
└── README.md
```

This folder holds the broker config and notes only — the actual publish/subscribe
code belongs in the producer/consumer services listed above (their poms already
depend on `activemq-client`, and each already has its own
`src/main/java/co/wethinkcode/trafficflow/mq/MqConfig.java` with the constants for
whichever topic/queue that service participates in).

## Build

Nothing to build here directly — this folder just brings up the broker used by the
services listed above.

## Run

```
docker compose up -d
```

- Broker URL for clients: `tcp://localhost:61616`
- Web console: http://localhost:8161 (default admin/admin)

Then start the producer/consumer services as usual (`mvn package && java -jar ...`
from their own directories at the project root).

## Test

```
docker compose ps          # confirm the broker container is healthy
```

`congestion-topic`, end to end, with congestion-service and routing-service running:

```
curl -X PUT localhost:7022/congestion -d '{"level": 4}'
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-1014'   # "congestionLevel": 4
```

The topic and its retained message are also visible in the web console under **Topics**.

## TODO

- Add `activemq-client` heartbeat-publish logic to `intersection-service`.
- Add `activemq-client` subscriber/alerting logic to `intersection-watchdog`.
