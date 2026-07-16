# Production Readiness Report — Olly Threads (akuma-threads)

**Date:** July 5, 2026 · **Scope:** full codebase audit + targeted upgrade of the Spring Boot 3.2.5 / Java 21 / MySQL / Stripe / Printful storefront (~7,100 lines of Java, 30 templates).

## Verdict

This codebase is substantially stronger than a typical capstone project. The payment architecture is genuinely sound: webhook HMAC signature verification, PaymentIntent amount re-verification before fulfillment, atomic conditional stock decrements, IDOR-safe order lookups, a pending-order cleanup scheduler, JSON-LD built through Jackson to prevent script breakout, CSRF everywhere except the signature-verified webhook, and a thorough CSP/HSTS header set. Credit where due — most of what usually blocks launch is already done.

What remained were three critical issues, a handful of high-priority gaps, and two launch tasks that can't be completed from inside this environment. The critical and high items that could be fixed in code have been fixed in this upgrade. Everything requiring your accounts or your machine is written up as a runbook below.

---

## Critical findings

### C1 — Live credentials were shared inside the project zip · ACTION REQUIRED

`src/main/resources/application-local.properties` in the uploaded zip contained a real Gmail app password, a real Printful API token, your Stripe test secret key, and your local MySQL password. I verified against the bundled `.git` directory that this file was **never committed** — the `.gitignore` worked, and a history-wide search for the credential values came back clean. However, the file has now left your machine at least once (inside this zip), so treat the credentials as exposed. Before launch: revoke and regenerate the Gmail app password, regenerate the Printful token, roll the Stripe test keys, and change the local MySQL password. The file in this upgraded tree has been replaced with a placeholder template carrying the same warning. Separately, the git index still tracks `ProjectDeliverable6_ScheduleManagement.docx` (course material) — run `git rm --cached ProjectDeliverable6_ScheduleManagement.docx` to stop shipping it with the repo.

### C2 — Stale cart prices could produce wrong charges · FIXED

The session cart snapshots each item's unit price at add-to-cart time and never refreshes it. The Stripe PaymentIntent amount was computed from those snapshots, while `OrderService.createPendingOrder` writes each `OrderItem.unitPrice` from the **live** database price. If an admin changed a price between add-to-cart and checkout, the customer would be charged one number while the order recorded another — and the recorded order total would disagree with its own line items. The fix, in `CheckoutController.createPaymentIntent`, re-reads every cart variant from the database before any amount is computed, pushes live prices into the cart via a new `SessionCart.reprice()` method, and drops lines whose variant was deleted. Charge amount, order total, and line items now always agree.

### C3 — `SameSite=Strict` session cookie breaks two production flows · FIXED

The JSESSIONID cookie was set to `SameSite=Strict`, which withholds the cookie on *every* top-level navigation arriving from an external origin. Two flows this app depends on break under Strict. First, checkout: `automatic_payment_methods` is enabled on the PaymentIntent, so Stripe can present redirect-based methods (Cash App Pay, certain 3DS bank flows) that return the customer via a top-level redirect from stripe.com — under Strict that return arrives without the session cookie, the customer lands logged out, the session-held `pendingOrderId` becomes unreachable, and the paid order is orphaned until the webhook rescues it. Second, every "VIEW MY ORDER" link in a confirmation email opened from Gmail appeared logged-out for the same reason. Changed to `SameSite=Lax` in `SecurityConfig`, which still blocks cross-site subresource and cross-site POST cookie sending; CSRF tokens continue to cover state-changing requests.

---

## High-priority findings

### H1 — Deploy pipeline shipped untested JARs · FIXED

`deploy.yml` built with `-DskipTests` and declared `needs: []`, so a manual deploy could ship a JAR whose tests had never run. The workflow now runs `./mvnw verify -B` — compile, test, package as one gate — before the Beanstalk deploy step.

### H2 — No forwarded-header handling behind the ALB · FIXED

Elastic Beanstalk's load balancer terminates TLS and forwards plain HTTP with `X-Forwarded-Proto`. Without forwarded-header support, Spring believes every request is insecure HTTP: redirect URLs are generated as `http://`, and the HSTS header your `SecurityConfig` carefully configures is never actually emitted (Spring only writes it for requests it considers secure). Added `server.forward-headers-strategy=native` to the prod profile.

### H3 — No database migration tool · RUNBOOK BELOW

