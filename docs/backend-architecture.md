# CO₂ Diet — Backend Architecture & Module Documentation

> Status: design / pre-implementation · Owner: Backend (Tomris)
> Audience: developers joining the backend, frontend devs integrating against it, self-hosters.

---

## 1. Principles (read this first — they drive every decision below)

The product is **privacy-first, local-first, open source, and self-hostable**. Those four constraints are not flavour text; they decide the architecture.

The single most important consequence: **the backend is deliberately thin.** It is a *reference-data service* (food + CO₂), a *sync engine*, and a *contribution pipeline*. It is **not** a store of user activity. Personal data (meals, weight, profile) lives on the device. Designing the backend as if it owns user data would violate the product.

Core principles:
- Fast enough for daily use, offline-first on the client.
- No guilt-based UX; climate awareness without ideology.
- Privacy by design — GDPR applies, no analytics, no tracking, no ad SDKs.
- Open and transparent — anyone can read the code and run their own instance.
- Scientifically credible — CO₂ is an *estimation*, and we say so (see confidence, §6.1).

---

## 2. Architecture at a glance

**Pattern:** Modular monolith — Java / Spring Boot 3 (Java 21). One deployable, strict module boundaries. We extract a separate service only when measured load demands it (ingestion or search first), never by default.

```
Clients (local mode · account mode)
        │  REST / TLS
        ▼
API gateway layer  (auth, rate limiting)
        ▼
┌──────────────── Modular monolith (Spring Boot) ────────────────┐
│ Catalog · CO₂ engine · Sync        (read / reference path)     │
│ Identity · Legal/Consent           (identity & legal)          │
│ Contributions · Ingestion · Backup (write & data pipeline)     │
└────────────────────────────────────────────────────────────────┘
        ▼
PostgreSQL · Redis · OpenSearch (later) · Object store · RabbitMQ
        ▲
External: Open Food Facts · USDA · OAuth providers (Apple/Google/GitHub)
```

---

## 3. Tech stack

| Layer | Choice | Notes |
|---|---|---|
| Language / runtime | **Java 21 (LTS)** | Virtual threads help the I/O-heavy sync & ingestion paths |
| Framework | **Spring Boot 3.x** | Spring Web (REST), Spring Security, Spring Batch |
| Primary DB | **PostgreSQL (self-hosted)** | Relational; `pg_trgm` + GIN for fuzzy food search |
| Migrations | **Flyway** | Reviewable, open-source friendly |
| Cache | **Redis** | Hot barcode / lookup caching |
| Search (later) | **OpenSearch** | Only if Postgres FTS isn't enough at scale |
| Object storage | **S3-compatible (MinIO self-hostable)** | OFF dumps, contributed images |
| Messaging | **RabbitMQ** | Async ingestion + moderation events |
| Batch | **Spring Batch** | OFF / USDA bulk import |
| Auth server | **Keycloak** | OIDC, self-hostable (see §9) |

All components are self-hostable and open source so the "anyone can run their own instance" promise holds.

---

## 4. Key decisions (rationale for new devs)

**Modular monolith, not microservices.** Open source + self-hostable is brutal on microservices: a monolith is one repo to clone and one container to run; a microservice version is a docker-compose orchestra that walls off contributors and self-hosters. The load profile (read-heavy reference data, single clear authority) doesn't justify distributed complexity yet. Module boundaries give us clean seams *without* the network tax. Extract a service only when production data forces it — the first candidates are **Ingestion** (heavy, spiky batch) and **Search** (if we outgrow Postgres FTS), both *infrastructure* boundaries, not domain ones.

**PostgreSQL, not Firebase.** Firestore is proprietary and Google-hosted — no production self-hosting, which kills "run your own instance," and building on Google's infra contradicts the privacy positioning (plus GDPR sub-processor concerns for the EU). Our data is relational and sync-heavy (catalog, CO₂ tables, version-based delta sync); Firestore is NoSQL with no joins, no real full-text search, and per-read pricing that scales badly for a free app. Since we're building a real Spring backend anyway, Firebase's "client skips the backend" value doesn't apply. *Exception:* FCM is fine **only** for push notifications — it doesn't lock up our data.

