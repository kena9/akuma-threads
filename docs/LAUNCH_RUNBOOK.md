# Launch runbook, presentation version

This is the non-code version of the launch plan.

The goal is simple: move Olly Threads from a working application to a public production store with HTTPS, payment handling, database persistence, monitoring, and a rollback-aware deploy process.

## Budget expectation

The first production version should cost around $20 to $40 per month.

The main monthly cost is the Application Load Balancer. I would still keep it because it gives the app a cleaner production setup: HTTPS routing, health checks, load balancer behavior, and a path to scale later without redesigning the whole system.

## Launch sequence

1. Buy the domain through Route 53.
2. Create an ACM certificate for the root domain and the www version.
3. Create the RDS MySQL database with public access turned off.
4. Create the Elastic Beanstalk environment on Corretto 21.
5. Place the app behind an Application Load Balancer.
6. Attach the certificate and force HTTP traffic to HTTPS.
7. Point Route 53 records to the environment.
8. Configure Stripe webhooks in test mode first.
9. Run a full test purchase on the real production domain before live keys exist.
10. Switch Stripe to live mode only after the test-mode rehearsal works.
11. Place a real low-cost order, confirm Printful receives it, and refund the test purchase.
12. Turn on budget alerts and CloudWatch health alarms.

## What I would watch first week

- Elastic Beanstalk environment health
- Application readiness endpoint
- RDS connectivity
- Stripe webhook delivery
- Order confirmation emails
- Printful fulfillment handoff
- Failed payment behavior
- CloudWatch logs and alarms
- AWS monthly spend

## Triage order

If the site is down, check Elastic Beanstalk health first.

If payments succeed but orders do not fulfill, check Stripe webhook delivery and the webhook signing secret.

If emails fail, check the SMTP credentials and async mail logs.

If inventory looks wrong, check the checkout and stock-update path before touching fulfillment.

And if spend spikes, check the ALB, RDS, and any accidental scaling behavior before assuming the app itself is the problem.

## Why this matters

A launch runbook keeps the team honest.

It turns “we think it is ready” into a sequence you can actually follow. It also makes the project easier to hand off, review, or operate under pressure.
