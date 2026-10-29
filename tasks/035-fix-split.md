# Task 035: 修正: 分割の判定と失敗の数え方

最初に `tasks/RULES.md` を読み、従うこと。

仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.3・§4.8・§5.5・§7.1 に従う。
対象は `docs/reviews/2026-10-29-codex-review.md` の A1・A2・A4・A8/C6・E7。
A8 は、起動結果を Boolean で確認できる直接起動とパネルからの起動を対象にする。分割要求の Started だけを根拠にした記録は対象外。

Task 17b (`tasks/032-link-browser.md`) がコミットされた状態から開始する。
4本の修正 Task を別の git worktree で同時実行するため、下記以外のファイルは変更しない。
git のコミット・リセット・ブランチ・worktree 操作はしない。

パス接頭辞:
`M = app/src/main/java/com/gt8d/launcher`
`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `T/foreground/UsageStatsForegroundEventsTest.kt`

変更:
- `M/foreground/ForegroundWatcher.kt`
- `M/foreground/UsageStatsForegroundEvents.kt`
- `M/sets/SetRunner.kt`
- `T/foreground/ForegroundWatcherTest.kt`
- `T/sets/SetRunnerTest.kt`
- `M/ui/panel/PanelActivity.kt`
  - `onLaunchApp` の既存 startApp 呼び出しを、後述の recordAppLaunch 呼び出しの引数にする1か所の変更だけを許す。
  - 17b の URL 消費・分割状態通知・ライフサイクル処理は変更しない。

`WarmTracker` と `ForegroundEvents` は `ForegroundWatcher.kt` 内にある。別ファイルへ移動しない。
`M/split/` の既存実装は今回変更しない。

## 使ってよいコマンド

- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.sets.SetRunnerTest --tests com.gt8d.launcher.foreground.ForegroundWatcherTest --tests com.gt8d.launcher.foreground.UsageStatsForegroundEventsTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

全テスト `test` は実行しない。
Gradle はバックグラウンドに回さず、終了まで待つ。

読み取り・調査用コマンドは RULES の範囲で使ってよい。
ファイルの作成・変更は編集機能を使う。

## 仕様

### 1. 共通: 指摘を先に再現する

指摘ごとに、後述の名前のテストを先に書き、修正前の製品コードで失敗することを確認する。
新設 API を使うテストは、API 未実装によるコンパイル失敗を最初の Red としてよい。ただし、実装後は具体的な状態・呼び出し回数・時刻を検証する。

既存 API で再現できるテストを、新設 API 未定義によるコンパイル失敗だけで代用しない。

修正前から対象の期待値がすべて通る場合、その再現条件に対応する修正は行わない。
「修正前から通ったテスト名・再現条件・指摘を直さなかった理由」を報告し、次の指摘へ進む。
コンパイル環境の不備や別テストの失敗は、その指摘の再現とは扱わない。

### 2. A1: 取得失敗と正常な空一覧を区別する

#### ForegroundEvents の契約

既存のシグネチャは維持する。

- `available()` は使用状況アクセスの許可状態を返す。
- `resumedSince(sinceMs)` は、正常に取得できた場合は空を含む一覧を返す。
- 取得できなかった場合は `ForegroundEventsUnavailableException` を投げる。

`ForegroundWatcher.kt` に次を追加する。

```kotlin
class ForegroundEventsUnavailableException(
    cause: Throwable? = null,
) : RuntimeException(cause)
```

サービス不在・queryEvents の null・問い合わせまたは列挙中の例外は取得失敗とする。
正常に取得できた空一覧は取得失敗にしない。

#### UsageStatsForegroundEvents

既存の Context だけの呼び出しを維持したまま、テスト用に次の第2引数を追加する。

```kotlin
private val queryResumed: ((Long) -> List<ResumeEvent>)? = null
```

`resumedSince` は、queryResumed が非 null ならそれを呼ぶ。
null なら既存の Android API による取得を行う。
両方に同じ取得失敗処理を適用する。

- UsageStatsManager 不在と queryEvents の null は
  `ForegroundEventsUnavailableException()`。
- その他の取得例外は
  `ForegroundEventsUnavailableException(cause)`。
- 既に同例外なら、そのまま投げる。
- 部分的な一覧を成功として返さない。
- 既存の時刻変換と ACTIVITY_RESUMED の抽出条件は変えない。

#### ForegroundWatcher

`private var lastQuerySucceeded: Boolean = true` を追加する。

`available()` は `events.available() && lastQuerySucceeded` を返す。

3つの問い合わせメソッド
`foregroundPkgsSince`・`bothResumed`・`lastForegroundSince` は共通の
`private fun readResumed(sinceMs: Long): List<ResumeEvent>?`
を使う。

readResumed の順序:
1. `events.available()` が false なら lastQuerySucceeded を false にして null。
2. events.resumedSince を呼ぶ。
3. 正常取得なら lastQuerySucceeded を true にして一覧を返す。
4. ForegroundEventsUnavailableException なら lastQuerySucceeded を false にして null。

再取得時は `available()` ではなく `events.available()` を調べる。
前回の取得失敗だけで、以降の問い合わせを永久停止させない。

取得不能時の各メソッドの戻り値は、順に空集合・false・null。
正常取得時の既存の時刻フィルタと判定は維持する。

#### SetRunner

操作ごとの監視可否を保持し、一度取得不能になった操作では監視に復帰しない。
次の操作の開始時には再取得して判定する。

- `openSet` で開始時刻を決めた後、幕の表示前に
  `foregroundPkgsSince(startMs)` を1回呼んで取得を試す。
- その直後の watcher.available() を、その操作の初期監視可否にする。
- 初期監視不可なら、既存どおり curtain.show の watcherAvailable を false にする。
- ポーリング・再試行直前の確認で取得不能になったら、その操作を監視不可にする。
- 監視不可の操作では再試行しない。Timeout を selector に通知しない。
  showSwitchFailed も呼ばない。
- 監視不可への切替だけでは readyCount を増やさず、onSuccess も呼ばない。

途中で監視不能になった通常操作の幕:
- 操作開始時に決めた expectedDelayMs を保持する。途中で再計算しない。
- 開始時刻 + expectedDelayMs までの残り時間で解除する。
- 既に期限を過ぎていたら、その場で解除する。
- CurtainController は変更できないため、解除には curtain.ready() を使う。
  この場合の HideReason.Ready は内部の解除手段であり、分割成功として扱わない。
- 解除前に waitKind を None にする。
- タイマーは activeTimers で管理し、終了時に取り消す。
- 安全期限を延ばすための curtain.show の再呼び出しはしない。

幕なしの起動操作は、監視不能になっても既存の BOOT_CLEAR_MS で終了する。
その期限は変更しない。

リンク操作が途中で監視不能になった場合、時間解除時に linkFallback を取り出して null にしてから1回だけ呼ぶ。
これは分割成立の確認不能に対する既存の全画面退避に使う。
分割成功通知は行わない。

正常に取得できた空一覧だけから、イベントが届かない端末だと判定する機構は追加しない。

### 3. A2: 初回と再試行の Failed を同じ処理に通す

SetRunner に次の共通処理を作る。

```kotlin
private fun handleLaunchFailure(
    reason: FailReason,
    left: String,
    useCurtain: Boolean,
)
```

初回と3000msの再試行の両方で、Failed の場合に呼ぶ。

処理順:
1. waitKind を None にする。
2. activeTimers をすべて取り消す。
3. `selector.onFailure(reason, countable = true)`。
4. 左アプリを `startApp(left, false)` で直接起動し、
   後述の成功時記録を行う。
5. `messenger.showSplitUnavailable()`。
6. linkFallback を取り出して null にしてから呼ぶ。
7. 幕ありなら curtain.fail()。幕なしなら finishWithoutCurtain()。

手順6は curtain.fail() より前。
curtain.fail() は同期的に onHidden を呼び得るため、再入時に同じコールバックを残さない。

再試行で Started が返った場合は既存の待ちを続ける。
再試行を追加予約せず、開始時刻・幕・安全期限は変更しない。

EntryMissing は既存の SplitModeSelector の規則で即時に vendorUsable=false。
Exception は1回だけ連続失敗に加える。
起動時でも、明確な EntryMissing・Exception は記録する。
起動時の時間切れを数えない規則とは区別する。

この Task では AppPair の失敗をどの方式に帰属させるかの設計を変更しない。

### 4. A4: 保留に起動経路を保持する

`pending: Int?` を次に置き換える。

```kotlin
private data class PendingOpen(val index: Int, val forBoot: Boolean)
private var pending: PendingOpen? = null
```

- open(index) の保留は `PendingOpen(index, false)`。
- home() の保留は `PendingOpen(SetNavigator.HOME, false)`。
- openForBoot(index) の保留は `PendingOpen(index, true)`。
- next()/prev() の巡回元は `pending?.index ?: currentIndex`。
- 次・前の操作で作る新しい要求は通常経路。前の要求の forBoot を継承しない。
- 保留は最後の要求1件で上書きする。
- runPendingIfNeeded は取り出して pending を null にした後、
  `p.index != currentIndex` のときだけ実行する。
- p.forBoot が true なら openForBoot(p.index)、false なら open(p.index)。

同じ番号の要求を再実行しない既存の規則は維持する。
openForLink の実行中拒否と、番号を更新しない規則も維持する。

保留した起動要求は、再開後も幕なし。
その待ちの時間切れは連続失敗に数えない。
保留した起動ホーム要求も幕なしで startHome を呼ぶ。

### 5. A8/C6: Boolean で成功を確認できる起動を記録する

WarmTracker に次を追加する。

```kotlin
fun recordAppLaunch(pkg: String, succeeded: Boolean) {
    if (succeeded) markForeground(pkg)
}
```

SetRunner の外部アプリへの直接の `d.apps.startApp(...)` はすべて、
その Boolean を受け取って `d.warm.recordAppLaunch(pkg, succeeded)` へ渡す。

- 成功時だけ記録する。
- 同じ起動を2回呼ばない。
- 未インストール・非対応・方式なし・初回 Failed・再試行 Failed の退避を含む。
- startHome の呼び出しを、外部アプリの起動記録に置き換えない。
- 自作パッケージの暖かさは既存の ownTaskExists で判定する。
- 幕の expectedDelayMs は起動前に計算する。
  今回記録した成功によって、今回の幕の時間を短縮しない。
- 監視成功時の既存 markForeground は維持する。
- 分割要求の Started だけでは左右を記録しない。

PanelActivity の onLaunchApp の本体だけを次にする。
追加する呼び出しは recordAppLaunch の1か所だけ。

```kotlin
AppGraph.warm.recordAppLaunch(
    pkg,
    activityLauncher.startApp(pkg, adjacent = isInMultiWindowMode),
)
```

AppGraph.kt は変更しない。
AppGraph.warm と SetRunnerDeps.warm が同じインスタンスである接続を維持する。

### 6. E7: テストのイベントを仮想時計に従わせる

SetRunnerTest の FakeForegroundEvents に
`nowMs: () -> Long` を必須のコンストラクタ引数として追加する。

resumedSince は次の条件で返す。

```kotlin
events.filter { it.timeMs >= sinceMs && it.timeMs <= nowMs() }
```

TestEnv では scheduler を events より前に作り、
`nowMs = { scheduler.nowMs() }` を渡す。
FakeScheduler の Clock 実装として既存の nowMs() を使う。

未来のイベントを予約用データとして一覧に入れることは許す。
現在時刻より後のイベントを、問い合わせ結果には含めない。

既存 `readyCount_incrementsWhenSplitReady` は次の時刻と期待値にする。

1. open(2) 直後は readyCount=0。
2. youtube=250ms、applemusic=280ms を登録。
3. 開始から200msでは readyCount=0、opening は非 null。
4. 開始から399msでも readyCount=0。
5. 開始から400msのポーリングで readyCount=1、opening は null。

これは RULES の「テストを弱めない」の例外として司令塔が認めた変更である。
根拠は仕様 §4.3 の「左右の両方のパッケージが前面化したこと」と、
§5.5 の ACTIVITY_RESUMED による検知。
未来のイベントで200ms時点に成功する旧期待を、両イベント発生後の400msへ訂正する。
検証を削除せず、成立前の否定検証を追加する。

他の既存テストの期待値は変更しない。
追加で期待値変更が必要なら、変更せず RULES の3点セットで報告する。

## 手順(TDD)

### 1. E7 を先に再現する

既存の Fake のまま次を追加して Red を確認する。

- `SetRunnerTest.futureResumeEvents_doNotCompleteBeforeTheirTime`
  - 250msと280msの左右イベントを登録。
  - 200msで readyCount=0・opening非null。
  - 400msで readyCount=1・opening=null。
  - 現在の Fake では200msの検証が失敗することを確認する。

その後、Fake を時刻に従わせ、前節で許可した既存テストの期待値を訂正する。

### 2. A1 のテストを追加して Red を確認する

`UsageStatsForegroundEventsTest`:
- `queryFailure_isNotReportedAsEmptySuccess`
  - queryResumed が IllegalStateException を投げる。
  - ForegroundEventsUnavailableException が投げられ、
    cause が元の例外であること。
- `successfulEmptyQuery_remainsEmptySuccess`
  - queryResumed が空一覧を返す。
  - 例外にならず空一覧が返ること。
- `missingUsageStatsService_isUnavailable`
  - Robolectric の ContextWrapper で UsageStatsManager のサービス取得だけ null にする。
  - resumedSince が ForegroundEventsUnavailableException を投げること。

`ForegroundWatcherTest`:
- `queryFailure_marksWatcherUnavailable_andReturnsNoEvidence`
  - available=true、問い合わせが取得不能例外。
  - 3つの問い合わせの結果が空集合・false・null。
  - watcher.available()=false。
- `queryRecovery_restoresAvailability`
  - 取得失敗後、同じ Fake を正常取得へ戻す。
  - 次の問い合わせが実行され、正常なイベントを返し、
    watcher.available()=true。
- `successfulEmptyQuery_keepsWatcherAvailable`
  - 正常な空一覧では available=true。

`SetRunnerTest`:
- `queryFailure_threeOperations_doNotDisableVendor`
  - 許可あり、問い合わせは取得不能例外。
  - 冷えた通常操作を3回、各操作の時間解除まで進める。
  - 各操作で再試行なし、失敗回数0、vendorUsable=true、
    showSwitchFailed=0、readyCount=0。
  - 初期監視不可なので各幕は3500msで Timed。
- `queryFailureDuringWait_stopsRetryAndTimeoutCounting`
  - 正常取得で開始し、1000ms後から取得不能にする。
  - 3000msで再試行なし。
  - 初期の冷えた期限3500msまでに幕と opening が終了。
  - 10000msまで進めても失敗回数0、readyCount=0。
- `linkQueryFailureDuringWait_callsFallbackOnce`
  - openForLink 開始後に取得不能。
  - 時間解除でフォールバック1回、以降も1回。
  - 失敗回数0、readyCount=0。

Fake に取得不能例外を切り替えるスイッチを追加してよい。
正常な空一覧で既存の再試行・安全タイムアウトが機能するテストは維持する。

### 3. A2 のテストを追加して Red を確認する

`SetRunnerTest`:
- `retryEntryMissing_disablesVendorImmediately`
  - 初回 Started、2999msで結果を Failed(EntryMissing) に変更。
  - 3000msで呼び出し計2回、vendorUsable=false、
    lastFailure=EntryMissing、左全画面起動1回、opening=null。
- `retryException_countsOnce_andCancelsTimeout`
  - 初回 Started、再試行 Failed(Exception)。
  - 3000msで連続失敗1、lastFailure=Exception、幕はFailedで終了。
  - 10000msまで進めても失敗1、再試行計1回、
    showSwitchFailed=0。
- `bootRetryException_countsKnownFailureWithoutCurtain`
  - openForBoot、再試行 Failed(Exception)。
  - 3000msで失敗1、左全画面起動1回、opening=null、幕表示0回。
  - 以降に Timeout の追加がない。
- `linkRetryFailure_callsFallbackOnce`
  - openForLink、再試行 Failed(EntryMissing)。
  - 3000msでフォールバック1回、opening=null。
  - 同期的な幕の終了通知と、その後の時間経過でも追加なし。

既存の初回 Failed の検証と17bのテストは残す。

### 4. A4 のテストを追加して Red を確認する

`SetRunnerTest`:
- `pendingBootSet_preservesNoCurtainAndUncountedTimeout`
  - openForBoot(2) の途中で openForBoot(3)。
  - 最初の操作を正常な左右イベントで完了。
  - 次にセット3が起動され、幕表示0回。
  - セット3開始から10000msで opening=null、失敗回数0。
- `pendingBootHome_startsHomeWithoutCurtain`
  - 起動操作中に openForBoot(0) を保留。
  - 元の操作終了後にホーム起動1回、currentIndex=0、
    lastSetIndex=0、lastOpenedPair=null、lastOpenedAtMs=null、
    幕表示0回。
- `pendingLatestRequest_preservesItsOwnRoute`
  - 別々の TestEnv で
    「起動要求を保留→通常要求で上書き」と
    「通常要求を保留→起動要求で上書き」を試す。
  - 前者は通常の幕あり、後者は幕なし。
- `nextAfterPendingBoot_usesPendingIndexAndNormalRoute`
  - openForBoot(1) 実行中に openForBoot(2)、続けて next()。
  - 最後の行き先は3、再開は通常経路で幕あり。

### 5. A8/C6 のテストを追加して Red を確認する

`ForegroundWatcherTest`:
- `recordAppLaunch_successMarksPackageWithoutUsageAccess`
  - recordAppLaunch("a", true) で a が暖かい。
- `recordAppLaunch_failureDoesNotMarkPackage`
  - recordAppLaunch("a", false) では a は冷えたまま。
- `recordAppLaunch_ownPackageStillDependsOnOwnTask`
  - 自作パッケージを成功記録しても、
    ownTaskExists=false では冷えたまま。

`SetRunnerTest`:
- `directLaunchSuccess_withoutUsageAccess_makesNextOpenWarm`
  - 使用状況アクセスなし、selector を LeftOnly にしてセット1を開く。
  - youtube の直接起動が true。
  - 今回の幕は3499msまで表示、3500msで時間解除。
  - 同じセットを再度開くと799msまで表示、800msで時間解除。
  - readyCount=0、連続失敗0。
  - 事前の手動 markForeground は禁止。
- `directLaunchFailure_withoutUsageAccess_keepsNextOpenCold`
  - FakeActivityLauncher が startApp=false を返せるようにする。
  - 同じ操作を2回行い、2回目も3500msの冷えた時間解除。
  - youtube の暖かい記録がないことも検証する。

C6 の接続は、PanelActivity の onLaunchApp が
既存の startApp の戻り値を recordAppLaunch に渡していることを
差分で確認する。
この確認は Activity の操作テストを実行したことにはしない。
報告では、WarmTracker の単体検証とパネル接続の静的確認を区別する。

### 6. 実装と確認

1. 各指摘の Red を確認してから、その指摘の製品コードを変更する。
2. 指定の testDebugUnitTest コマンドで対象3クラスを検証する。
3. 実装後の失敗修正は最大3回。
   通らなければ失敗テスト名とエラー先頭30行を報告して止める。
4. 対象テストが通ったら assembleDebug を実行する。
5. git diff で担当外の変更がないことを確認する。
6. 17b の lastOpenedPair/lastOpenedAtMs の同時更新、
   リンク用の番号非更新、フォールバック1回の規則が維持されていることを確認する。

## この Task でしないこと

- A3/D1 の起動タイムアウト時のホーム復帰、D3/E3 の起動画面合計10秒化。
  boot・AppGraph の担当 Task で扱う。
- A5 の過去の Resume と現在の分割状態の区別。
  判定方式と端末でのイベント順序の確認は Task 20 で扱う。
- A6 の維持された側で新規 Resume が省略されるかの確認と対策。
  要実機確認のため Task 20 で扱う。
- A7 の AndroidActivityLauncher.isResizeable の本実装。
  Task 20 の確認と方針決定が必要。
- 正常な空イベント一覧が続く場合に、監視不能へ切り替える閾値の決定。
- 分割要求の Started だけを根拠に、左右を暖かい記録へ追加する変更。
- ForegroundWatcher が観測した全パッケージを WarmTracker へ接続する変更。
  今回は既存の監視成功時記録と、直接起動の成功記録を対象にする。
- AppPair 失敗時の方式別カウンタの再設計。
- CurtainController・CurtainWindow の変更。
  途中の監視不能による時間解除は、仕様に記した SetRunner 内の処理で行う。
- PanelActivity の操作テストの新設。
  T/ui/panel は担当外であり、変更しない。
- AppGraph.kt の変更。034 の担当。
- 設定・ホーム・リンクの各ファイル、Manifest、ビルド設定の変更。
- 指示書・.allow・レビュー文書の更新。
- 担当外ファイルを変えなければ成立しない追加修正。
  必要になった場合は変更せず、必要なファイルと理由を報告する。

## 報告

日本語で、次を報告する。

- 指摘別の再現テスト名、修正前の失敗内容、修正後の結果。
- 修正前から通った指摘と、修正しなかった理由。
- 実際に実行した完全なコマンド。
- 対象テストの実行件数・失敗件数、assembleDebug の結果。
- 作成・変更したファイル名。
- 許可された既存テストの期待値変更と、その根拠の仕様節。
- A8 の対象にした経路と、未対応の分割 Started 経路。
- パネル接続は静的確認であり、Activity 操作テストでは未確認であること。
- 担当外ファイルの変更が必要になった点。
- 実機未確認の事項。
- **決めたこと**。なければ「決めたこと: なし」。