---

## 5. Modules (bounded contexts)

Each module hides its own entities behind a small interface. That discipline is what keeps the monolith healthy and makes later extraction cheap.

| Module | Responsibility |
|---|---|
| **Catalog** | Food products, nutrition, barcode index, category taxonomy, search. Highest-traffic surface. |
| **CO₂ Estimation** | Versioned estimation engine, factors, regional/transport adjustments, confidence scoring. Our differentiator. |
| **Sync** | Delta sync of catalog + CO₂ data down to devices for offline use. |
| **Identity** | *Optional by design.* Social login (Apple/Google/GitHub), passkeys, JWT, moderation roles. Local mode never touches it. |
| **Contributions & Moderation** | User corrections/additions, CO₂ feedback, moderation queue, push-back to Open Food Facts. |
| **Ingestion** | OFF + USDA import/refresh pipeline, normalisation into our schema. |
| **Encrypted Backup** | *Optional, account mode only.* Stores **opaque end-to-end-encrypted blobs only** — the server cannot read meals/weight/profile. |
| **Legal / Consent** | Serves current Terms / Privacy / Impressum / Disclaimer versions; records acceptance (timestamp, app version, policy version) for account users. |
| **Admin** | Role-gated interface to manage the core database, factor tables, moderation overrides. |
| **AI Gateway** *(later)* | Meal recognition + AI coach, behind the same CO₂ port; enforces the "AI-generated, may be inaccurate" disclosure. |

---

## 6. Deep dives

### 6.1 CO₂ Estimation Engine

CO₂ is an **estimation**, not a lab measurement, and the methodology must be open and reproducible. The engine is a **versioned pipeline of pluggable strategies**, and every estimate is stamped with the methodology version that produced it.

Data model separates three things — factors (reference data), methodology version (the ruleset), and per-request context:

```sql
CREATE TABLE co2_methodology (
    id BIGINT PRIMARY KEY,
    version TEXT NOT NULL UNIQUE,        -- e.g. "2026.1"
    description TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE co2_factor (
    id BIGINT PRIMARY KEY,
    methodology_id BIGINT NOT NULL REFERENCES co2_methodology(id),
    category_code TEXT NOT NULL,
    co2e_per_kg NUMERIC(8,3) NOT NULL,   -- base factor
    confidence SMALLINT NOT NULL,        -- 0-100, data quality of this factor
    source_ref TEXT,                     -- citation, for transparency
    UNIQUE (methodology_id, category_code)
);

CREATE TABLE co2_modifier (
    id BIGINT PRIMARY KEY,
    methodology_id BIGINT NOT NULL REFERENCES co2_methodology(id),
    dimension TEXT NOT NULL,   -- 'origin' | 'production' | 'transport' | 'processing'
    key TEXT NOT NULL,
    multiplier NUMERIC(5,3) NOT NULL,
    UNIQUE (methodology_id, dimension, key)
);
```

Strategy interface — the simple v1 model and a future ML model both implement it:

```java
public interface Co2Strategy {
    String methodologyVersion();
    Co2Estimate estimate(Co2Request request);   // pure function, no side effects
}
```

**Two separations that matter:**
- **Product-level** (per food item): `base = factor(category) × mass_kg`, then apply origin/production/transport/processing modifiers. Deterministic and cacheable.
- **Context-level** (FR-010 factors: cooking energy, fridge/freezer size, household size, retail channel, location): applied **once at meal/daily aggregation**, *not* per product — those describe the user's lifestyle, not the food. Applying them per item would double-count.

**Confidence (transparency requirement).** The API never returns a bare number. Each result carries a confidence score (high data completeness → high; category fallback with unknown origin → low) and the methodology version:

```json
GET /api/v1/co2/estimate?productId=...&grams=150&cooking=gas&household=2
{
  "co2eGrams": 410,
  "confidence": 62,
  "confidenceLevel": "medium",
  "methodologyVersion": "2026.1",
  "breakdown": { "base": 480, "transportAdj": 1.1, "contextAdj": 0.78 }
}
```

