# Bug Report (Reordered: Severity → Similarity)

please go to bugs.md, find the first group of bugs identify them in the code, ensure they exist then plan a fix, after a fix is done remove the ting     
from the bugs.md file and commit the changes to git including a suitable commit message. Do not remove this message from the dugs.md file.  clear?

Autonomous read-only bug-hunting pass over the OpenAthleteMetrics codebase. Findings only — nothing has been fixed yet.

## Low Severity

### Misc UI state races / edge cases

**`ui/questions/QuestionsViewModel.kt`** — Lines 75-84 (`saveResponse`), 86-94 (`clearResponse`): both read `localDate.value` from inside the `viewModelScope.launch { }` body rather than capturing the date at the moment of user interaction. If the displayed date changes between tap and coroutine execution, the response could be attributed to the wrong date. Low-likelihood given single-threaded scheduling. Not yet fixed. Suggested fix: pass/capture the date explicitly from the caller.

**`ui/components/PillSelector.kt`** — Lines 46-49: `continuousIndex.coerceIn(0f, (tabs.lastIndex).toFloat())` throws `IllegalArgumentException` if `tabs` is ever empty (`lastIndex == -1`). Currently unreachable since every call site passes a non-empty list, but latent in this shared, reusable component. Not yet fixed. Suggested fix: guard with `if (tabs.isEmpty()) return@drawBehind`.

**`ui/dailydetail/DailyDetailViewModel.kt`** — Line 113-121 (`onTileReordered`): `current` is built without sorting by `sortOrder`, then indices from the UI's sorted list are applied directly against this unsorted list. Every writer today happens to keep list-position equal to `sortOrder`, so indices stay aligned in practice, but it's an unenforced invariant — any future write path persisting tiles out of order would desync indices and silently move the wrong tile. Not yet fixed. Suggested fix: `.sortedBy { it.sortOrder }` before the index-based mutation.

**`ui/devices/DevicesViewModel.kt`** — Line 137-139: `onDeviceCellTapped()` only allows reconnecting from `Idle`/`Error`, but `onAddDeviceTapped()` additionally allows `GattCacheError`. Tapping the device tile directly while in `GattCacheError` silently does nothing, even though the banner's "Retry" button (calling `onAddDeviceTapped`) works fine in the same state. Not yet fixed. Suggested fix: include `GattCacheError` in the allowed-states check for `onDeviceCellTapped` too.
