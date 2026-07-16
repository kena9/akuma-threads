# Data decisions

Every product call in this store traces back to a number. This document is the audit trail: where the data came from, what it says, what I decided, and what would make me reverse each call.

Run the analysis yourself:

```bash
pip install pandas
python analytics/audience_report.py
```

## Where the data comes from

Two sources. My TikTok Analytics (201,851 followers, exported June 15, 2026) and my Instagram Insights (exported June 14, 2026). I transcribed the on-screen values into tidy CSVs under [`/data`](../data/), one row per fact.

What I deliberately left out: the line charts. Reading pixel heights off a graph and calling them data is how you end up defending numbers you invented. Only printed values made it into the CSVs. Where a metric was cut off on screen (likes for two posts, follower counts for one), the cell is empty, and the analysis drops those rows instead of guessing.

Know the limits before trusting any of this. These are platform-reported metrics, a single snapshot in time, and the location tables only show each platform's top five. The visible countries cover about 41% of viewers. The other 59% is a long tail I cannot see.

## Decision 1: launch shipping is US only

The raw reach numbers argue for shipping everywhere. 2.5M viewers in a month, spread across every continent.

But reach is not commitment. Compare each country's share of *viewers* against its share of *followers*:

| Country | Viewers | Followers | Conversion index |
|---|---|---|---|
| United States | 14.1% | 25.8% | **1.83x** |
| Brazil | 5.5% | 5.8% | 1.05x |
| Philippines | 8.8% | 5.7% | 0.65x |
| Mexico | 7.1% | 3.9% | 0.55x |

Americans who see the content follow at nearly twice the base rate. Filipinos and Mexicans watch a lot and commit rarely. Instagram corroborates: the US holds 33.4% of that audience too, four times Brazil.

So the store ships domestic first. International shipping adds customs handling, duty disputes, and 3x support load, and the data says the paying audience is not there yet.

What flips this: Brazil. It already converts above 1.0x and holds the number two spot on both platforms. If Brazilian followers cross 10% or Brazilian visitors start hitting the checkout wall in meaningful numbers, Brazil ships next.

## Decision 2: design for the follower, not the viewer

My assumption going in: anime art plus streetwear equals a young male buyer. The viewer data even supports it. 50% male, 49% female. Dead even.

Then the follower table.

40% male. 60% female.

Same content, same account, and the population that *commits* looks nothing like the population that *scrolls past*. An 11-point swing toward women between casual and committed. Since buying a shirt is the strongest commitment on the ladder, size runs, cuts, and product photography follow the follower distribution, and the default-male-hypebeast playbook goes in the bin.

What flips this: actual sales data. Once the store has a few hundred orders, purchaser demographics replace follower demographics as the source of truth. That is the point of the phase 2 pipeline.

## Decision 3: price under $40, free shipping at $75

86.9% of TikTok followers are 18 to 34. Instagram says 82.2%, and adds that 10.3% are under 18 (who mostly cannot buy at all). This is a student-budget audience with strong intent and weak wallets.

Two pricing consequences. Single items stay under $40 because that is an impulse-approvable amount for this cohort. And free shipping starts at $75, a threshold one hoodie cannot reach but a hoodie plus a tee can. The threshold is doing quiet work: it converts "buy one thing" into "build a two-item order" without a discount code.

The math is honest about the tradeoff. Below $75 the customer pays $8.99 shipping and 8% tax on goods plus shipping. That stings on a single $35 tee. It is supposed to.

What flips this: average order value data. If AOV settles far below $75, the threshold is too ambitious and just reads as a shipping tax. Drop it to $60.

## Decision 4: growth content is process video, full stop

Likes and follows are different currencies, and only one of them compounds. For each top post where both numbers are visible, follows earned per 1,000 likes:

| Post | Format | Likes | New followers | Follows per 1k likes |
|---|---|---|---|---|
| Goku speed paint | process video | 323K | 11,000 | **34.1** |
| Performative art trend | static sketch | 395K | 9,059 | 22.9 |
| Line art post | static sketch | 330K | 7,565 | 22.9 |
| TikTok filters post | static sketch | 561K | 9,350 | 16.7 |

My most-liked post ever converts worst. The speed paint converts twice as well per unit of attention. Watching art get made builds a different relationship than seeing art finished.

So drop announcements ride speed paints. Static sketches keep the feed alive between drops, but the videos carry the release calendar.

What flips this: nothing visible yet. Two independent speed paints (Goku, plus the Deku video at 8,519 follows) both sit at the top of the follower-conversion table. The pattern held across months.

## The pipeline this becomes

Right now this is CSVs and pandas, which is correct for the data volume. The design scales without rewriting the logic:

CSVs and order events land in S3. Glue crawls and catalogs them. The groupbys in `audience_report.py` become Athena SQL over the catalog. QuickSight renders the same five sections as dashboards. And once order data joins audience data in one queryable place, the interesting question becomes answerable: not "which format earns followers" but "which format earns customers."

The local script exists because decisions could not wait for infrastructure. The dashed lane in the [architecture diagram](architecture.svg) exists because the infrastructure should not require rethinking the analysis.
