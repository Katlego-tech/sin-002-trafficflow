# CongestionServiceApp

## Overview

Tracks the city-wide Congestion Level (0-8).

Part of the [TrafficFlow](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ topic `congestion-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.trafficflow.mq.MqConfig` class alongside it in this module.

## Project structure

```
congestion-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/trafficflow/
    │   ├── CongestionServiceApp.java     GET and PUT /congestion, body validation
    │   ├── CongestionLevel.java          the level, 0 to 8: publish the change, then record it
    │   ├── CongestionChanged.java        the event published to congestion-topic
    │   ├── CongestionPublisher.java      "tell the city the level changed"
    │   ├── JmsCongestionPublisher.java   ... over JMS, to the ActiveMQ topic
    │   ├── LastPublished.java            "what did we last tell the city?"
    │   ├── RetainedLevelReader.java      ... read back from the topic's retained message
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/trafficflow/
```

## Build

```
mvn package
```

## Run

```
java -jar target/congestion-service.jar
```

Listens on port `7022`. Holds the city-wide congestion level, from 0 (clear) to 8 (gridlock).

| Endpoint | Response |
|---|---|
| `GET /congestion` | `200 {"level": 0, "updatedAt": null}` when no level was ever set |
| `PUT /congestion` with `{"level": 4}` | `200 {"level": 4, "updatedAt": "2026-09-24T08:00:00Z"}` |

```
curl -X PUT localhost:7022/congestion -d '{"level": 4}'
```

`PUT` because it replaces the state of this one resource: sending the same level twice changes
nothing, not even `updatedAt`, which stays `null` until the level first changes.

Each change is published to `congestion-topic` (see [`../common/`](../common)) **before** it is
recorded. If the broker can't be reached, the answer is
`503 {"error": "level not changed: ..."}` and the level stays as it was, so this service never
holds a level its subscribers weren't told about. It connects to the broker on the first change,
fails within 3 s rather than hanging, and reconnects on the next change after a failure. Start
the broker first (`cd ../common && docker compose up -d`) or every change is a 503.

The level survives a restart of this service. The broker keeps the last change published as
`congestion-topic`'s retained message, and that is where the level is read back from, at startup
or on the first request: a restarted service answers with the level and `updatedAt` it had. A
broker with nothing retained means no level was set since the broker started, so it starts at 0. If the
broker can't be reached, the level is unknown rather than assumed:
`503 {"error": "level unknown: ..."}` for `GET`, and `503 {"error": "level not changed: level
unknown: ..."}` for `PUT`, which publishes nothing. The next request tries again. The retained
message lives in the broker's memory, so restarting the broker itself loses it.

The body is checked strictly, never coerced. A level that isn't a whole number from 0 to 8
(`"3"`, `1.5`, `9`, `null`, missing), or a body that isn't a JSON object, is a
`400 {"error": ...}`, and the level stays as it was.

## Test

```
mvn test
```

- `CongestionLevelTest`: the range, the timestamp, setting the same level twice,
  publish-then-record when publishing fails, and reading the level back after a restart
  (recovered, never published, unknown then retried).
- `CongestionServiceAppTest`: GET and PUT over HTTP, including every malformed body and the 503s.
- `RetainedLevelReaderTest`: against a real in-process broker: the latest of two changes read
  back, nothing retained, an unreachable broker failing fast, and retained messages it can't
  read (no level, a null one, one outside 0 to 8) failing rather than reading as 0.
- `JmsCongestionPublisherTest`: against a real ActiveMQ broker run in-process (no Docker needed):
  the message, the retained latest level handed to a late subscriber, failing fast when the broker
  is down, and reconnecting when it is back.
