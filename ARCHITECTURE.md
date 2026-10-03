# Global Edge AI Trader v1.4.3 Architecture

## Durable pipeline

Instrument master
→ one shared rate-safe live OHLC snapshot
→ UC continuation model + pre-pressure model
→ full quote/depth confirmation
→ historical/intraday evidence
→ candidate ranking
→ persist last successful section snapshots
→ near-close 3 PM audit
→ PICKS / NO_SIGNAL / NO_DATA daily record
→ next-session outcome evaluation
→ version-scoped adaptive learning

## Reliability invariants

1. **No data is not a bearish signal.** API, feed or scheduling failure is stored as `NO_DATA` and excluded from prediction accuracy.
2. **After-hours is not a live scan.** The UI retains and labels the last successful market-session snapshot.
3. **A daily freeze is auditable.** The app stores the source-scan timestamp, freeze timestamp, date, outcome and candidate payload.
4. **Near-close evidence only.** Automatic 3 PM records require a same-day scan from 14:30 IST or later.
5. **A temporary failure can recover.** A `NO_DATA` record can be upgraded later that day when a valid near-close scan becomes available.
6. **New-listing zero and feed failure are different states.** Feed health is persisted independently from the cached listing count.
7. **Learning generations remain isolated.** v1.3 does not change the v1.2 strategy model versions, so existing statistics continue without contamination.
8. **Rate-limit protection remains centralized.** Both models share market snapshots and the Groww client controls request pacing/backoff.

## Persistence

Android SharedPreferences stores:

- last UC scan summary
- last pre-pressure scan summary
- last market-data success timestamp
- new-listing cache
- listing-feed health
- daily frozen candidate arrays
- daily freeze metadata/audit outcome
- strategy statistics and evaluated outcomes

Retention pruning removes old daily audit/outcome keys according to the configured memory-retention window while keeping the latest scan and listing cache compact.

## Global Lead subsystem (v1.4.0)

`GlobalMarketClient` loads a versioned weekly mapping and foreign daily market bars. `GlobalLeadEngine` performs directional foreign→India scoring only. `GlobalEdgeAITraderRepository.scanGlobalLead()` ranks foreign-only signals first, then spends Groww quote/historical calls only on the leading subset for Indian confirmation and freshness control. The top 10 is persisted so the UI remains usable between worker runs. `ScanWorker` runs Global Lead across global-market hours rather than restricting it to the NSE session; the Indian UC/pre-pressure engines remain NSE-session scoped.

The 3 PM Global Lead decision is intentionally separate from the 3 PM audit of the UC/pre-pressure models. It is a delivery-management decision: retain only candidates that remain strong enough for the next session.

### v1.4.1 directional Global Lead
- `GlobalLeadDirection` separates LONG and SHORT candidates while preserving one weekly counterpart map.
- The engine mirrors gap, benchmark excess, close-location, freshness and post-open continuation scoring by direction.
- The repository ranks each side independently, up to the configured top-candidate count per side.
- The UI exposes LONG and SHORT selectors at the top of Global Lead and never fabricates a direction when no mapped foreign counter currently qualifies.
- Short-side entries remain decision support only and explicitly distinguish normal cash delivery from securities-borrowing routes.


## Adaptive model ranges (v1.4.3)

`AdaptiveRangeEngine` converts each completed quality-score distribution into a per-scan threshold. The UC model uses a 66–88 safety band; the pre-pressure model uses 62–86. The target-count anchor raises the threshold when many strong setups exist and relaxes it only to the safety floor in quiet sessions. This is intentionally not a quota system: if every setup is below the floor, the result remains NO SIGNAL.

For pre-pressure candidates, the success target is stored per candidate at freeze time (+1.5% to +4.5%), and the next-session learning evaluator uses that exact stored target. The buy/sell late-entry cutoff also moves between 6x and 10x according to acceleration and microstructure quality, with an absolute extreme-pressure gate preserved.


## v1.1.3 execution-quality fix
All actionable and watch candidates now use NSE EQ execution gates: minimum ₹20 price, 50,000 shares of current-session volume, ₹25 lakh traded value, two-sided market depth, and maximum 1.5% quoted spread. WATCH candidates are displayed separately and never counted as LIVE.
