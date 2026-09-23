# IngestionServiceApp

## Overview

Parses and cleans `intersections-legacy.csv`, a messy legacy export of intersections, districts, and signal types data, and is the
first stop in the TrafficFlow pipeline. Independent Maven module, no parent pom.

Part of the [TrafficFlow](../README.md) project.

## Known data issues

`intersections-legacy.csv` is deliberately messy — cleaning it is the point of this service. Look
out for (and handle) at least:

- **Inconsistent casing** in IDs, names, and status/category values (`Active` /
  `active` / `ACTIVE`)
- **Padding** — leading/trailing spaces, and the occasional double space, inside
  fields
- **Duplicate records** for the same real-world entity, written with a different ID
  casing/format and/or slightly different field values
- **Inconsistent date formats** (`YYYY-MM-DD`, `MM/DD/YYYY`, `DD-MM-YYYY`, one- and
  two-digit months/days) and outright invalid dates
- **Missing / placeholder values** — blank fields, `N/A`, `n/a`, `TBD`, `unknown`,
  `-`, `NaN`
- **Invalid or non-numeric values** in numeric columns (negative counts, spelled-out
  numbers, unrealistic values)
- **Inconsistent boolean/flag representations** (`Y`/`N`, `yes`/`no`, `1`/`0`,
  `true`/`FALSE`)
- **Naming/spelling variants** for the same thing (e.g. regional spelling
  differences, synonyms)

## Worked example

A few raw rows from `intersections-legacy.csv`, and one reasonable cleaned shape for
them. Your field names/casing conventions don't need to match this exactly — the
point is normalizing consistently and handling the duplicate/missing cases, not
hitting this exact JSON.

Raw:

```csv
intersection_id,District ,signal_type,active_flag
INT-1001, Downtown ,4-way,Y
INT-1005,Downtown,Roundabout,true
int-1005,downtown ,ROUNDABOUT,TRUE
INT-1007,Eastside,,1
INT-1015,,4-way,Y
```

Cleaned:

```json
[
  { "id": "INT-1001", "district": "Downtown", "signalType": "4-way",       "active": true },
  { "id": "INT-1005", "district": "Downtown", "signalType": "roundabout",  "active": true },
  { "id": "INT-1007", "district": "Eastside", "signalType": null,          "active": true },
  { "id": "INT-1015", "district": null,       "signalType": "4-way",       "active": true }
]
```

What happened:
- `INT-1001`: trimmed the padded district (`" Downtown "` → `"Downtown"`); flag `Y` → `true`.
- `INT-1005` / `int-1005`: same real-world intersection under two ID casings and two
  signal-type casings — collapsed to a single record.
- `INT-1007`: signal type was blank in the source — kept as an explicit `null` rather
  than dropped or guessed, so downstream services can see it's missing.
- `INT-1015`: same idea for a missing district.

## Project structure

```
ingestion-service/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/co/wethinkcode/trafficflow/
    │   │   ├── IngestionServiceApp.java      cleans the CSV at startup, serves the result
    │   │   ├── IntersectionCsvCleaner.java   pass 1: clean each row; pass 2: merge duplicate IDs
    │   │   ├── Values.java                   trimming, placeholders, flags, casing, spelling keys
    │   │   ├── CleanIntersection.java        one cleaned record
    │   │   └── CleaningReport.java           the records + every rejected row and why
    │   └── resources/intersections-legacy.csv
    └── test/java/co/wethinkcode/trafficflow/
```

## Build

```
mvn package
```

## Run

```
java -jar target/ingestion-service.jar
```

Listens on port `7020`. The CSV is cleaned once at startup (18 rows → 17 intersections).

| Endpoint | Response |
|---|---|
| `GET /health` | `OK` |
| `GET /intersections` | `200` JSON array of cleaned records, sorted by ID number |
| `GET /report` | `200` `{"rowsRead": 18, "intersections": [...], "rejected": [{"line", "reason", "raw"}]}` |

```
curl localhost:7020/intersections
```

Each record is `{"id", "district", "signalType", "active", "notes"}`. A value that is missing,
a placeholder, or contradicted by a duplicate row is an explicit `null`, and `notes` says why.

How each data issue is handled:

- **Casing and padding:** every field is trimmed and inner whitespace collapsed. IDs are upper
  case (`int-1002` → `INT-1002`), districts title case, signal types lower case.
- **Placeholders** (`N/A`, `n/a`, `TBD`, `unknown`, `-`, `NaN`, blank, ...) become `null`, with a
  note saying what was there.
- **Flags:** `Y/yes/1/true` → `true`, `N/no/0/false` → `false`, in any casing. Anything else is
  `null`, never a guess.
- **Spelling variants** of a signal type (`4-Way`, `Stop Sign`) are matched on letters and digits
  onto `4-way`, `pedestrian`, `roundabout`, `stop-sign`. An unrecognised type is kept and flagged.
- **Duplicates** are rows whose IDs match once normalised. They are merged field by field: a
  present value beats a missing one, the majority wins, and a tie stays `null`. Each
  disagreement goes into `notes`, as do the file lines that were merged.
- **Malformed rows** (wrong field count, no ID) are rejected with a reason, listed at `/report`,
  and never stop the rest of the file. A header missing a column fails startup loudly.
- **Dates and numbers:** the file has neither column, so there is nothing to normalise.

## Test

```
mvn test
```

- `ValuesTest`: the per-value rules.
- `IntersectionCsvCleanerTest`: each data issue on small inline CSVs, including merging,
  disagreeing duplicates, rejected rows, and a reordered or broken header.
- `BundledCsvTest`: the real file, pinned to the worked example above.
- `IngestionServiceAppTest`: the JSON as other services receive it, over HTTP.
