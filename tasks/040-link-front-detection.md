# Task 040: リンクの前面判定と、冷えた起動での振り分けの修正

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.7(前面の判定方法 ①〜⑤)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が 2026-10-30 に GT8D で確認した事実)
1. **前面の判定の①が効かない**: リンクの受け口 `LinkEntryActivity` が開くと、その `onCreate` より前に、下にある HomeActivity・PanelActivity が
   `onPause` され、`AppGraph.homeResumed`・`AppGraph.panelInSplit` が false になる。そのため、ホームを表示中のリンクが「ホーム」と判定されず、
   送り元のアプリを前面とみなして「送り元|ブラウザ」の分割を開こうとする(例: ホームで Gmail の通知のリンク → Gmail と分割)。
   「アプリ|パネル」の分割中のリンクも「アプリ|パネル」と判定されず、E で分割を開き直していた(見た目は同じだが遅い)。
   実機のログ: `front=Fullscreen(pkg=com.android.shell)`(ホーム表示中に adb から送ったリンク)。
2. **冷えた起動**: アプリのプロセスが無い状態でリンクが届くと、設定の読み込み前なので Task 037a の仕様で `openForLink` が断られ、
   常に全画面ブラウザになる。

## 作成・変更するファイル(これで全部)
新規作成:
- `M/link/RecentFront.kt`
- `T/link/RecentFrontTest.kt`
- `T/link/LinkEntryActivityTest.kt`
変更:
- `M/AppGraph.kt`(フィールドを2つ足すだけ。ほかは変えない)
- `M/ui/home/HomeActivity.kt`(`onResume`・`onPause` に1行ずつ足すだけ)
- `M/ui/panel/PanelActivity.kt`(`onResume`・`onMultiWindowModeChanged`・`onPause` に足すだけ)
- `M/link/LinkEntryActivity.kt`
- `T/ui/home/HomeActivityTest.kt`、`T/ui/panel/PanelActivityTest.kt`(テストを足す。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.link.RecentFrontTest --tests com.gt8d.launcher.link.LinkEntryActivityTest --tests com.gt8d.launcher.link.LinkRouterTest --tests com.gt8d.launcher.ui.home.HomeActivityTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### RecentFront.kt(パッケージ `com.gt8d.launcher.link`。純粋な Kotlin)
```kotlin
object RecentFront {
    const val RECENT_MS = 1_000L
    // 再開中、または一時停止してから RECENT_MS 以内(0 ≦ now - pausedAt ≦ RECENT_MS)なら前面とみなす
    fun isFront(resumed: Boolean, pausedAtMs: Long?, nowMs: Long): Boolean
}
```
理由(コメントに1行で書く): リンクの受け口が開くとき、下の画面はその直前に一時停止するため。

### AppGraph.kt
- `@Volatile var homePausedAtMs: Long? = null`、`@Volatile var panelSplitPausedAtMs: Long? = null` を足す。既存の `homeResumed`・`panelInSplit` はそのまま。

### HomeActivity.kt
- `onResume` で `AppGraph.homeResumed = true` にする所で、`AppGraph.homePausedAtMs = null` も行う。
- `onPause` で `AppGraph.homeResumed = false` にする**前に**、`AppGraph.homePausedAtMs = SystemClock.elapsedRealtime()` を行う。

### PanelActivity.kt
- `onResume`: `AppGraph.panelSplitPausedAtMs = null`。
- `onMultiWindowModeChanged` で分割の外になったとき(`isInMultiWindowMode == false`): `AppGraph.panelSplitPausedAtMs = null`(分割を出たので、直前まで分割の中だった扱いにしない)。
- `onPause`: `AppGraph.panelInSplit = false` にする**前に**、`AppGraph.panelInSplit` が true なら `AppGraph.panelSplitPausedAtMs = SystemClock.elapsedRealtime()`。

### LinkEntryActivity.kt
1. `onCreate` の最初に、前面の手がかり(`FrontClues`)を**今の時刻で**集める。`homeResumed` には
   `RecentFront.isFront(AppGraph.homeResumed, AppGraph.homePausedAtMs, ElapsedClock.nowMs())`、
   `panelInSplit` には `RecentFront.isFront(AppGraph.panelInSplit, AppGraph.panelSplitPausedAtMs, ElapsedClock.nowMs())` を使う。
   ほかの手がかり(送り元、lastPair、otherForegroundAfterLastPair)は今のまま。
2. 設定を待つ: 設定の Flow は `AppGraph.configForHome`(テストで差し替えられる。製品では `latestConfig`)を使う。
   値が null でなければすぐ 3 へ。null なら `lifecycleScope.launch` で `withTimeoutOrNull(CONFIG_WAIT_MS) { flow.filterNotNull().first() }` を待ってから 3 へ。
   `CONFIG_WAIT_MS = 1_500L`(companion object に置く)。待つ間、画面は出さない(テーマは今の透明のまま)。
3. 振り分けと起動: 今の `route` 以降の処理(try/catch/finally と `finish()`)をそのまま使う。1 で集めた手がかりを使う(待った後に集め直さない)。
   設定が待っても来なかったら、今までどおり `LinkConfig()` で振り分ける(`openForLink` は断られ、全画面ブラウザになる)。
4. `finish()` は、すぐ振り分けた場合も、待った場合も、必ず1回だけ呼ぶ。待っている間に Activity が破棄されたら、何もしない(リンクは捨てる。DiagLog に記録)。

## 手順(TDD)
1. テストを書く:
   - `T/link/RecentFrontTest.kt`(純粋な JUnit):
     `resumed_isFront`、`pausedWithinRecent_isFront`(now-paused=1000)、`pausedTooLongAgo_isNotFront`(1001)、`neverPaused_notResumed_isNotFront`、`pausedInFuture_isNotFront`(now < pausedAt)
   - `T/ui/home/HomeActivityTest.kt` に `pause_recordsPausedAt_resumeClears`。
   - `T/ui/panel/PanelActivityTest.kt` に `pauseInSplit_recordsSplitPausedAt`、`pauseOutsideSplit_doesNotRecord`、`leaveSplit_clearsSplitPausedAt`
     (分割の状態は `org.robolectric.Shadows.shadowOf(activity).setInMultiWindowMode(true)` で作る)。
   - `T/link/LinkEntryActivityTest.kt`(Robolectric、sdk 35):
     - `homeJustPaused_routesAsHome`: `AppGraph.homeResumed=false`、`homePausedAtMs` = 今 − 500、送り元を `android-app://com.example.mail`(Intent の `Intent.EXTRA_REFERRER_NAME`)にして
       `https://example.com` を開く → 次に起動された Activity が `FullscreenBrowserActivity`(仕様 §4.7: 前面がホームなら全画面)。
     - `homePausedLongAgo_routesBySender`: 同じで `homePausedAtMs` = 今 − 5000 → 全画面ブラウザ**ではない**(送り元を前面とみなす経路に進む)ことを確かめる。
       分割の経路は `AppGraph.runner` に触れるので、ここでは「次に起動された Activity が FullscreenBrowserActivity でない、または openForLink が呼ばれた」ことが確かめられる形でよい。確かめ方が作れない場合は、推測で回避せず報告して止まる。
     - `coldStart_waitsForConfig_thenRoutes`: 設定の Flow を null にして `https://accounts.google.com/` を開く → main looper を進めても、まだ何も起動されず `isFinishing == false`。
       設定を流す → Chrome への VIEW が起動され、`isFinishing == true`。
     - `coldStart_timeout_routesWithDefaults`: 設定の Flow を null のまま、1500ms 進める → Chrome への VIEW が起動され、`isFinishing == true`(既定の LinkConfig でも Google のログインは Chrome)。
     - 各テストで AppGraph のフィールドとテスト用の Flow を元に戻す(既存の HomeActivityTest の後始末の書き方に合わせる)。
2. 対象テストを実行し、修正前に失敗することを確かめる。
3. 実装する。
4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- LinkRouter の判定の規則、SetRunner、BrowserActivity の変更。RECENT_MS・CONFIG_WAIT_MS 以外の値の変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
