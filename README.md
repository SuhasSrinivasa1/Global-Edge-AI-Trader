# Global Edge AI Trader v1.4.4



## v1.4.4 adaptive model ranges

- Adds **Dynamic model ranges** (enabled by default) so the UC and pre-pressure score thresholds are selected from the current scan's score distribution instead of using one fixed number every day.
- Keeps hard safety floors: UC cannot relax below 66; pre-pressure cannot relax below 62. The app can still return **NO SIGNAL** rather than manufacturing weak candidates.
- Makes the pre-pressure success target candidate-specific, from **+1.5% to +4.5%**, based on setup, acceleration, microstructure and total score.
- Makes the already-pressure Buy/Sell cutoff adaptive between **6x and 10x** according to signal stage, while an absolute late-entry gate remains in place.
- Treats UC/prediction pick counts as maximum caps; the actual number shown remains dynamic.
- Bumps the UC and pre-pressure model generations so v1.4.4 learning is isolated from older fixed-threshold statistics.
- Retains the v1.4.2 15-minute background refresh, near-close scan passes, DNS retry/backoff and delayed NO DATA audit behavior.

## v1.3.2 verified rebuild release

This release creates a clean successor build from the v1.3.1 hands-free source tree without changing the strategy generations or automatic order behavior. It bumps the Android package version to **1.3.2 / 132** and shows the running app version directly in Settings so installed builds are easy to verify.

Global Edge AI Trader is a long-lived Android research application for two independent NSE cash-market models:

1. **Next-session upper-circuit continuation**
2. **Pre-pressure price-spike prediction** — looks for compression, acceleration and thinning supply before buy pressure becomes extreme

It also tracks newly listed NSE securities, versioned strategy learning, exchange news and replay/backtest results.

## v1.3 reliability release

This release focuses on making the app trustworthy across years of unattended use:

- Persists the last successful UC and pre-pressure market-session scan across app restarts.
- After market close, shows the last valid market-session result instead of pretending an after-hours zero is a fresh scan.
- Creates an auditable 3 PM daily record with three explicit outcomes: `PICKS`, `NO_SIGNAL`, or `NO_DATA`.
- Keeps `NO_DATA` separate from `NO_SIGNAL` so API/feed failures never pollute model accuracy.
- Can replace an early `NO_DATA` audit record if a valid near-close scan arrives later that same day.
- Stores daily freeze history for both models.
- Persists new-listing results and tracks listing-feed health separately as OK / EMPTY / ERROR / NEVER_LOADED.
- Shows last successful market-data refresh time and listing-feed last-success time.
- Disables fresh live scans outside the NSE market window while preserving the previous session snapshot.
- Retains the v1.2.1 Groww rate-limit protection: shared OHLC snapshot, centralized throttling, retry/backoff and scan de-duplication.
- Preserves existing v1.2 model generations and learning statistics; this is a reliability release, not a strategy reset.

## Trading-day audit behavior

At or after the configured freeze time (default 3:00 PM IST), each model receives one durable daily audit record:

- `PICKS`: at least one candidate was frozen from a valid near-close scan.
- `NO_SIGNAL`: the near-close scan completed successfully but no candidate crossed the model threshold.
- `NO_DATA`: no valid near-close scan was available. This is not treated as a model miss.

Only scan snapshots from the same date and from 2:30 PM IST onward qualify for automatic 3 PM freezing.

## New listing feed

The app stores the latest successful NSE new-listing dataset and its health state. A healthy result with zero listings is shown differently from a failed feed request, so `0` never silently means “network error.”

## Build

- Package: `com.suhas.globaledgeai`
- Version: `1.4.4`
- Version code: `143`
- JDK: 17
- Compile / target SDK: 36
- Minimum SDK: 28

Build:

```bash
gradle --no-daemon clean testDebugUnitTest assembleDebug
```

## Scope

Global Edge AI Trader is a research and decision-support application. It does not guarantee an upper circuit or price spike and does not automatically place trades.


## v1.3.1 hands-free automation

After the user authenticates Groww, Global Edge AI Trader automatically bootstraps the instrument universe, recent listings and due learning outcomes. During NSE market hours WorkManager performs rate-safe pre-pressure discovery and near-close dual-model scans, maintains the 3 PM audit, and runs adaptive learning every 24 hours.

With **TOTP** credentials, the encrypted on-device token/secret can be used to renew Groww's daily access token automatically after the 6:00 AM IST token boundary. With **API-key approval** mode, Groww's daily approval remains a manual dependency; once approved, scanning and learning resume without additional app actions.

The application does not place trades automatically.

## v1.4.0 — Global Lead → India delivery candidates

- Adds a dedicated **Global Lead** window with a dynamic top-10 Indian delivery watchlist.
- Treats foreign markets only as leading sensors; the actionable side remains NSE.
- Uses a curated weekly counterpart map covering exact ADRs plus high-confidence listed parents / groups across the US, Europe and Asia. The map refreshes weekly from a remotely maintained JSON file and falls back to an embedded copy.
- Scores foreign gap, foreign session return, benchmark-relative excess return, abnormal volume, close strength, relationship quality and information freshness.
- Discounts foreign moves that appear to be catch-up to the Indian share's prior session.
- After 09:15 IST, a gap alone is not enough: Indian post-open continuation, order-flow and Global Edge AI Trader pre-pressure confirmation improve the rank; a gap that stays flat is penalized.
- Near 3 PM the list changes to a delivery holding decision: **KEEP NEXT SESSION** only when the setup still survives; otherwise **EXIT / DROP BY 3 PM** or observe.
- Global scanning runs across time zones even when NSE is closed, with a default 60-minute cadence and a tighter near-3-PM decision refresh.
- The list always exposes the best 10 mapped opportunities, but weak rows are explicitly labelled OBSERVE / WAIT rather than being forced recommendations.

The Global Lead module is decision support and does not place orders automatically. Cross-market quote data is obtained from a public market-data endpoint; counterpart relationships are curated from official company / investor-relations sources embedded in the mapping metadata.

## v1.4.1 Global LONG / SHORT split

The Global Lead pane now maintains separate dynamic LONG and SHORT watchlists. LONG candidates require directional foreign strength plus Indian continuation. SHORT candidates invert the same lead/freshness/benchmark/volume logic and require Indian downside continuation. Weak directional conditions are allowed to show OBSERVE/WAIT rather than being presented as actionable. Short-side output is research-only: overnight cash-equity short exposure requires an eligible borrowing route (for example SLBM) rather than an ordinary naked delivery short.


## v1.1.3 execution-quality fix
All actionable and watch candidates now use NSE EQ execution gates: minimum ₹20 price, 50,000 shares of current-session volume, ₹25 lakh traded value, two-sided market depth, and maximum 1.5% quoted spread. WATCH candidates are displayed separately and never counted as LIVE.
