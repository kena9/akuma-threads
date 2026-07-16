# What this project shows for AWS, Amazon, and FAANG-level teams

This project is useful because it shows how I think when the work is messy.

Not just when the assignment is clean. Not just when someone else already decided the architecture.

I led a six-engineer team through a real eCommerce build, and I personally owned the cloud launch path. That gave me practice in the exact skills serious teams care about: ownership, customer focus, cost awareness, operational discipline, and decision-making under uncertainty.

## Customer focus

The store is built around the way real buyers behave.

Guest checkout matters because Instagram sent 124,733 profile visits. Most of those people are not going to make an account before buying a shirt. So the product should reduce friction, not protect an unnecessary registration flow.

The pricing logic also came from the audience. With 86.9% of TikTok followers between 18 and 34, I treated affordability as part of the customer experience.

## Ownership

I did not just “help with the project.” I carried the cloud side from architecture to launch planning.

That included AWS service selection, production cost estimates, domain and TLS planning, health checks, monitoring, secrets handling, deploy gates, and the runbook for going live.

And because I was also leading the team, I had to keep the engineering work connected to the business goal. That is not always easy. People can build a lot of features that do not move the product forward. My job was to keep the build pointed at launch.

## Dive deep

I can explain the technical decisions, not just name the tools.

I can talk through why Stripe should be the source of truth for payment success. Why signed webhooks matter. Why idempotency matters. Why inventory needs to be protected when two customers race for the last item. Why the ALB is worth the extra monthly cost. Why RDS MySQL made more sense than overbuilding with services we did not need yet.

And on the data side, I can explain why a 1.83x follow-conversion index is more useful than raw reach when deciding where to ship first.

## Frugality

The launch architecture is intentionally cost-aware.

Estimated starting cost: around $20 to $40 per month. The ALB is the main cost, and I kept it because HTTPS, health checks, and cleaner production behavior are worth it. But I avoided services that would make the architecture look impressive while adding little value at this stage.

That is the kind of cloud thinking I respect: spend where it protects reliability, security, or customers. Cut where it only feeds ego.

## Bias for action

The analytics work started locally because the business decisions could not wait for a full data platform.

That was the right move. Clean the CSVs, generate the charts, make the launch decisions. Then design the AWS analytics lane for phase 2 with S3, Glue, Athena, and QuickSight.

I did not wait for the perfect system to start making better decisions.

## High standards

The project has 47 tests aimed at the expensive bugs: payment issues, webhook replay, inventory races, failed payments, and tampered checkout behavior.

That matters because an eCommerce app can look finished while still being unsafe to launch. The real standard is not “does the page load?” The real standard is “can a customer pay, can the order fulfill, and can the system avoid doing something stupid with money?”

## What I would bring to the team

I bring the mix of technical build work and product judgment.

I can lead a small team. I can communicate with people who do not think in code. I can look at data and change direction when the numbers prove me wrong. I can make AWS decisions with budget and operations in mind. And I can own a launch path instead of waiting for someone else to tell me every next step.

That is why this project belongs on my resume. It is not just an app. It is proof that I can take a real idea, organize people around it, make the technical calls, and push it toward production.