**Responsibility split for confidence display (important — agreed):**
- **Backend / engine:** computes the confidence number **and** owns the high/medium/low thresholds, returning `confidenceLevel`. This keeps thresholds consistent across iOS/Android/web, tied to the methodology version, and testable. The UI must **not** invent thresholds.
- **On-device app logic:** aggregates per-item confidence into a meal/day confidence (meals are local/offline, so this lives on the device, between backend and UI).
- **UI:** renders the level — label, colour, icon, tooltip, and the "estimate" framing. Presentation only.

Suggested default buckets (backend-owned, tune later): `≥70 = high`, `40–69 = medium`, `<40 = low`.

### 6.2 Sync protocol

Offline-first (FR-110): the device boots and logs without network, then reconciles. **One-directional delta sync** of reference data down to the device, plus a **lazy barcode-miss fetch**. Reference data only flows server→device; the device never uploads meals, so there are no merge conflicts on user data.

Mechanism: a **monotonic global version counter** + **tombstones** for deletes.

```sql
CREATE SEQUENCE catalog_version_seq;

ALTER TABLE food_product ADD COLUMN row_version BIGINT NOT NULL;
CREATE INDEX idx_product_row_version ON food_product (row_version);

CREATE TABLE catalog_tombstone (
    product_id  BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    deleted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tombstone_row_version ON catalog_tombstone (row_version);
```

The device stores its highest seen `row_version` (watermark) and asks for newer:

```
GET /api/v1/sync?since=48210&limit=500
If-None-Match: "48210"
```
```json
{
  "highWatermark": 48710,
  "hasMore": false,
  "changed": [ { "id": 91823, "barcode": "40084...", "rowVersion": 48655, ... } ],
  "deleted": [ 88120, 88004 ]
}
```

