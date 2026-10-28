# Task 11: 前面化の検知と暖かい起動の記録

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 11**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.3(暖かい／冷えた起動の見分け方)・§5.5 に従う。計画の Global Constraints を守る。

## 作成・変更するファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- 新規: `app/src/main/java/com/gt8d/launcher/foreground/ForegroundWatcher.kt`、`UsageStatsForegroundEvents.kt`
- 新規: `app/src/test/java/com/gt8d/launcher/foreground/ForegroundWatcherTest.kt`
- 変更: `app/src/main/AndroidManifest.xml`(`<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />` を足す。`xmlns:tools` が無ければ `<manifest>` に足す)

## 仕様(パッケージ `com.gt8d.launcher.foreground`)

`ForegroundWatcher.kt`(純粋な Kotlin):
```kotlin
data class ResumeEvent(val pkg: String, val timeMs: Long)
interface ForegroundEvents {
    fun available(): Boolean
    fun resumedSince(sinceMs: Long): List<ResumeEvent>
}
class ForegroundWatcher(private val events: ForegroundEvents) {
    fun available(): Boolean = events.available()
    fun bothResumed(left: String, right: String, sinceMs: Long): Boolean    // 使えなければ false。since 以降(>=)に left と right の両方の ResumeEvent があれば true
    fun lastForegroundSince(sinceMs: Long): String?                         // since 以降の最後のイベントの pkg。無ければ・使えなければ null
}
class WarmTracker(private val ownPkg: String, private val ownTaskExists: () -> Boolean) {
    fun markForeground(pkg: String)
    fun isWarm(pkg: String): Boolean        // ownPkg は ownTaskExists() の結果。それ以外は markForeground されたことがあるか
    fun expectedDelayMs(left: String, right: String): Long   // 両方 isWarm なら 800、それ以外は 3500
    companion object { const val WARM_MS = 800L; const val COLD_MS = 3500L }
}
```
- `WarmTracker` はスレッドセーフ(`synchronized` か `ConcurrentHashMap.newKeySet()`)

`UsageStatsForegroundEvents.kt`(Android 実装。JVM テスト不要):
```kotlin
class UsageStatsForegroundEvents(private val context: Context) : ForegroundEvents
```
- `available()`: `AppOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED`
- `resumedSince(sinceMs)`: `sinceMs` は `SystemClock.elapsedRealtime()` 基準で渡される。`UsageStatsManager.queryEvents(begin, end)` は壁時計(`System.currentTimeMillis()`)基準なので、`begin = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sinceMs) - 1000`、`end = System.currentTimeMillis() + 1000` で問い合わせ、`UsageEvents.Event.ACTIVITY_RESUMED` だけを、`timeMs` を elapsedRealtime 基準に換算して(`event.timeStamp - (System.currentTimeMillis() - SystemClock.elapsedRealtime())`)返す。`timeMs < sinceMs` のものは捨てる。例外は空リスト

## 手順(TDD)

1. テストを書く(純粋な JUnit。テスト内に `FakeForegroundEvents(available, events)` を置く):
   - `bothResumed_trueOnlyWhenBothAfterSince`: since=1000。(a@1100, b@1200) → true。(a@1100) だけ → false。(a@900, b@1200) → false
   - `unavailable_bothResumedFalse_andLastNull`
   - `lastForegroundSince_returnsLatest`: (a@1100, b@1300, c@1200) → "b"
   - `warm_bothMarked_800`、`cold_oneUnmarked_3500`
   - `ownPkg_warmWhenOwnTaskExists`: ownTaskExists=true → `expectedDelayMs("x", own)` は x を mark 済みなら 800
   - `ownPkg_coldWhenNoTask_3500`: ownTaskExists=false → 3500(mark していても own は task の有無で決まる)
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を実装に合わせて弱めない
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、作成・変更したファイル名を報告する
