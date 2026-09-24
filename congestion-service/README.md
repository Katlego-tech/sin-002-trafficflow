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
    │   ├── CongestionServiceApp.java   GET and PUT /congestion, body validation
    │   ├── CongestionLevel.java        the level, 0 to 8
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

Listens on port `7022`. Holds the city-wide congestion level, from 0 (clear) to 8 (gridlock),
starting at 0.

| Endpoint | Response |
|---|---|
| `GET /congestion` | `200 {"level": 0, "updatedAt": null}` |
| `PUT /congestion` with `{"level": 4}` | `200 {"level": 4, "updatedAt": "2026-09-24T08:00:00Z"}` |

```
curl -X PUT localhost:7022/congestion -d '{"level": 4}'
```

`PUT` because it replaces the state of this one resource: sending the same level twice changes
nothing, not even `updatedAt`, which stays `null` until the level first changes.

The body is checked strictly, never coerced. A level that isn't a whole number from 0 to 8
(`"3"`, `1.5`, `9`, `null`, missing), or a body that isn't a JSON object, is a
`400 {"error": ...}`, and the level stays as it was.

## Test

```
mvn test
```

- `CongestionLevelTest`: the range, the timestamp, and setting the same level twice.
- `CongestionServiceAppTest`: GET and PUT over HTTP, including every malformed body.
