# URL Shortener + Analytics Dashboard
### A System Design Walkthrough — Spring Boot + Angular

> **Project 1 of 3** in a progressive system design series (Beginner → Medium → Advanced).
> This document is written the way a senior engineer would leave notes for the next
> person who has to maintain this service: what was built, what broke, how it was
> proven fixed, and what trade-offs were consciously accepted rather than solved.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Phase 1 — Core Vertical Slice](#2-phase-1--core-vertical-slice)
3. [Phase 2 — Concurrency & the Race Condition](#3-phase-2--concurrency--the-race-condition)
4. [Phase 3 — Caching the Hot Path](#4-phase-3--caching-the-hot-path)
5. [Phase 4 — Decoupling Writes with Async](#5-phase-4--decoupling-writes-with-async)
6. [Phase 5 — Continuous Observability](#6-phase-5--continuous-observability)
7. [SLOs & What "Done" Means](#7-slos--what-done-means)
8. [Known Gaps (Intentionally Unsolved)](#8-known-gaps-intentionally-unsolved)
9. [Postmortem Log](#9-postmortem-log)
10. [What This Project Teaches for Next Time](#10-what-this-project-teaches-for-next-time)

---

## 1. Architecture Overview

```
┌─────────────┐       POST /api/urls        ┌──────────────────┐
│   Angular    │ ───────────────────────────▶│   Spring Boot     │
│  (port 4200) │ ◀─────────────────────────── │   (port 8080)     │
└─────────────┘        UrlResponse            └──────────────────┘
                                                       │
                              GET /{code}              │
                          ┌────────────────────────────┼──────────────┐
                          ▼                             ▼              ▼
                  ┌───────────────┐          ┌──────────────┐  ┌─────────────┐
                  │ Caffeine Cache │──miss──▶│  H2 / SQL DB  │  │ Async Thread │
                  │ (urlResolution)│          │ short_urls    │  │ Pool         │
                  └───────────────┘          │ click_events  │  │ (analytics)  │
                                              └──────────────┘  └─────────────┘
                                                                       │
                                                                       ▼
                                                              Prometheus (scrape)
                                                                       │
                                                                       ▼
                                                                  Grafana
```

**Core design decisions, up front:**

| Decision | Choice | Why |
|---|---|---|
| Short code generation | Random base62, DB unique constraint + retry | Avoids leaking volume/order (vs. ID encoding) |
| Cache | Caffeine, in-process, 10k entries, 10min TTL | Redirect is read-heavy; no need for distributed cache at this scale |
| Click logging | `@Async`, bounded thread pool, `CallerRunsPolicy` | Never block the redirect on analytics; degrade gracefully under overload |
| Consistency model | Redirects strongly consistent, click counts eventually consistent | Correctness matters for redirects; a few seconds of undercounted clicks is acceptable |

---

## 2. Phase 1 — Core Vertical Slice

**Goal:** paste a URL → get a short code → redirect works. No caching, no async, no
edge cases yet. Establish the skeleton before optimizing anything.

### Backend

**`pom.xml`** (core dependencies)
```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
</dependencies>
```

**`application.yml`**
```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:h2:mem:urlshortener
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
  h2:
    console:
      enabled: true

app:
  base-url: http://localhost:8080/
```

**`entity/ShortUrl.java`**
```java
@Entity
@Table(name = "short_urls")
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, updatable = false)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false)
    private Instant createdAt;

    protected ShortUrl() {}

    public ShortUrl(String shortCode, String originalUrl) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getShortCode() { return shortCode; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
```

**`entity/ClickEvent.java`**
```java
@Entity
@Table(name = "click_events")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // NOTE: deliberately NO foreign key to ShortUrl.
    // Async writes against FK-constrained tables are a hazard — see Phase 4.
    private String shortCode;

    private Instant clickedAt;
    private String referrer;
    private String userAgent;

    protected ClickEvent() {}

    public ClickEvent(String shortCode, Instant clickedAt, String referrer, String userAgent) {
        this.shortCode = shortCode;
        this.clickedAt = clickedAt;
        this.referrer = referrer;
        this.userAgent = userAgent;
    }

    public String getShortCode() { return shortCode; }
    public Instant getClickedAt() { return clickedAt; }
}
```

**`repository/UrlRepository.java`**
```java
public interface UrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByShortCode(String shortCode);
    boolean existsByShortCode(String shortCode);
}
```

**`repository/ClickEventRepository.java`**
```java
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    long countByShortCode(String shortCode);
    List<ClickEvent> findTop20ByShortCodeOrderByClickedAtDesc(String shortCode);
}
```

**`dto/CreateUrlRequest.java`**
```java
public class CreateUrlRequest {
    @NotBlank
    @URL
    private String originalUrl;

    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }
}
```

**`dto/UrlResponse.java`**
```java
public class UrlResponse {
    private String shortCode;
    private String shortUrl;
    private String originalUrl;
    private Instant createdAt;

    public UrlResponse(ShortUrl entity, String baseUrl) {
        this.shortCode = entity.getShortCode();
        this.shortUrl = baseUrl + entity.getShortCode();
        this.originalUrl = entity.getOriginalUrl();
        this.createdAt = entity.getCreatedAt();
    }

    public String getShortCode() { return shortCode; }
    public String getShortUrl() { return shortUrl; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
```

**`exception/UrlNotFoundException.java`**
```java
public class UrlNotFoundException extends RuntimeException {
    public UrlNotFoundException(String code) {
        super("No URL found for code: " + code);
    }
}
```

**`exception/GlobalExceptionHandler.java`**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UrlNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "Could not generate a unique short code, please retry"));
    }
}
```

**`config/WebConfig.java`**
```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:4200")
            .allowedMethods("GET", "POST", "DELETE");
    }
}
```

### Frontend

**`shared/models/url.model.ts`**
```typescript
export interface UrlResponse {
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  createdAt: string;
}
```

**`core/services/url.service.ts`**
```typescript
@Injectable({ providedIn: 'root' })
export class UrlService {
  private baseUrl = 'http://localhost:8080/api/urls';

  constructor(private http: HttpClient) {}

  create(originalUrl: string): Observable<UrlResponse> {
    return this.http.post<UrlResponse>(this.baseUrl, { originalUrl });
  }

  listAll(): Observable<UrlResponse[]> {
    return this.http.get<UrlResponse[]>(this.baseUrl);
  }
}
```

**`features/shortener/shortener.component.ts`**
```typescript
@Component({
  selector: 'app-shortener',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './shortener.component.html'
})
export class ShortenerComponent {
  form = new FormGroup({
    originalUrl: new FormControl('', [Validators.required])
  });
  result: UrlResponse | null = null;
  error: string | null = null;

  constructor(private urlService: UrlService) {}

  submit() {
    const url = this.form.value.originalUrl!;
    this.error = null;
    this.urlService.create(url).subscribe({
      next: (res) => (this.result = res),
      error: (err) => (this.error = err.error?.error ?? 'Something went wrong')
    });
  }
}
```

**`features/shortener/shortener.component.html`**
```html
<form [formGroup]="form" (ngSubmit)="submit()">
  <input formControlName="originalUrl" placeholder="Paste a long URL" />
  <button type="submit">Shorten</button>
</form>

@if (result) {
  <p>Short URL: <a [href]="result.shortUrl">{{ result.shortUrl }}</a></p>
}
@if (error) {
  <p style="color: red">{{ error }}</p>
}
```

**Checkpoint:** `mvn spring-boot:run` + `ng serve` — paste a URL, get a code, redirect works.
This is the baseline every later phase measures itself against.

---

## 3. Phase 2 — Concurrency & the Race Condition

### The bug

The naive implementation used check-then-act:

```java
// ❌ VULNERABLE TO A RACE CONDITION — DO NOT SHIP
private String generateUniqueCode() {
    String code;
    int attempts = 0;
    do {
        code = randomCode();
        attempts++;
        if (attempts > 5) throw new IllegalStateException("...");
    } while (urlRepository.existsByShortCode(code));   // TOCTOU race
    return code;
}
```

Two threads can both observe `existsByShortCode(code) == false` before either has
saved — classic **time-of-check to time-of-use (TOCTOU)**. With a 7-character
base62 space (62⁷ ≈ 3.5 trillion combinations) this almost never collides in
practice, which is exactly why it's dangerous: **rare bugs still ship, they just
surface at 2am under real traffic instead of in code review.**

### Proving it (forcing the collision)

Temporarily shrink the code space so the race becomes observable in a test:

```java
private static final int CODE_LENGTH = 2; // 62² = 3,844 combos — collides fast
```

Load test with [k6](https://k6.io/):

```javascript
import http from 'k6/http';

export const options = { vus: 20, iterations: 50 };

export default function () {
  http.post('http://localhost:8080/api/urls',
    JSON.stringify({ originalUrl: 'https://example.com' }),
    { headers: { 'Content-Type': 'application/json' } });
}
```

**Observed result:** intermittent `500 Internal Server Error` from an uncaught
`DataIntegrityViolationException` — the database's unique constraint caught what
the application logic missed. A raw `500` instead of a clean `409`/`503` is itself
the tell: the database was the actual safety net, not the code.

### The fix

Stop checking-then-acting. Let the DB constraint be the source of truth, and treat
the violation as an **expected, recoverable outcome** — not an error:

```java
@Service
public class UrlService {

    private static final int MAX_RETRIES = 5;

    public ShortUrl createShortUrl(String originalUrl) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String code = randomCode();
            ShortUrl shortUrl = new ShortUrl(code, originalUrl);
            try {
                return urlRepository.save(shortUrl);
            } catch (DataIntegrityViolationException e) {
                // Another request grabbed this code between generation and save.
                // Expected under concurrency — retry with a fresh code.
                if (attempt == MAX_RETRIES) {
                    throw new IllegalStateException(
                        "Could not generate unique code after " + MAX_RETRIES + " attempts", e);
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }
}
```

### Trade-off considered but not taken: ID-based encoding

```java
// Alternative: base62-encode the DB auto-increment ID.
// Zero collision risk, no retry loop needed — but leaks creation order/volume
// (competitors can estimate traffic by watching codes increment).
public ShortUrl createShortUrl(String originalUrl) {
    ShortUrl saved = urlRepository.save(new ShortUrl(null, originalUrl));
    saved.setShortCode(Base62.encode(saved.getId()));
    return urlRepository.save(saved);
}
```

**Decision:** kept the random + retry approach. The two extra DB round-trips of
ID-encoding weren't worth it for a project this size, and hiding volume is a
reasonable default for a public-facing shortener.

**Verification:** reverted `CODE_LENGTH` to 7, reran the k6 script at 20 VUs / 50
iterations — zero `500`s.

---

## 4. Phase 3 — Caching the Hot Path

### Why

Reads (redirects) vastly outnumber writes (link creation) — the same asymmetry
Twitter hit with home timelines (300k reads/sec vs 12k writes/sec at peak,
per Kleppmann's *Designing Data-Intensive Applications*). Every redirect
hitting the DB is wasted work once a link is popular.

### Implementation

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

```java
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("urlResolution");
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .recordStats());   // required for hit/miss ratio metrics later
        return cacheManager;
    }
}
```

```java
@Cacheable(value = "urlResolution", key = "#shortCode")
public ShortUrl resolve(String shortCode) {
    return urlRepository.findByShortCode(shortCode)
        .orElseThrow(() -> new UrlNotFoundException(shortCode));
}
```

**Deliberately NOT cached:** `existsByShortCode` — caching a write-path
uniqueness check would let a stale "doesn't exist" answer through and defeat
the guarantee established in Phase 2. Only cache what's safe to serve slightly
stale.

### Benchmark: before vs. after

```javascript
// k6 script — repeatedly hit ONE known short code to simulate a popular link
export const options = { vus: 200, duration: '30s' };

export default function () {
  http.get('http://localhost:8080/aZ3kQ1x', { redirects: 0 });
}
```

| | p50 | p95 | p99 |
|---|---|---|---|
| No cache (200 VUs) | *record actual value* | *record actual value* | *record actual value* |
| Cached (200 VUs) | *record actual value* | *record actual value* | *record actual value* |

> **Lesson:** at low concurrency the difference is often invisible — a single
> indexed lookup on a small table is already fast. The gap shows up at
> **p95/p99 under concurrent load**, because uncached requests contend for DB
> connections from a finite pool and start queueing — the "head-of-line
> blocking" effect. Caching pays off exactly where tail latency lives, not
> necessarily in the average case.

### Observability added this phase

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, caches
```

---

## 5. Phase 4 — Decoupling Writes with Async

### Why

Logging a click synchronously inside the redirect request means every redirect
pays for an extra `INSERT` before the user's browser even starts following the
`302`. Analytics should never add latency to the user-facing path.

### Implementation

```java
@SpringBootApplication
@EnableAsync
public class UrlShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
```

**Dedicated, bounded thread pool** — never rely on Spring's default unbounded
`SimpleAsyncTaskExecutor`:

```java
@Configuration
public class AsyncConfig {

    @Bean(name = "analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("analytics-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

**Design choices worth naming explicitly:**

- **Bounded queue (500)** — click events queue up to 500 deep, not infinitely.
  This is backpressure, applied early.
- **`CallerRunsPolicy`** — if the queue fills, the *calling thread* (the
  redirect request) executes the write directly instead of silently dropping
  the event or throwing. Under extreme overload the system gracefully
  degrades back to synchronous behavior rather than losing data. Trade-off:
  bounded resource usage vs. guaranteed no-loss — `CallerRunsPolicy` picks
  correctness over throughput when things get bad.

```java
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private final ClickEventRepository clickEventRepository;

    public AnalyticsService(ClickEventRepository clickEventRepository) {
        this.clickEventRepository = clickEventRepository;
    }

    @Async("analyticsExecutor")
    public void recordClick(String shortCode, String referrer, String userAgent) {
        try {
            clickEventRepository.save(new ClickEvent(shortCode, Instant.now(), referrer, userAgent));
        } catch (Exception e) {
            // Exceptions inside @Async void methods vanish silently by default —
            // must be caught HERE, not assumed to propagate. A missed click count
            // is acceptable; a broken redirect is not.
            log.warn("Failed to record click for {}: {}", shortCode, e.getMessage());
        }
    }
}
```

```java
@GetMapping("/{shortCode}")
public ResponseEntity<Void> redirect(
        @PathVariable String shortCode,
        @RequestHeader(value = "Referer", required = false) String referrer,
        @RequestHeader(value = "User-Agent", required = false) String userAgent) {

    ShortUrl shortUrl = urlService.resolve(shortCode);            // cached, fast
    analyticsService.recordClick(shortCode, referrer, userAgent); // fire-and-forget

    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(shortUrl.getOriginalUrl()))
        .build();
}
```

### Benchmark: sync vs. async click logging

Same k6 script as Phase 3, run against both versions at `vus: 100–200`:

| | p50 | p95 | p99 |
|---|---|---|---|
| Sync click write | *record actual value* | *record actual value* | *record actual value* |
| Async click write | *record actual value* | *record actual value* | *record actual value* |

> **Lesson:** the sync version's tail latency degrades under concurrency
> because each redirect holds its DB connection longer (SELECT + INSERT). The
> async version's redirect path only ever does the cached SELECT — tail
> latency stays flat as load increases.

### Eventual consistency, observed directly

After a load test, `GET /api/urls/{code}/stats` may **undercount** clicks
briefly — events still sitting in the bounded queue. Querying immediately vs.
a few seconds later shows the count catch up. This is the first hands-on
encounter with eventual consistency in this series; it becomes the central
topic of Project 3's Saga pattern.

---

## 6. Phase 5 — Continuous Observability

### Why

Point-in-time k6 runs only tell you the p95 *at the moment you ran them*.
Production needs continuous visibility: catch regressions before users
complain, and answer "is it slow right now?" without manually running a load
test.

### Micrometer + Prometheus

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, caches, prometheus
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      percentiles:
        http.server.requests: 0.5, 0.95, 0.99
      slo:
        http.server.requests: 50ms, 100ms, 200ms, 500ms
```