Schema management is `ddl-auto=update` in dev and `validate` in prod, with the README describing a manual export-and-import for first deploy. That works exactly once; the first entity change after launch has no path to production. The runbook below adds Flyway with a baseline, which is a one-hour task on a machine with database access. I deliberately did not wire Flyway into the build from here, because generating the baseline DDL requires running against your actual schema — a hand-derived baseline that's wrong would hard-fail prod startup under `validate`.

### H4 — Effectively zero test coverage · FIXED

The suite was one context-load test. It is now thirty-nine tests across six classes, all pure Mockito/JUnit with no Spring context, so the whole suite runs in well under a second and gates every deploy via H1. Coverage targets the places where bugs cost money or trust: `CartPricingTest` and `SessionCartTest` pin the pricing arithmetic (shipping-threshold boundaries, discount-exceeds-subtotal clamping, HALF_UP rounding at the cent, the new reprice behavior). `OrderServiceTest` proves the two guarantees the payment flow depends on — stock safety (validation failures mutate nothing; the conditional-UPDATE race where a concurrent buyer drains stock between the read and the write throws instead of overselling) and webhook idempotency (a replayed `payment_intent.succeeded` re-writes nothing; a late `payment_failed` arriving after success must not cancel the order or hand inventory back). `StripeWebhookControllerTest` attacks the endpoint's only security boundary by computing real Stripe-format HMAC-SHA256 signatures with the JDK: forged signatures and wrong-secret signatures get 400 with zero side effects, valid signatures get 200, and an unconfigured secret never causes an event to be trusted. `CheckoutControllerPlaceOrderTest` proves the tampered/expired-session path cancels the pending order (releasing reserved stock), clears session state, and never reaches a confirmation page. `DiscountCodeServiceValidateTest` pins coupon gating including the exact-minimum boundary. The remaining test debt, in value order: a `@DataJpaTest` against H2 for the conditional stock UPDATE and the `redeemIfAvailable` query, a `@WebMvcTest` slice asserting the security filter chain's route rules, and one Playwright happy-path purchase against Stripe test mode.

### H5 — HTML injection into transactional emails · FIXED

The order-confirmation and abandoned-cart emails interpolated `customerName` — which is the customer-typed shipping name — raw into HTML markup, and the abandoned-cart email did the same with product names. Now escaped with Spring's `HtmlUtils.htmlEscape`. Low direct severity (renders in the victim's own mail client), but user input in HTML is exactly the kind of thing that becomes exploitable when the email template later grows admin-facing variants.

---

## Medium-priority findings

**M1 — Tailwind via CDN with `'unsafe-inline'` CSP · runbook below.** The templates load `cdn.tailwindcss.com`, which is explicitly not for production (runtime JIT compilation on every page view, a third-party script dependency at render time, and it forces `'unsafe-inline'` + the CDN host into `script-src`). Your own CSP comment already acknowledges this. The runbook covers self-hosting with the standalone Tailwind CLI and the CSP tightening that follows.

**M2 — Unconfigured runtime defaults · FIXED.** Added explicit HikariCP pool sizing (max 10, sized for a t3.micro RDS with headroom for a second instance), graceful shutdown with a 20-second drain so in-flight webhook deliveries finish during deploys, gzip compression for text responses, one-day browser caching for static assets (the site previously sent no cache headers, so every page view re-downloaded all CSS/JS — content-hash versioning is the follow-up once templates are click-through verified), and liveness/readiness health groups. One important correction to note: Spring's *default* readiness group contains only the `readinessState` indicator and does **not** check the database, so `management.endpoint.health.group.readiness.include=readinessState,db` is set explicitly — without it, an instance that lost its RDS connection would keep passing the ALB health check. Point the EB/ALB health check at `/actuator/health/readiness`.

**M3 — Repository junk · FIXED.** Deleted `OrderRepository.java.bak` and a stray empty file literally named `git` (both were git-tracked), and added `*.bak`, `*.swp`, `*~` to `.gitignore`. The tracked `.docx` is covered in C1.

**M4 — Open Session In View is enabled (Spring default).** OSIV holds a database connection for the entire request including template rendering, which caps throughput at the pool size under load. Your repositories already use explicit `JOIN FETCH` queries (`findByIdWithItems` etc.), so you're most of the way to safely setting `spring.jpa.open-in-view=false` — but flipping it can surface `LazyInitializationException` in any template that still lazy-walks a relation, so do it in a branch with a full click-through, not from an audit that can't run the app.

**M5 — Schedulers assume a single instance.** `PendingOrderCleanupService` and the abandoned-cart job use plain `@Scheduled`. Correct at one EB instance; the moment you scale to two, both run the jobs. Fine for now — add ShedLock (or move to a leader-only job) before enabling autoscaling.

