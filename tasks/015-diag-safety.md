# Task 7: 診断ログと安全装置

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の **Task 7**、仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §7.2 に従う。計画の Global Constraints を守る。既存のファイルは、下で許可したもの以外は変更しない。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/diag/DiagLog.kt`
- `app/src/main/java/com/gt8d/launcher/safety/SafetyGuard.kt`
- `app/src/test/java/com/gt8d/launcher/diag/DiagLogTest.kt`
- `app/src/test/java/com/gt8d/launcher/safety/SafetyGuardTest.kt`

既存の更新が許可されているのは `app/src/main/java/com/gt8d/launcher/LauncherApp.kt` だけ。

## 仕様

`DiagLog`(パッケージ `com.gt8d.launcher.diag`):
```kotlin
object DiagLog {
    fun d(tag: String, msg: String)   // リングバッファに "HH:mm:ss.SSS [tag] msg" を1行追加し、logcat(タグ "GT8D")にも出す
    fun dump(): List<String>          // 古い順。最大 500 行
}
```
- 最大 500 行。超えたら古い行から捨てる。スレッドセーフ(`synchronized`)
- logcat への出力は `runCatching { android.util.Log.d("GT8D", "[$tag] $msg") }` で包む(JVM の単体テストでは `android.util.Log` が例外を投げるため。例外は握りつぶしてよい)
- テスト用に `internal fun clearForTest()` を置いてよい

`SafetyGuard`(パッケージ `com.gt8d.launcher.safety`):
```kotlin
object SafetyGuard {
    fun shouldFallback(crashTimesSinceStart: List<Long>): Boolean
    fun recordStart(context: Context)
    fun recordCrash(context: Context)
    fun clear(context: Context)
    fun isFallbackActive(context: Context): Boolean
}
```
- 判定ルール: `crashTimesSinceStart` は、直近のクラッシュから順に「そのプロセスの起動からクラッシュまでの経過 ms」を、**古い順**に並べたもの。末尾から数えて、**10000ms 以内のクラッシュが 3 回連続**していれば true。途中に 10000ms を超えるもの(=10 秒以上動いてから落ちた)があれば、そこで連続が切れる
- 記録: `context.filesDir/safety.json` に、`startElapsed`(最後の起動時の `SystemClock.elapsedRealtime()`)、`crashes`(上の経過 ms のリスト、最大 10 件)、`fallback`(Boolean) を保存する(kotlinx.serialization を使う)。壊れていれば空として扱う
- `recordStart`: `startElapsed` を更新し、ファイル上の `crashes` で `shouldFallback` が true なら `fallback=true` にする
- `recordCrash`: `elapsedRealtime() - startElapsed` を `crashes` に追加して保存する(同期的に書く。プロセスが落ちる直前のため)
- `clear`: `crashes` を空、`fallback=false`
- `isFallbackActive`: `fallback`
- プロセスが 10 秒以上動いたら連続を切る、は「次のクラッシュの経過 ms が 10000 を超える」ことで自然に表現される(別のタイマーは不要)

`LauncherApp.onCreate` の先頭(`super.onCreate()` の直後)で:
1. `SafetyGuard.recordStart(this)`
2. 既存の `Thread.getDefaultUncaughtExceptionHandler()` を保持し、`Thread.setDefaultUncaughtExceptionHandler` で「`SafetyGuard.recordCrash(this)` と `DiagLog.d("Crash", ...)` のあと、保持したハンドラに渡す」ものを設定する

## 手順(TDD)

1. テストを書く(どちらも Robolectric 不要の純粋な JUnit。`android.util.Log` を直接呼ばない):
   - `SafetyGuardTest.threeQuickCrashes_fallback`: `shouldFallback(listOf(2000, 3000, 9000)) == true`
   - `SafetyGuardTest.slowCrash_resetsStreak`: `shouldFallback(listOf(2000, 15000, 3000)) == false`
   - `SafetyGuardTest.twoQuickCrashes_noFallback`: `shouldFallback(listOf(2000, 3000)) == false`
   - `DiagLogTest.diagLog_keepsLast500`: 501 行書くと `dump().size == 500` で、先頭が 2 行目の内容
   - `DiagLogTest.diagLog_worksWithoutAndroidLog`: JVM で `d()` を呼んでも例外にならない
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗する(コンパイルエラーを含む)ことを確認する
3. 実装する
4. `/home/munde/tools/gradle-run.sh test assembleDebug` で全部通るまで直す(最大3回。通らなければ失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を実装に合わせて弱めない
- 走行状態による分岐、`com.android.launcher3` への操作を入れない
- 最後に、`test assembleDebug` の結果(実行件数と失敗件数)と、作った・変更したファイル名を報告する。確認していないことを「確認した」と書かない
