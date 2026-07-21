# Bug Report (Reordered: Severity → Similarity)

please go to bugs.md, find the first group of bugs identify them in the code, ensure they exist then plan a fix, after a fix is done remove the ting     
from the bugs.md file and commit the changes to git including a suitable commit message. Do not remove this message from the dugs.md file.  clear?

Autonomous read-only bug-hunting pass over the OpenAthleteMetrics codebase. Findings only — nothing has been fixed yet.

## Low Severity

### Glance widget minor issues

**`glance/MetricGlanceWidget.kt`** — Lines 151-156, 163-171, 173-182, 325-327: `lastUpdatedText` is derived independently of whether the specific metric's value is actually present. The "Updated {time}" caption shows whenever a summary/context row was recently updated, even if this particular field is still null, implying a refreshed value when there is none. Not yet fixed.

**`glance/MetricGlanceWidget.kt`** — Lines 80-95 (`provideGlance`): no error handling wraps any of the suspend/Flow calls. If any throw, `provideGlance` never reaches `provideContent`, silently freezing the widget on its last-rendered state with no error surfaced or retried. Not yet fixed.

### BLE engine minor issues

**`ble/BleEngine.kt`** — Lines 547-552 vs. `dispatchPostStreamParse()` (1277-1378): `triggerSync()` logs and discards leftover `reassemblyBuffers` content before clearing it, but the EOS-based path never inspects or logs `reassemblyBuffers` at all — any mid-assembly partial fragment at end-of-stream is silently dropped with no diagnostic trace. Not yet fixed. Suggested fix: mirror the discard-and-log handling in `dispatchPostStreamParse()`.

**`ble/BleEngine.kt`** — Line 349 (`acknowledgeSyncComplete()`): when transitioning back to `Connected`, the displayed `packetsReceived` resets to `0`, but the internal `packetCount` field is never reset — the next notification continues incrementing from the old count, so the UI-visible packet counter can jump to a large stale-looking number after an acknowledged sync. Not yet fixed. Suggested fix: reset `packetCount = 0` in `acknowledgeSyncComplete()`.

### NavGraph minor issues

**`ui/nav/NavGraph.kt`** — Line 114: `intent.getStringExtra(WidgetDeepLink.EXTRA_DATE)?.let(LocalDate::parse)` isn't wrapped in a try/catch. A stale or malformed date string (e.g. from an old widget instance after a format change) throws `DateTimeParseException` uncaught inside the `LaunchedEffect`, crashing the composition. Not yet fixed. Suggested fix: `runCatching { LocalDate.parse(it) }.getOrNull() ?: LocalDate.now()`.

**`ui/nav/NavGraph.kt`** — Lines 111-113: when `widgetIntent` is non-null but fails to resolve a known `templateId`, the function returns without calling `onWidgetIntentConsumed()`, leaving the caller's intent state dangling — unlike every other exit path in this effect. Not yet fixed.

### Misc UI state races / edge cases

**`ui/questions/QuestionsViewModel.kt`** — Lines 75-84 (`saveResponse`), 86-94 (`clearResponse`): both read `localDate.value` from inside the `viewModelScope.launch { }` body rather than capturing the date at the moment of user interaction. If the displayed date changes between tap and coroutine execution, the response could be attributed to the wrong date. Low-likelihood given single-threaded scheduling. Not yet fixed. Suggested fix: pass/capture the date explicitly from the caller.

**`ui/components/PillSelector.kt`** — Lines 46-49: `continuousIndex.coerceIn(0f, (tabs.lastIndex).toFloat())` throws `IllegalArgumentException` if `tabs` is ever empty (`lastIndex == -1`). Currently unreachable since every call site passes a non-empty list, but latent in this shared, reusable component. Not yet fixed. Suggested fix: guard with `if (tabs.isEmpty()) return@drawBehind`.

**`ui/dailydetail/DailyDetailViewModel.kt`** — Line 113-121 (`onTileReordered`): `current` is built without sorting by `sortOrder`, then indices from the UI's sorted list are applied directly against this unsorted list. Every writer today happens to keep list-position equal to `sortOrder`, so indices stay aligned in practice, but it's an unenforced invariant — any future write path persisting tiles out of order would desync indices and silently move the wrong tile. Not yet fixed. Suggested fix: `.sortedBy { it.sortOrder }` before the index-based mutation.

**`ui/devices/DevicesViewModel.kt`** — Line 137-139: `onDeviceCellTapped()` only allows reconnecting from `Idle`/`Error`, but `onAddDeviceTapped()` additionally allows `GattCacheError`. Tapping the device tile directly while in `GattCacheError` silently does nothing, even though the banner's "Retry" button (calling `onAddDeviceTapped`) works fine in the same state. Not yet fixed. Suggested fix: include `GattCacheError` in the allowed-states check for `onDeviceCellTapped` too.