**M6 — Cart add validates stock against the request quantity only,** not request-plus-already-in-cart, so a customer can stage more units than exist. Harmless economically — `createPendingOrder`'s atomic decrement is the real gate and fails cleanly — but it surfaces as a confusing checkout error rather than a cart-time message.

---

## Launch runbook (owner actions, in order)

**1. Rotate credentials (C1).** Gmail app password, Printful token, Stripe test keys, local MySQL password. Refill `application-local.properties` from the new template.

**2. Verify the upgrade on your machine.** This audit environment has no route to Maven Central, so the build could not be executed here. Every changed file was parse-verified with javac 21 and uses only APIs already present in the codebase, but you must run the real gate:

```bash
./mvnw verify        # compile + all 20 tests + package
./mvnw spring-boot:run   # click through: add to cart → change a product price as admin → checkout → confirm charge matches new price
```

**3. Adopt Flyway (H3).** Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

Export your current schema as the baseline, then configure:

```bash
mysqldump --no-data --skip-comments akuma_threads \
  > src/main/resources/db/migration/V1__baseline.sql
```

```properties
# application.properties — replaces ddl-auto=update as the schema authority
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
```

Existing databases (local + RDS) get stamped at version 1 on next start; fresh databases replay `V1__baseline.sql`. Every future schema change is a new `V2__*.sql` file that deploys itself — no more manual RDS surgery.

**4. Self-host Tailwind (M1).** Download the standalone CLI (no Node required), generate a static stylesheet from your templates, and swap the CDN script tag:

```bash
curl -sLO https://github.com/tailwindlabs/tailwindcss/releases/latest/download/tailwindcss-linux-x64
chmod +x tailwindcss-linux-x64
./tailwindcss-linux-x64 -i src/main/resources/static/css/main.css \
  -o src/main/resources/static/css/tailwind.css \
  --content "src/main/resources/templates/**/*.html" --minify
```

Replace `<script src="https://cdn.tailwindcss.com">` in `fragments/layout.html` with `<link rel="stylesheet" th:href="@{/css/tailwind.css}">`, then remove `https://cdn.tailwindcss.com` and `'unsafe-inline'` from `script-src` in `SecurityConfig.CSP`. Verify every page renders — dynamically-composed class names (if any) need safelisting.

**5. Production environment.** In the EB console set `STRIPE_WEBHOOK_SECRET` (from the Dashboard webhook endpoint for `https://yourdomain.com/stripe/webhook`, subscribed to `payment_intent.succeeded` and `payment_intent.payment_failed`) — the webhook controller silently no-ops without it, which is correct for dev and catastrophic for prod. Set the ALB health check path to `/actuator/health/readiness`. Confirm `APP_BASE_URL` is the real domain, since it feeds password-reset and confirmation-email links.

**6. Untrack the course doc.** `git rm --cached ProjectDeliverable6_ScheduleManagement.docx && git commit`.

---

## Files changed in this upgrade

Modified: `SecurityConfig.java` (C3), `CheckoutController.java` (C2), `SessionCart.java` (C2), `EmailService.java` (H5), `application.properties` (M2, asset caching, readiness group), `application-prod.properties` (H2, M2), `.github/workflows/deploy.yml` (H1), `.gitignore` (M3), `application-local.properties` (sanitized, C1). Added: `CartPricingTest.java`, `SessionCartTest.java`, `OrderServiceTest.java`, `StripeWebhookControllerTest.java`, `CheckoutControllerPlaceOrderTest.java`, `DiscountCodeServiceValidateTest.java`, this document. Deleted: `OrderRepository.java.bak`, stray `git` file. A unified diff of every change accompanies this report as `upgrade.patch`.

## Worth doing next, deliberately not done blind

Three upgrades were evaluated and held back because each changes runtime behavior in ways that need a running app to verify, and breaking a working checkout from an audit environment would be malpractice. `spring.jpa.open-in-view=false` roughly doubles effective connection-pool throughput but will throw `LazyInitializationException` in any template that still lazy-walks a relation — flip it in a branch and click through every page. `spring.threads.virtual.enabled=true` (Java 21 virtual threads) is a genuine win for this I/O-heavy app (Stripe, Printful, SMTP calls all block platform threads today) and Boot 3.2 supports it natively — enable it together with a load test. And the Spring Boot parent should move from 3.2.5 to the current 3.3/3.4 line: 3.2.x OSS support has ended, which means no CVE patches; the jump is usually mechanical for an app this shape, but it must be compiled and regression-tested, so it belongs on your machine, not in this patch.
