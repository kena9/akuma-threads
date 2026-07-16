# Olly Threads: eCommerce project case study

I led a team of six engineers to design and build Olly Threads, a full eCommerce app for my art and clothing brand.

And I do not want to make it sound bigger than it is or smaller than it is. The team built the product together. I drove the project direction, kept the work organized, made the product decisions, and owned the cloud side myself. That meant architecture, deployment planning, AWS cost decisions, launch readiness, and making sure the system could handle real customers instead of just looking good in a demo.

The business problem was not “make a website.” I already had a real audience. TikTok had 201,851 followers and 2.5 million viewers in the latest 30-day window. Instagram reached 1,221,538 accounts and sent 124,733 profile visits in the same general launch window. So the real question was: how do we turn attention into a store people can actually buy from, without losing control to a marketplace or a link-in-bio setup?

That is where I treated the project more like a technical product launch than a class assignment.

## My role

I acted as the technical project manager and cloud architect.

I helped break the work into pieces, kept the team focused on what mattered, and pushed the project toward launch instead of letting it become an endless build. I worked across product decisions, backend behavior, checkout flow, fulfillment, testing, analytics, and infrastructure.

But the cloud deployment path was mine. I made the AWS decisions, chose the production shape, mapped the cost, and built the launch plan around a simple idea: use the boring thing that works, spend money only where it protects the customer, and keep the system easy to operate.

The production design uses Spring Boot, AWS Elastic Beanstalk, an Application Load Balancer, RDS MySQL, Stripe for payments, Printful for fulfillment, Gmail SMTP for transactional email, CloudWatch for monitoring, and GitHub Actions for deployment gates. Phase 2 adds an analytics lane with S3, Glue, Athena, and QuickSight so audience and order data can sit in one place.

## The data work

I did not want to make product decisions off vibes. So I took the real TikTok Analytics and Instagram Insights numbers and turned them into clean CSV data.

Then I used the numbers to make four launch decisions:

1. Launch shipping in the United States first.
2. Design for committed followers, not casual viewers.
3. Keep single-item pricing under $40 and test free shipping at $75.
4. Use process videos as the main drop-announcement content because they convert attention into followers better than static posts.

The US had a 1.83x follow-conversion index on TikTok. That means the US audience did not just watch. They committed at a much stronger rate than most other countries.

I also had to correct my own assumption. I expected the brand audience to be mostly male because of anime and streetwear. The viewer data was nearly split, but follower data was 60% female. That mattered. Followers are closer to buyers than viewers are. So product photos, sizing assumptions, and launch positioning had to follow the committed audience, not the stereotype in my head.

The age data also changed the pricing conversation. On TikTok, 86.9% of followers were 18 to 34. Instagram showed 82.2% in that same range. That is a strong audience, but it is also a student-budget audience. So I treated price sensitivity as real.

And the content data was the most useful surprise. The most-liked post was not the best growth post. A Goku speed paint earned 34.1 follows per 1,000 likes. The biggest static post earned 16.7. That told me process content builds trust better than finished-image content.

## The architecture decision

I chose an architecture that made sense for the stage of the business.

Could we have used Kubernetes? Sure. But that would have added complexity before we had the traffic or team size to justify it. Elastic Beanstalk with an ALB and RDS gave us a clean production path, HTTPS support, health checks, deploy rollback options, and a realistic monthly cost.

The estimated production cost is around $20 to $40 per month to start, mostly because the ALB is worth paying for. I see that as a good cloud architecture decision. It is not about using the flashiest AWS service. It is about matching the system to the customer, the budget, and the risk.

Payments were the part I treated most carefully. Card data never belongs on the app server. Stripe owns that. The app verifies the payment and uses the signed webhook as the source of truth before sending anything to fulfillment. That protects the store from fake confirmations, replayed webhooks, and orders that should not exist.

## Quality and risk

The project has 47 tests focused on the bugs that would cost real money.

Not random tests just to chase a percentage. The important ones.

Two buyers try to buy the last item. A Stripe webhook arrives twice. A payment failure arrives after success. A customer tampers with a payment intent. A webhook signature is forged. Those are the bugs that hurt a real store, so those are the bugs the test suite attacks.

And the deploy pipeline is built around that. If the tests do not pass, the build should not ship.

## Why this matters for teams like AWS, Amazon, and FAANG-level engineering groups

This project shows more than one skill.

It shows I can lead people, not just complete tickets. It shows I can make technical decisions with cost, customer behavior, risk, and launch timing in mind. It shows I can work through ambiguity without pretending everything is certain. And it shows I know when to be careful: payments, inventory, secrets, health checks, and deployment gates.

I am still learning. I would never claim otherwise. But I have already had to think like the person responsible for the product, the cloud bill, the launch, and the customer experience at the same time.

That is the part I would bring to a serious engineering team.