> `percentiles-histogram: true` matters: without it, Micrometer only tracks a
> client-side approximation that can't be aggregated correctly across
> instances. Per Kleppmann: **averaging percentiles across machines is
> mathematically meaningless — you must aggregate histograms.** With buckets
> enabled, Prometheus computes real percentiles across however many instances
> eventually exist.

### Custom timer around the cache-miss path

```java
@Cacheable(value = "urlResolution", key = "#shortCode")
public ShortUrl resolve(String shortCode) {
    return Timer.builder("url.resolve.db")
        .description("Time to resolve a short URL on a cache miss")
        .register(meterRegistry)
        .record(() -> urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode)));
}
```

This timer sits *inside* the cached method, so it only fires on misses
(`@Cacheable` short-circuits the body on a hit). Comparing `url.resolve.db`
against overall `http.server.requests` shows exactly how much the cache saves,
continuously — not just during a single k6 run.

### Cache hit ratio as a metric

```java
@Bean
public MeterBinder cacheMetrics(CacheManager cacheManager) {
    return registry -> {
        CaffeineCache cache = (CaffeineCache) cacheManager.getCache("urlResolution");
        CaffeineCacheMetrics.monitor(registry, cache.getNativeCache(), "urlResolution");
    };
}
```

### Local Prometheus + Grafana stack

