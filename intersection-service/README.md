# IntersectionServiceApp

## Overview

Validates intersection/district names (source of truth).

Part of the [TrafficFlow](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service publishes to the ActiveMQ queue `intersection-heartbeat-queue` — see [`../common/`](../common). Broker URL and queue name come from the common `co.wethinkcode.trafficflow.mq.MqConfig` class alongside it in this module.

## Project structure

```
intersection-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/trafficflow/
    │   ├── IntersectionServiceApp.java   the four lookup endpoints
    │   ├── IntersectionDirectory.java    loads once, indexes by ID and by district
    │   ├── IngestionClient.java          GET /intersections on ingestion-service
    │   ├── Intersection.java             this service's copy of the cleaned record
    │   ├── UpstreamUnavailable.java      a dependency is down -> 503
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
java -jar target/intersection-service.jar
```

Listens on port `7021`. The source of truth for intersection and district names: it loads
ingestion-service's cleaned records (`GET /intersections` on `INGESTION_URL`, default
`http://localhost:7020`) on first use and keeps them.

| Endpoint | Response |
|---|---|
| `GET /intersections` | `200` every intersection, in ingestion-service's order |
| `GET /intersections/{id}` | `200` the record, or `404 {"error": "no intersection with ID '...'"}` |
| `GET /districts` | `200` `[{"name": "Downtown", "intersectionIds": [...]}, ...]`, by name |
| `GET /districts/{name}` | `200` the district, or `404 {"error": "no district called '...'"}` |

```
curl localhost:7021/intersections/int-1005
```

Lookups ignore case and padding. An intersection whose district is missing from the source
(`INT-1015`) is still a valid intersection, but belongs to no district.

If ingestion-service is down, every lookup answers `503` with an `{"error": ...}` naming it,
never a `404`, and the next request tries again. The two can be started in either order.

## Test

```
mvn test
```

- `IntersectionDirectoryTest`: lookups, districts, loading once, retrying after a failure.
- `IngestionClientTest`: against a stand-in ingestion-service over real HTTP, including fields
  it doesn't know (ignored) and every way the call can fail.
- `IntersectionServiceAppTest`: the four endpoints, with their 200, 404 and 503 answers.
