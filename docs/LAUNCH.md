# Launch runbook: from repo to live domain

This is the exact order to take the store from your laptop to a URL anyone can visit. Every step here needs your AWS account, your card, or your Stripe login, which is why it is a runbook and not already done.

Budget first, so nothing surprises you:

| Item | Cost |
|---|---|
| Domain (.com, Route 53) | ~$14/year |
| Route 53 hosted zone | $0.50/month |
| TLS certificate (ACM) | Free |
| EC2 t3.micro (EB) | ~$8/month (free tier covers year one on new accounts) |
| Application Load Balancer | ~$18/month (free tier never covers this) |
| RDS db.t3.micro MySQL | ~$13/month (free tier covers year one) |
| **Realistic total** | **~$20-40/month**, mostly the ALB |

The ALB is the one item worth paying for anyway. Everything configured in this repo (the readiness health check, `forward-headers-strategy`, HTTPS redirect) assumes it. A single-instance environment saves the $18 but makes HTTPS a manual nginx fight. Pay for the ALB.

## 1. Buy the domain

Route 53 → Registered domains → Register. Check if `ollythreads.com` is free; have two backups ready. Buying through Route 53 auto-creates the hosted zone and makes every later step one dropdown instead of copy-pasting nameservers.

## 2. Certificate

AWS Certificate Manager, region **us-east-1** (same as the environment). Request a public cert for `ollythreads.com` AND `www.ollythreads.com`. Choose DNS validation. Because the zone is in Route 53, there is a "Create records" button that validates it in about five minutes. Free forever, renews itself.

## 3. Database

RDS → Create database → MySQL 8, `db.t3.micro`, 20 GB gp3. Two settings matter:

* **Public access: No.** The app reaches it inside the VPC; the internet never should.
* Note the endpoint, master user, and password.

Then load the schema. Prod runs `ddl-auto=validate`, so the tables must exist before the app boots:

```bash
mysqldump --no-data akuma_threads > schema.sql
mysql -h <rds-endpoint> -u admin -p akuma_threads < schema.sql
```

(Or do the Flyway migration from `docs/PRODUCTION-READINESS.md` now and never think about schema sync again. One hour, worth it.)

## 4. Elastic Beanstalk environment

Create application `akuma-threads`, environment `akuma-threads-prod`, platform **Corretto 21**, environment type **Load balanced** (min 1, max 1 instance to start).

Security groups: allow the EB instances' security group into the RDS security group on 3306. Nothing else touches the database.

Environment variables (Configuration → Software). This is where every secret lives; none are in the repo:

```
SPRING_PROFILES_ACTIVE = prod
DB_URL                 = jdbc:mysql://<rds-endpoint>:3306/akuma_threads
DB_USERNAME            = admin
DB_PASSWORD            = <rds password>
APP_BASE_URL           = https://ollythreads.com
GMAIL_USERNAME         = <your gmail>
GMAIL_APP_PASSWORD     = <NEW app password, rotated>
printful.api.key       = <NEW token, rotated>
STRIPE_PUBLISHABLE_KEY = pk_test_... (test keys first, see step 8)
STRIPE_SECRET_KEY      = sk_test_...
STRIPE_WEBHOOK_SECRET  = (set in step 7)
```

Check the exact property names against `application-prod.properties` before saving.

## 5. HTTPS on the load balancer

EB Configuration → Load balancer:

* Add a listener on **443**, protocol HTTPS, select the ACM cert from step 2.
* Keep the process on port 5000; health check path `/actuator/health/readiness` (the bundled `.ebextensions` sets this, verify it took).
* On the ALB itself (EC2 console → Load balancers), edit the port 80 listener to **redirect** to 443. One rule, permanent redirect.

## 6. Point the domain

Route 53 → your hosted zone → Create record:

* `ollythreads.com` → A record → Alias → Elastic Beanstalk environment (or the ALB directly).
* `www.ollythreads.com` → same alias.

DNS propagates in minutes since the zone is fresh. `https://ollythreads.com` now loads the store.

## 7. Stripe webhook (still test mode)

Stripe Dashboard → Developers → Webhooks → Add endpoint:

* URL: `https://ollythreads.com/stripe/webhook`
* Events: `payment_intent.succeeded` and `payment_intent.payment_failed`

Copy the signing secret (`whsec_...`) into the EB env var and restart the environment. Without it the app treats every webhook as untrusted and orders never reach Printful.

## 8. Full dress rehearsal, then flip to live

With test keys still set: place a real order on the real domain with card `4242 4242 4242 4242`. Confirm the confirmation email arrives, the order shows in your account page, the webhook shows delivered in Stripe, and stock decremented. This rehearsal on production infrastructure is the whole point of doing test keys first.

Then go live:

1. Stripe Dashboard → complete account activation (business details, bank account for payouts).
2. Swap the three Stripe env vars to live keys, create a **live-mode** webhook endpoint (test and live webhooks are separate), update `STRIPE_WEBHOOK_SECRET`, restart.
3. Buy the cheapest item yourself with a real card. Confirm Printful received the order. Refund yourself from the Stripe dashboard.

You are live.

## 9. First-week hygiene

* AWS Budgets: create a $50/month alert. Two minutes, prevents every horror story.
* CloudWatch alarm on the ALB target group `UnHealthyHostCount >= 1`.
* Gmail SMTP caps around 500 emails/day. Fine for launch. When order volume makes that scary, move to SES (verify the domain, request production access, ~$0.10 per 1,000 emails) and the only code change is SMTP host and credentials.
* Deploys from now on: GitHub → Actions → "Deploy to AWS Elastic Beanstalk" → Run workflow. Tests gate it automatically.

## Order of operations if something is broken

Site down? Check EB environment health first, then `/actuator/health/readiness` in the EB logs. Payments succeed but no fulfillment? Stripe webhook delivery log, then `STRIPE_WEBHOOK_SECRET`. Emails missing? Gmail app password and the async task logs. Wrong prices or totals? They cannot disagree by construction; check the discount code instead.