**`docker-compose.yml`**
```yaml
version: '3.8'
services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    depends_on:
      - prometheus
```

**`prometheus.yml`**
```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: 'url-shortener'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

### Dashboard queries

**p95 redirect latency:**
```promql
histogram_quantile(0.95,
  sum(rate(http_server_requests_seconds_bucket{uri="/{shortCode}"}[1m])) by (le)
)
```

**Cache hit ratio:**
```promql
sum(rate(cache_gets_total{cache="urlResolution", result="hit"}[5m]))
/
sum(rate(cache_gets_total{cache="urlResolution"}[5m]))
```

### Alert rule (practice syntax, reusable in Projects 2 & 3)

```yaml
groups:
  - name: url-shortener-alerts
    rules:
      - alert: HighRedirectLatency
        expr: histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri="/{shortCode}"}[5m])) by (le)) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Redirect p95 latency above 100ms for 2+ minutes"
```

---

## 7. SLOs & What "Done" Means

| Metric | Target | Measured how |
|---|---|---|
| Redirect p95 latency | < 100ms | `http_server_requests_seconds_bucket{uri="/{shortCode}"}` |
| Redirect p99 latency | < 300ms | same |
| Cache hit ratio | > 90% under normal load | `cache_gets_total{cache="urlResolution"}` |
| Click count consistency | Converges within 5s of request | manual verification after load test |
| Uniqueness guarantee | Zero collisions under concurrent creation | k6 concurrency test, Phase 2 |

Conditions: single instance, H2 in-memory, local machine, no network latency —
these numbers are a baseline, not a production commitment.

---

## 8. Known Gaps (Intentionally Unsolved)

Naming what you chose *not* to solve, and why, is itself a system design skill.

1. **Negative-lookup caching.** Requests for nonexistent short codes always hit
   the DB (never cached), which means someone could enumerate `/aaaaaaa`,
   `/aaaaaab`, etc. and bypass the cache entirely, pressuring the database.
   A production fix would use a Bloom filter or a short-TTL negative cache.
   Left out here because Spring's `@Cacheable` + `unless` handling of `null`/
   `Optional.empty()` is fiddly, and the fix adds complexity out of proportion
   to this project's scope.

2. **No distributed cache.** Caffeine is in-process — fine for a single
   instance, but a second instance would have its own independent cache with
   no invalidation coordination. Acceptable here; would need Redis if this
   service ever ran on more than one node.

3. **No rate limiting.** Nothing stops a client from creating thousands of
   short URLs per second. Flagged as a stretch goal, not built.

4. **No link expiration (TTL).** `ShortUrl` has no `expiresAt` field wired up
   yet — schema allows for it, logic doesn't enforce it.

---

## 9. Postmortem Log

Short, dated entries — the habit of writing these down at small scale is
practice for doing it at real-incident scale later.

```
[Phase 2] TOCTOU race in generateUniqueCode().
  Verified: k6, 20 VUs, shrunk code space to 62² combos.
  Symptom: intermittent 500s from uncaught DataIntegrityViolationException.
  Fix: removed check-then-act; catch the DB constraint violation and retry
  (max 5 attempts). DB unique constraint is now the actual source of truth.
  Trade-off considered: ID-based base62 encoding (zero collisions, but leaks
  creation volume/order). Not taken — random + retry preferred for this use case.

[Phase 4] Eventual consistency observed in click counts.
  After 100-VU load test, GET /stats undercounted clicks immediately after
  the test, converged to correct count ~3-5s later (bounded async queue
  draining). Expected behavior, not a bug — documented as accepted trade-off.
```

---

## 10. What This Project Teaches for Next Time

This small project deliberately touches the same skeleton every later, bigger
system will need:

- **Correctness under concurrency** — never trust check-then-act across a
  boundary (app ↔ DB); let the DB's constraints be the real source of truth.
- **Measured caching** — "we added a cache" means nothing without a before/
  after percentile comparison proving it helped, and at what concurrency
  level it starts to matter.
- **Decoupled writes** — the user-facing path (redirect) and the
  side-effect path (analytics) have different consistency and latency
  requirements; conflating them costs tail latency.
- **Continuous, percentile-based observability** — a single k6 run tells you
  the past; a dashboard tells you now. Averages hide tail latency; percentiles
  don't.
- **Naming what you didn't solve** — a known-gaps list is more honest and
  more useful than silently hoping nobody asks.

**Next:** Project 2 — a distributed job scheduler with a message queue
(RabbitMQ/Kafka) and horizontally scaled workers, where these same lessons
show up again but across process boundaries instead of within one service.
