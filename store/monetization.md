# Monetization

The game side is built and tested. **No ad or billing SDK is wired in** — that is the one
remaining step, and it is deliberately isolated so it touches nothing else.

## What is already working

| Surface | Where | Fit |
|---|---|---|
| **Rewarded continue** | Game over, all three games | Strong |
| **Rewarded power charge** | Descent, when a power is empty | Strong |
| **Interstitial** | Every 3rd game over, after a 2-run grace | Good |
| **Remove ads** | Home screen | Strong |

`MonetizationPolicy` owns every rule about *when* a player is asked for anything, and is
pure Kotlin with 13 tests. `AdGateway` / `BillingGateway` are the only seams the rest of
the app knows about.

## Plugging in a real SDK

Everything happens in one function — `MonetizeHost.install()`:

```kotlin
fun install(context: Context, debugBuild: Boolean) {
    ads = YourAdGateway(context)        // implements AdGateway
    billing = YourBillingGateway(context) // implements BillingGateway
    policy.updateEntitlements(billing.entitlements)
}
```

Nothing else in the codebase changes. Two rules an implementation must honour:

1. **Every callback fires exactly once, on the main thread.** A gateway that drops a
   callback strands the game showing "Loading…" forever. That is the single most common
   way ad integrations break a game.
2. **`rewardedReady` must be honest.** The UI hides the continue offer when it is false,
   which is far better than showing a button that does nothing.

`SimulatedAdGateway` in debug builds grants after 1.2s so the flows can be exercised on a
device now. Release builds get `NoOpAdGateway`, so free rewards cannot ship by accident.

## Tuning

All in `AdConfig`:

| Field | Default | Why |
|---|---|---|
| `graceGameOvers` | 2 | A player interrupted before they understand the game uninstalls it |
| `interstitialEveryNGameOvers` | 3 | |
| `minMsBetweenInterstitials` | 90s | Backstop against short runs stacking ads |
| `maxContinuesPerRun` | 1 | Keeps a score meaningful |
| `maxChargeRewardsPerDay` | 6 | Stops charges being farmed |

## Deliberate decisions

- **Removing ads does not remove rewarded video.** Paying buys freedom from
  *interruption*, not from an optional bonus the player went looking for. Taking it away
  punishes them for paying.
- **A continue clears from the top of the pile, never the bottom.** The floor is where
  the large tiles live; a rescue that razes your foundation is not much of a rescue.
- **Revive keeps clearing until a spawn actually fits.** A continue that hands back a
  board which ends the run on the next spawn has taken an ad view for nothing.
- **The interstitial counter keeps counting while ads are removed**, so a refund or
  entitlement change cannot produce a sudden burst from a stalled counter.

## Two bugs the tests caught

Worth recording, because both were invisible on inspection:

- `lastInterstitialMs` started at `Long.MIN_VALUE`. `nowMs - Long.MIN_VALUE` overflows to
  a negative number, which reads as "still inside the cooldown" — **no interstitial would
  ever have fired.** Now nullable.
- The daily reward window used `0L` as its "unset" sentinel, but `0L` is a legitimate
  timestamp, so the window re-anchored on every call and the daily reset never landed.
  Also now nullable.

Winding the device clock backwards re-anchors the daily window but **does not** refund the
allowance — closing the same exploit the power regeneration still has.

## Before release

- [ ] Implement the two gateways against the real SDK
- [ ] Privacy policy URL — required by every ad network
- [ ] Update the Data Safety declaration: it currently says **"No data collected"**, which
      becomes false the moment an ad SDK is added (advertising ID, device data)
- [ ] Rewrite the "no ads, no paywalled continues" paragraph in
      [play-store-listing.md](play-store-listing.md) — it is currently a promise the app
      would be breaking
- [ ] Server-side reward verification, if rewards ever become valuable enough to fake

## The unavoidable caveat

Revenue is roughly `DAU x ARPDAU`, and casual-puzzle ARPDAU is cents. A thousand daily
players in India is roughly ₹100–300/day. **None of this returns anything without
distribution**, and a 2048 variant has close to zero organic discovery. The work above is
worth doing because it is cheap and non-intrusive, not because it is the thing that
decides whether the app makes money.