- `hasMore` paginates for long-offline devices — one sync never returns the whole table.
- `ETag` / `If-None-Match` makes the common "nothing changed" case a cheap `304`.
- **Bundled core + lazy tail:** the app ships a "core" set (most-logged foods) at install and lazy-fetches the long tail on a barcode miss via `GET /api/v1/foods/barcode/{code}` (Redis-cached; result is written to the device's local store).
- **Version strategy:** a single global sequence is correct and simple. Postgres handles it comfortably well past launch; only consider a sharded/hybrid clock if write contention ever shows up.

---

## 7. Data sources & ingestion

Primary open datasets: **Open Food Facts** and **USDA FoodData Central**, enriched with our own CO₂ data, sustainability scores, and regional adjustments.

**Pull method — dumps, not crawling.** OFF rate-limits and explicitly asks you to use data exports beyond a few hundred products (heavy API traffic returns HTTP 503).
- Use the **Parquet** export (via DuckDB) for processing, or **JSONL** (~7 GB gzipped / 43 GB raw) for the richest data.
- Apply **daily delta exports** to stay current.
- **Caveat:** deltas don't carry deletions → run a **periodic full reload** to purge deleted products.
- The live read API (`GET /api/v2/product/{barcode}`) is reserved for the **barcode-miss path** only, and **requires a custom `User-Agent`** identifying our app (generic ones get banned). Fill out OFF's API usage form.

Ingestion is a scheduled Spring Batch job: *weekly full reload + daily deltas* → normalise → upsert into Postgres → bump `row_version` (feeds Sync).

---

## 8. Contributing back to Open Food Facts

Lets users add unknown products / photos. Aligns with FR-070 and the "users build the database" vision.

**Approach (privacy-correct):** uploads go through a **single dedicated service account** (e.g. `reduceco2now_app`) — OFF endorses this pattern. The user's email / name / app-id is **never** sent to OFF; the bot shows as the contributor; only product data goes out (barcode, name, brand, ingredients, nutrition, photos). Anonymous product creation is **not** allowed by OFF, so the service account is mandatory.

**Non-negotiables — hiding identity ≠ handling consent:**
1. **Explicit public consent.** Photos publish publicly, permanently, under **CC-BY-SA**. The consent prompt must say *"this photo will be made public,"* not just "contribute?" — and the license consent must live in our terms (separate, opt-in, distinct from general privacy onboarding; tied to FR-002 / FR-140).
2. **Strip EXIF / metadata** before upload — even a packaging-only photo can carry GPS, device, and timestamp data that leaks the user's location.
3. **Moderation / quality gate** before submitting — everything flows through one service account, so a junk batch can get the whole bot banned and kill contributions for *all* users. Validate before pushing.
4. **ODbL:** OFF data obliges us to attribute the source and contribute additions back. Consistent with us, but a legal obligation, not optional.

**Consent record** (what we store to prove a user licensed a given photo): user/device ref, photo hash, license (`CC-BY-SA`), policy version, timestamp, app version.

---

## 9. Authentication & account model

Two distinct paths — keep them separate:

- **Local mode:** *no backend account at all.* The app hits public, anonymous, rate-limited read APIs (catalog + sync). Nothing is attributed to a person.
- **Account mode:** Spring Security as an OAuth2 / OIDC resource server (Apple / Google / GitHub) + WebAuthn/passkeys, fronted by **Keycloak** (self-hostable). Used only for encrypted backup, contribution attribution, and moderation roles — never tracking. *Nice alignment:* OFF itself is migrating to Keycloak/OIDC.

*Alternative considered:* Supabase (bundles Postgres + auth + storage, open source, self-hostable). Viable if we ever want batteries-included, but with our own Spring backend, Spring Security + Keycloak is the more natural fit.

---

## 10. Privacy & data handling (selling point — state it explicitly)

The backend stores **food data, CO₂ methodology, contributions, and (optionally) encrypted user blobs it cannot read.** It stores **no meal logs, no weight history, no analytics.**

- GDPR compliant: access, deletion, rectification, portability, consent withdrawal.
- Encrypted at rest; all traffic over HTTPS/TLS.
- No third-party advertising trackers, no behavioural profiling.
- Breach-response process: detect → notify → regulatory reporting where required.

---

## 11. Repository / module layout (Maven)

```
co2diet-backend/
  app/            # Spring Boot bootstrap, wiring
  catalog/        # food products, search, barcode
  co2/            # estimation engine, factors
  sync/           # delta sync
  identity/       # auth, passkeys, roles
  contributions/  # submissions + moderation + OFF push
  ingestion/      # OFF/USDA import (Spring Batch)
  backup/         # e2e-encrypted blob storage
  legal/          # consent + policy versions
  shared/         # domain primitives, errors
```

Each module exposes a small interface and hides its JPA entities. This is what keeps the monolith from rotting and lets `search` / `ingestion` be extracted later without rewrites.

---

## 12. Deployment

- Containerised; everything self-hostable (Postgres, MinIO, Redis, RabbitMQ, Keycloak) so the open-source claim is real and anyone can run their own instance.
- TLS everywhere; encrypted storage.
- FCM is the one external dependency, used **only** for push notifications.

---

## 13. Open items / TBD

- Encrypted blob backup **vs** pure user-cloud export (write to the user's own iCloud/Drive). The product doc leans user-cloud; choosing that lets us drop the `backup/` module entirely.
- OFF image upload: legacy `cgi` endpoint vs the newer v3 images API (build against current OpenAPI).
- Search: Postgres FTS + trigram now; OpenSearch only if needed at scale.
- Confidence bucket thresholds — confirm defaults with the data once the v1 methodology lands.

---

## API surface (quick reference)

```
GET  /api/v1/foods/search?q=&category=
GET  /api/v1/foods/barcode/{code}
GET  /api/v1/foods/{id}
GET  /api/v1/co2/estimate
GET  /api/v1/sync?since={version}
POST /api/v1/contributions
GET  /api/v1/legal/{doc}/current
POST /api/v1/backup    GET /api/v1/backup
```