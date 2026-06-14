# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mini-SSP is a simplified Supply-Side Platform (SSP) for programmatic advertising. It receives ad requests from media (apps/websites), concurrently auctions to multiple DSPs (Demand-Side Platforms), selects the highest bidder, and returns the winning ad.

## Commands

```bash
# Build
./mvnw clean package

# Run
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=MiniSspApplicationTests
```

## Architecture

**Stack:** Spring Boot 4.1 / Java 17, MySQL 8, Redis, MyBatis/MyBatis-Plus, WebClient (async DSP calls), Lombok

**Planned package structure** under `src/main/java/com/example/ssp/`:
- `config/` — ThreadPoolConfig, RedisConfig, WebClientConfig
- `controller/` — BidController, TrackController, SlotAdminController, DspAdminController
- `service/` — BidService (core auction orchestration), DspBidClient, SlotService, DspService, TrackService
- `model/entity/` — AdSlot, DspConfig, SlotDspRel, BidLog, EventLog
- `model/dto/` — BidRequest/Response, DspBidRequest/Response
- `model/vo/` — ApiResponse (unified response wrapper)
- `model/enums/` — AdSlotType, BidStatus, EventType
- `mapper/` — MyBatis mappers for each entity
- `cache/` — SlotCacheService, DspCacheService, RateLimiter
- `aspect/` — LogAspect (request timing)
- `exception/` — BizException, GlobalExceptionHandler

## Core Bid Flow

`BidController` → `BidService.processBid()`:
1. Fetch ad slot from Redis cache (fallback to DB)
2. Fetch associated DSPs for the slot from Redis cache
3. Fan out concurrent bids via `CompletableFuture.supplyAsync` per DSP
4. Per DSP: check Redis rate limiter → call `DspBidClient` via WebClient with per-DSP timeout
5. `CompletableFuture.allOf()` to collect all responses
6. Filter valid bids (price > 0, price >= floor_price), pick max
7. Async write `bid_log` records to DB
8. Cache winning result in Redis (`ssp:bid_result:{requestId}`, TTL 5 min)
9. Return winning ad or "no fill"

## Redis Key Conventions

| Key Pattern | TTL | Purpose |
|---|---|---|
| `ssp:slot:{slotId}` | 10 min | Ad slot config |
| `ssp:slot:all` | 10 min | All enabled slots |
| `ssp:dsp:{dspId}` | 10 min | DSP config |
| `ssp:slot_dsps:{slotId}` | 10 min | DSPs associated with a slot |
| `ssp:rate:{dspId}:{yyyyMMddHHmmss}` | 2 sec | Per-DSP QPS rate limiter (INCR) |
| `ssp:bid_result:{requestId}` | 5 min | Winning bid result for tracking callbacks |

## Key API Endpoints

- `POST /api/v1/bid` — Core auction endpoint
- `GET /api/v1/track/impression?rid={requestId}` — Impression tracking (204)
- `GET /api/v1/track/click?rid={requestId}` — Click tracking + redirect (302)
- `GET|POST|PUT|DELETE /api/v1/admin/slots` — Ad slot CRUD
- `GET|POST|PUT|DELETE /api/v1/admin/dsps` — DSP CRUD
- `GET /api/v1/admin/logs` — Bid log query

All responses use unified format: `{ "code": 0, "message": "success", "data": {} }`. Code `1` = no fill.

## Database

Database name: `mini_ssp`. Tables: `ad_slot`, `dsp_config`, `slot_dsp_rel`, `bid_log`, `event_log`. Full DDL is in `dev.md` section 5.6.

MyBatis XML mappers go in `src/main/resources/mapper/`.

## Configuration

`application.properties` currently only has `spring.application.name=mini-ssp`. The full `application.yml` config (from `dev.md` section 10) should configure datasource, Redis, MyBatis-Plus, and SSP-specific settings (`ssp.bid.global-timeout-ms=200`, thread pool, cache TTLs).

## DSP Integration

Two modes documented in `dev.md`:
- **Mode A (default for early dev):** In-process mock via `MockDspHandler` — no real HTTP calls
- **Mode B (recommended):** Separate Spring Boot mock DSP services on ports 8081-8083, called via WebClient
