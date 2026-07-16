"""
audience_report.py — turns raw creator analytics into store decisions.

Reads the cleaned CSVs in /data (transcribed from TikTok Analytics and
Instagram Insights exports) and computes the numbers that drive product,
shipping, and marketing choices for the Olly Threads storefront.

Run it:
    pip install pandas
    python analytics/audience_report.py

Cloud mapping (same logic, bigger scale): the CSVs land in S3, a Glue
crawler catalogs them, these exact aggregations become Athena SQL, and
QuickSight renders the output. This script is the local, zero-cost
version of that pipeline.
"""

from pathlib import Path

import pandas as pd

DATA = Path(__file__).resolve().parent.parent / "data"


def load(name: str) -> pd.DataFrame:
    return pd.read_csv(DATA / name)


def section(title: str) -> None:
    print(f"\n{'=' * 62}\n{title}\n{'=' * 62}")


def main() -> None:
    tt_viewers = load("tiktok_viewer_demographics.csv")
    tt_follows = load("tiktok_follower_demographics.csv")
    tt_posts = load("tiktok_top_posts.csv")
    tt_sum = load("tiktok_summary.csv").set_index("metric")["value"]
    ig_aud = load("instagram_audience.csv")
    ig_sum = load("instagram_summary.csv").set_index("metric")["value"]

    # ── 1. Where casual reach turns into committed audience ──────────
    # A country that holds a bigger share of FOLLOWERS than of VIEWERS
    # converts attention into commitment above average. That is where
    # paying customers come from first.
    section("1. Follow-conversion index by country (followers% / viewers%)")
    v = tt_viewers.query("dimension == 'location'").set_index("segment")["share_pct"]
    f = tt_follows.query("dimension == 'location'").set_index("segment")["share_pct"]
    idx = (f / v).dropna().sort_values(ascending=False).round(2)
    for country, ratio in idx.items():
        print(f"  {country:<16} {ratio:>5.2f}x   "
              f"(viewers {v[country]:.1f}% -> followers {f[country]:.1f}%)")
    print(f"\n  Decision: launch shipping = US only. The US converts viewers")
    print(f"  to followers at {idx['United States']:.2f}x, roughly double every other market,")
    print(f"  and holds {f['United States']:.1f}% of followers vs {v['United States']:.1f}% of raw views.")

    # ── 2. Who actually commits (gender shift viewer -> follower) ────
    section("2. Gender: casual viewers vs committed followers")
    vg = tt_viewers.query("dimension == 'gender'").set_index("segment")["share_pct"]
    fg = tt_follows.query("dimension == 'gender'").set_index("segment")["share_pct"]
    for seg in ("male", "female"):
        print(f"  {seg:<8} viewers {vg[seg]:>4.0f}%  ->  followers {fg[seg]:>4.0f}%  "
              f"({fg[seg] - vg[seg]:+.0f} pts)")
    print("\n  Decision: do not design merch for a default male streetwear")
    print("  buyer. Viewers split evenly; the people who commit skew female")
    print(f"  {fg['female']:.0f}/{fg['male']:.0f}. Size runs and cuts follow the follower base.")

    # ── 3. Age concentration -> price architecture ────────────────────
    section("3. Age concentration (share aged 18-34)")
    tt_1834 = tt_follows.query(
        "dimension == 'age' and segment in ('18-24','25-34')")["share_pct"].sum()
    ig_1834 = ig_aud.query(
        "dimension == 'age' and segment in ('18-24','25-34')")["share_pct"].sum()
    print(f"  TikTok followers 18-34:   {tt_1834:.1f}%")
    print(f"  Instagram audience 18-34: {ig_1834:.1f}%")
    print("\n  Decision: student-budget pricing. Single-item price sits under")
    print("  $40; free shipping starts at $75 to pull the average order")
    print("  toward two items instead of taxing a young audience on one.")

    # ── 4. Which content format earns followers, not just likes ──────
    section("4. Follows earned per 1,000 likes, by post")
    posts = tt_posts.dropna(subset=["likes_365d", "new_followers_365d"]).copy()
    posts["follows_per_1k_likes"] = (
        posts["new_followers_365d"] / posts["likes_365d"] * 1000
    ).round(1)
    posts = posts.sort_values("follows_per_1k_likes", ascending=False)
    for _, r in posts.iterrows():
        print(f"  {r['follows_per_1k_likes']:>5.1f}  {r['format']:<18} "
              f"{r['post'][:38]}")
    best = posts.iloc[0]
    worst = posts.iloc[-1]
    print(f"\n  Decision: process video is the growth engine. '{best['post'][:20]}...'")
    print(f"  earns {best['follows_per_1k_likes']:.1f} follows per 1k likes vs "
          f"{worst['follows_per_1k_likes']:.1f} for the top static")
    print("  sketch, a "
          f"{best['follows_per_1k_likes'] / worst['follows_per_1k_likes']:.1f}x gap. "
          "Drop announcements ride speed paints, not stills.")

    # ── 5. Scale of the funnel feeding the store ─────────────────────
    section("5. Funnel headline numbers (last ~30 days)")
    print(f"  TikTok unique viewers:      {int(tt_sum['total_viewers']):>12,}")
    print(f"  Instagram accounts reached: {int(ig_sum['accounts_reached']):>12,}")
    print(f"  Instagram profile visits:   {int(ig_sum['profile_visits']):>12,}")
    print(f"  TikTok followers (all time):{int(tt_sum['total_followers']):>12,}")
    visits = ig_sum["profile_visits"]
    print(f"\n  Decision: the store must survive spikes. {int(visits):,} profile")
    print("  visits in a month means a single viral post can push four-figure")
    print("  concurrent sessions at the storefront: session carts, atomic")
    print("  stock decrements, and rate limiting are requirements, not polish.")


if __name__ == "__main__":
    main()
