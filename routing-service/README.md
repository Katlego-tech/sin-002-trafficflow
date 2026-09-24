# RoutingServiceApp

## Overview

Provides estimated travel times based on congestion and intersection.

Part of the [TrafficFlow](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `congestion-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.trafficflow.mq.MqConfig` class alongside it in this module.

## Project structure

```
routing-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/trafficflow/
    │   ├── RoutingServiceApp.java      GET /travel-time: validate, then estimate
    │   ├── TravelTimeEstimator.java    the model, and every assumption it makes
    │   ├── DistrictMap.java            crossing minutes between districts (an assumption)
    │   ├── IntersectionLookup.java     "is this a real intersection?"
    │   ├── IntersectionClient.java     ... asked of intersection-service
    │   ├── CongestionSource.java       "what is the congestion level?"
    │   ├── RestCongestionSource.java   ... asked of congestion-service
    │   ├── Intersection.java           the fields of intersection-service's record it uses
    │   ├── UpstreamUnavailable.java    a dependency is down -> 503
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
java -jar target/routing-service.jar
```

Listens on port `7023`. Calls intersection-service (`INTERSECTION_SERVICE_URL`, default
`http://localhost:7021`) and congestion-service (`CONGESTION_SERVICE_URL`, default
`http://localhost:7022`) directly over HTTP.

```
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-1014'
```

```json
{"from": {"id": "INT-1001", "district": "Downtown", "signalType": "4-way", "active": true},
 "to": {"id": "INT-1014", "district": "Uptown", "signalType": "pedestrian", "active": true},
 "congestionLevel": 4, "congestionAsOf": "2026-09-24T08:00:00Z",
 "baseMinutes": 14.0, "congestionMultiplier": 2.0, "estimatedMinutes": 28.0, "warnings": []}
```

Both ends are validated against intersection-service before anything is estimated:

| Answer | When |
|---|---|
| `200` | the estimate |
| `400` | an end is missing, or both ends are the same intersection |
| `404` | an end isn't a known intersection. The message says which end: `'INT-9999' (to) is not a known intersection` |
| `503` | intersection-service is down, so routes can't be validated, or congestion-service is down |

### The estimate

```
(district crossing + signal delay at each end) x (1 + 0.25 x congestion level)
```

Level 0 is free-flowing, level 4 doubles the trip, and level 8 triples it.

- **District crossing:** the data names districts but has no roads, so the crossing times are an
  assumption, kept in `DistrictMap`: 4 minutes within a district, 6 to 16 between two.
- **Signal delay:** roundabout 0.5 min, 4-way and pedestrian 1.0, stop sign 1.5, and 2.0 for
  inactive signals whatever their type.
- **Gaps in the data** still give an estimate, never a silent guess. A missing or unmapped
  district is costed as the longest crossing (16). An unknown signal type gets a typical 1.0.
  Each assumption is a line in `warnings`, e.g. for `INT-1015`:
  `"INT-1015 has no district in the source data, so the longest crossing is assumed"`.

## Test

```
mvn test
```

- `DistrictMapTest`, `TravelTimeEstimatorTest`: the model, and every warning.
- `IntersectionClientTest`, `RestCongestionSourceTest`: against stand-in services over real
  HTTP, including every way each call can fail.
- `RoutingServiceAppTest`: `/travel-time` with its 200, 400, 404 and 503 answers.
