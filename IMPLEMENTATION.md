# TrafficFlow: implementation notes

Stages 1 to 3 are implemented: ingestion-service cleans the legacy CSV, three domain services
talk over REST, and congestion reaches routing-service over the ActiveMQ topic
`congestion-topic` instead of a REST call. Stage 4 (intersection-watchdog) is not built; its
module is still the brief's scaffold.

There are 164 JUnit tests across the four modules, and every stage was also run end to end on
the real jars and the broker in `common/` (see [Verified end to end](#verified-end-to-end)).

```
ingestion-service ◀── GET /intersections ── intersection-service ◀── GET /intersections/{id} ── routing-service
     (7020)                                     (7021)                                          (7023)
                                                                                                  ▲ retroactive
                                                                                                  │ subscription
                              congestion-service (7022) ── publish (retained) ──▶ congestion-topic
                                   ▲        │
                         PUT /congestion    └── reads the retained level back after a restart
```

## Run it

Java 17+, Maven, and Docker for the broker. From the project root:

```
cd common && docker compose up -d && cd ..
for m in ingestion-service intersection-service congestion-service routing-service; do (cd $m && mvn -q package); done
```

Then start each service in its own terminal, from its own folder:

```
cd ingestion-service && java -jar target/ingestion-service.jar
cd intersection-service && java -jar target/intersection-service.jar
cd congestion-service && java -jar target/congestion-service.jar
cd routing-service && java -jar target/routing-service.jar
```

The start order doesn't matter. intersection-service fetches from ingestion-service on first
use and retries on the next request if that fails; routing-service's subscription reconnects
on its own; congestion-service reads its level from the broker on the first request if it
couldn't at startup.

| Variable | Used by | Default |
|---|---|---|
| `INGESTION_URL` | intersection-service | `http://localhost:7020` |
| `INTERSECTION_SERVICE_URL` | routing-service | `http://localhost:7021` |

The broker address and topic name come from each module's `mq/MqConfig.java`.

### A walkthrough

With the four services up on a freshly started broker:

```
curl localhost:7020/report                                    # rowsRead 18, 17 intersections, nothing rejected
curl localhost:7021/intersections/int-1005                    # two rows merged (lines 6, 7); any casing works
curl localhost:7021/districts                                 # five districts and their intersections
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-1014'   # congestionLevel null + a warning; 14 min
curl -X PUT localhost:7022/congestion -d '{"level": 4}'
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-1014'   # congestionLevel 4; 28 min
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-1015'   # 36 min; warns INT-1015 has no district
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-9999'   # 404: 'INT-9999' (to) is not a known intersection
```

Then restart congestion-service (Ctrl-C, `java -jar` again) and ask it:

```
curl localhost:7022/congestion                                # still level 4, with the original updatedAt
```

And stop intersection-service:

```
curl 'localhost:7023/travel-time?from=INT-1001&to=INT-1014'   # 503: routes can't be validated
```

Start it again and the same request answers 200, with no restart of routing-service.

## Endpoints

| Service | Endpoint | Notes |
|---|---|---|
| ingestion-service | `GET /intersections`, `GET /report` | cleaned once at startup; the report lists rejected rows and why |
| intersection-service | `GET /intersections`, `GET /intersections/{id}`, `GET /districts`, `GET /districts/{name}` | 404 unknown; 503 if ingestion-service is down |
| congestion-service | `GET /congestion`, `PUT /congestion` | 400 for a bad level; 503 if the broker can't be reached |
| routing-service | `GET /travel-time?from=&to=` | 400 missing or same ends; 404 names the unknown end; 503 if intersection-service is down |

Every service also answers `GET /health` with `OK`. Each module's README has the full
request and response shapes.

## Stage 1: cleaning `intersections-legacy.csv`

- **Cleaned once, at startup.** The file is bundled with the service and never changes, so
  there is nothing to gain from cleaning per request. 18 rows become 17 intersections.
- **One form for every value.** Fields are trimmed and inner spaces collapsed. IDs are upper
  case, districts title case, signal types lower case.
- **Missing stays missing.** Placeholders (`N/A`, `TBD`, `unknown`, `-`, `NaN`, blank, ...)
  become an explicit `null`, with a note saying what was there. `active_flag` variants map to
  `true`/`false`, and anything else is `null`, never a guess.
- **Signal types** are matched on letters and digits onto `4-way`, `pedestrian`,
  `roundabout`, `stop-sign`, so `4-Way` and `Stop Sign` both normalise. An unrecognised type is
  kept as written and flagged, rather than thrown away.
- **Duplicates** are rows whose IDs match once normalised (`INT-1005` / `int-1005`). Matching
  on district and type instead would merge two real intersections that happen to share both.
  Rows are merged field by field: a present value beats a missing one, the majority wins, and a
  tie stays `null`. "First row wins" was rejected because it silently picks one. Every
  disagreement, and the file lines merged, go into `notes`.
- **A bad row never stops the file.** A row with the wrong field count or no ID is rejected
  with a reason, visible at `/report`. A header missing a column fails startup loudly.
- **Parsing** uses opencsv's RFC 4180 parser, not `split(",")`, which breaks on quoted fields.
- The file has no date or numeric columns, so those issue categories don't apply here.

## Stage 2: the REST services

- **intersection-service is the source of truth** for IDs and district names. It loads
  ingestion-service's records on first use, and lookups ignore case and padding. An
  intersection with no district (INT-1015) is still a valid intersection.
- **routing-service validates both ends before estimating anything.** A 404 names the end that
  is wrong. If intersection-service is down, the answer is 503 at once. It keeps no cached copy,
  which would validate routes against stale data.
- **The travel-time model.** The data names districts but has no roads, so the road model is
  an assumption, kept in one class, `DistrictMap`:
  - a crossing time for each pair of districts: 4 minutes within one, 6 to 16 between two. A
    graph of roads would be the same assumption with more code;
  - a delay at each end: roundabout 0.5 min, 4-way and pedestrian 1.0, stop sign 1.5, and 2.0
    for an inactive signal of any type;
  - the total multiplied by `1 + 0.25 × level`, so level 4 doubles the trip and level 8
    triples it.
- **Gaps in the data still get an estimate, never a silent guess.** A missing district is
  costed as the longest crossing, an unknown signal type as a typical 1.0, and each assumption
  is a line in `warnings`. A 404 would be wrong: the intersection is real, the data is incomplete.
- **`PUT /congestion`, not `POST`.** It replaces the state of one resource, so sending the
  same level twice is harmless and changes nothing, not even `updatedAt`.

## Stage 3: `congestion-topic`, a retained value instead of a durable subscription

- **Congestion is one city-wide number, so the latest message is the whole state.**
  congestion-service marks each change with `ActiveMQ.Retain`, and the broker keeps the latest.
  routing-service subscribes to `congestion-topic?consumer.retroactive=true`, so the broker hands
  it that value as it subscribes, then every change after it.
- **Why not a durable subscription:** it would replay every change missed while routing-service
  was down, when only the latest matters, and it would still know nothing on the very first
  start. The retained value covers both. A REST call at startup was also rejected: it would put
  back the coupling this stage removes.
- **The REST poll is deleted.** routing-service no longer knows congestion-service's address;
  stopping congestion-service doesn't affect it.
- **Publish, then record.** congestion-service changes its level only after the broker has
  acknowledged the event. With the broker down, `PUT` returns 503 "level not changed" within
  3 s instead of hanging, so it never holds a level its subscribers weren't told about.
- **An unknown level is never reported as clear roads.** Before any level has arrived,
  routing-service reports `"congestionLevel": null`, assumes level 0, and says so in `warnings`.
  A `0` plus a separate "known" flag was rejected: two fields to say one thing, one of them
  wrong. A message it can't read (no level, a null level, a level outside 0 to 8, no time) is
  logged and ignored.
- **congestion-service survives its own restart.** It used to start at 0 in memory, so a
  restart reported "never set" while the topic, and routing-service, held the real level. It now
  reads the retained value back (a short retroactive subscription) before it answers. If the
  broker can't be reached, its level is unknown and it answers 503, rather than 0; the next
  request tries again.

## Verified end to end

On the real jars, against `apache/activemq-classic:5.18.3` from `common/`:

| Scenario | Result |
|---|---|
| levels 0, 4, 8 | 14, 28, 42 min for INT-1001 → INT-1014 |
| the same `PUT` twice | unchanged, `updatedAt` included |
| unknown ID, unknown end, bad level | 404 naming the ID or end; 400 for the level |
| ingestion-service down, then up | intersection-service 503, then 200 with no restart |
| intersection-service down, then up | routing-service 503 "routes can't be validated", then 200 with no restart |
| before any level was set | `"congestionLevel": null` and a warning; 14 min |
| routing-service stopped while the level changed to 6, then started | knew 6 at once |
| congestion-service stopped | routing-service kept answering with the last level |
| junk published to the topic | ignored; the level unchanged |
| broker stopped | `PUT` 503, level unchanged; routing-service kept answering; both reconnected once it was back |
| level 4, congestion-service restarted | `GET` still level 4, with the original `updatedAt` |
| broker stopped, congestion-service restarted | `GET` and `PUT` 503 "level unknown"; nothing published |

## With more time

- **The retained level lives in broker memory.** Restarting the broker loses it:
  congestion-service then reads back nothing and starts at 0, while running routing-services
  keep the last level they heard. A persistent broker configuration would close that gap.
- **Stage 4**, the watchdog: a heartbeat from intersection-service on
  `intersection-heartbeat-queue`, with the watchdog reporting a missed or dead-lettered one.
- **Real road data** would replace `DistrictMap` without touching anything else.
- **Pinned versions.** Javalin 5.6.3 (Jetty 11.0.17) and ActiveMQ 5.18.3 are pinned by the
  brief and carry known security advisories; they would be upgraded together.
