# Task 039: 実機確認(17b)で見つかった問題の修正

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.7・§5.2・§8(ダーク固定)に従う。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が 2026-10-30 に GT8D で確認した事実)
1. **タイトルバーが重なる**: アプリにテーマの指定が無いため、端末の既定テーマの ActionBar(「GT8D Launcher」と表示)が、
   HomeActivity・PanelActivity・BrowserActivity の上に**重なって**表示される(ActionBarOverlayLayout)。
   BrowserActivity では、自前のバー(戻る/タイトル/Chromeで開き直す/閉じる)がその下に隠れて見えない。ホームにも ActionBar が出ている。
2. **右が黒いまま**: リンクから「左 ChatGPT|右 パネル」の分割を E で開くとき、SetRunner の3秒後の再試行で E をもう一度呼んでいる最中に、
   PanelActivity が `onResume` で保留の URL を受け取って BrowserActivity を起動した。BrowserActivity は INITIALIZING のまま作られず、
   パネルのタスクは分割から外れて非表示になり、右半分が黒くなった。分割が落ち着いてから E を呼び直すと、
   BrowserActivity は正しく分割の右(パネルのタスクの上)に表示された。つまり「パネルのタスクに重ねる」方式は成り立ち、
   問題は**分割の遷移の最中に起動したこと**。
3. **パネルの「戻る」**: 分割の右のパネルで「戻る」を押すと PanelActivity が閉じ、分割が崩れてホームになった。
   ユーザーの決定: **パネルでは「戻る」で何もしない**(パネルはホーム画面のような場所なので閉じない)。

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)
新規作成:
- `T/ui/NoActionBarTest.kt`
変更:
- `app/src/main/AndroidManifest.xml`
- `M/ui/panel/PanelActivity.kt`
- `T/ui/panel/PanelActivityTest.kt`(テストを足す。既存のテストは変えない)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.NoActionBarTest --tests com.gt8d.launcher.ui.panel.PanelActivityTest --tests com.gt8d.launcher.ui.panel.PanelScreenTest --tests com.gt8d.launcher.ui.home.HomeActivityTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様

### 1. テーマ(タイトルバーなし・ダーク)
- `AndroidManifest.xml` の `<application>` に `android:theme="@android:style/Theme.Material.NoActionBar"` を付ける。
- `LinkEntryActivity` の `android:theme="@android:style/Theme.Translucent.NoTitleBar"` はそのまま(個別の指定が優先される)。
- ほかの属性・要素は変えない。

### 2. 保留の URL は、分割の成立(SetRunner が開いている最中でない)まで受け取らない
- `PanelActivity.consumePendingUrl()` の最初に、`AppGraph.runner.opening.value != null` なら何もせず return する(URL は預けたまま。PendingUrl の有効期限 15 秒は変えない)。
- `onCreate` で、`lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { AppGraph.runner.opening.collect { if (it == null) consumePendingUrl() } } }` を足す
  (開いている最中が終わった時点で、パネルが RESUMED かつ分割の中なら受け取る。`consumePendingUrl` の中の `isInMultiWindowMode` の確認と `take()` は今のまま)。
- 既存の `onResume`・`onMultiWindowModeChanged` からの呼び出しは残す(開いている最中でなければ、今までどおりその場で受け取る)。
- 分割の外では受け取らない、`take()` が消すので二重に開かない、は今のまま。

### 3. パネルでは「戻る」で何もしない
- `onCreate` の `setContent` より前に、`onBackPressedDispatcher.addCallback(this) { DiagLog.d("Panel", "back ignored") }` を登録する
  (何もしない。PanelActivity を閉じない)。
- 一覧(AppListScreen)を開いているときの Compose の `BackHandler`(一覧を閉じる)は、後から登録されるので優先される。この動きは変えない。

## 手順(TDD)
1. テストを書く:
   - `T/ui/NoActionBarTest.kt`(Robolectric、sdk 35。既存の Robolectric テストの書き方に合わせる):
     - `homeActivity_hasNoActionBar`、`panelActivity_hasNoActionBar`、`browserActivity_hasNoActionBar`:
       それぞれ Activity を作り(BrowserActivity は `EXTRA_URL` に `https://example.com` を入れた Intent)、`activity.actionBar == null` を確かめる。
       Robolectric でテーマが Manifest から反映されない場合は、推測で回避せず、事実を報告して止まる。
   - `T/ui/panel/PanelActivityTest.kt` に足す(既存のテストの準備の書き方に合わせる):
     - `pendingUrl_whileOpening_isNotConsumedUntilOpeningEnds`: SetRunner が開いている最中(opening が非 null)の状態で、
       PanelActivity が分割の中で RESUMED → BrowserActivity は起動されない(次に起動された Activity の記録で確かめる)、URL は預けたまま。
       開いている最中が終わる(opening が null になる)→ BrowserActivity がちょうど1回起動される。
       opening を非 null にする方法は、実際の `AppGraph.runner` を、既存テストが使っている方法(偽の SetRunnerDeps など)で操作する。
       分割の中(`isInMultiWindowMode == true`)の状態を Robolectric で作れない場合は、推測で回避せず、作れない理由を報告して止まる。
     - `pendingUrl_notOpening_consumedOnResume`: 開いている最中でなければ、今までどおり RESUMED でちょうど1回起動される。
     - `back_doesNotFinishPanel`: PanelActivity で `onBackPressedDispatcher.onBackPressed()` → `isFinishing == false`。
     - `back_withAppListOpen_closesListOnly`: 一覧を開いた状態で戻る → 一覧が閉じ、`isFinishing == false`。
2. 対象テストを実行し、修正前に失敗することを確かめる(テーマの3件、保留の1件目、戻るの1件目は失敗するはず。失敗しないものは報告する)。
3. 実装する。
4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- SetRunner の再試行のタイミング、E の呼び方の変更(実機の遷移の性質は Task 20 で調べる)。
- BrowserActivity・LinkEntryActivity・HomeActivity のコードの変更。HomeActivity の「戻る」の扱いの変更。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(テストの実行件数・失敗件数、修正前の失敗の内容、assembleDebug の結果)、作成・変更したファイル、**決めたこと**。
