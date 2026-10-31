# Task 093: 幕に「開いています」と「ホームへ戻る」を出し、開く処理を取り消せるようにする(v1.6 B3)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §4 B3 と §2、計画 `docs/superpowers/plans/2026-10-31-v1.6-ux.md` の Task 093 と Review Focus 2 を読むこと(この指示書が優先)。
- 今の幕(`curtain/CurtainContent.kt`)は、セット名・左右の絵・0.8 秒後の進行線・点を出すだけで、開いている間は何もできない。冷えたアプリだと 3〜4 秒待たされ、間違えて開いても戻れない。
- 幕は `edge/CurtainWindow.kt` が重ね表示で出す(`FLAG_NOT_FOCUSABLE` だけで、タッチは受ける)。幕の状態は `curtain/CurtainController.kt`、開く処理は `sets/SetRunner.kt`。
- Task 092 で、失敗の通知帯(`notice/NoticeOverlay.kt`)と `Messenger.show(notice)` が入っている。幕が出ている間に来た通知は、幕が外れてから出る。**まず 092 で入った `NoticeOverlay`・`Messenger`・`AppGraph` の今のコードを読む**こと。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/curtain/CurtainController.kt`
- `app/src/main/java/com/gt8d/launcher/curtain/CurtainContent.kt`
- `app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt`
- `app/src/main/java/com/gt8d/launcher/edge/EdgeStripService.kt`(`CurtainWindow` に取り消しの口を渡すだけ)
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/main/java/com/gt8d/launcher/notice/NoticeOverlay.kt`(`onCurtainHidden(HideReason.Cancelled)` で幕待ちの通知を捨てるだけ)
- `app/src/test/java/com/gt8d/launcher/curtain/CurtainControllerTest.kt`
- `app/src/test/java/com/gt8d/launcher/curtain/CurtainContentTest.kt`
- `app/src/test/java/com/gt8d/launcher/edge/CurtainWindowTest.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`
- `app/src/test/java/com/gt8d/launcher/notice/NoticeOverlayTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.curtain.*' --tests 'com.gt8d.launcher.edge.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.notice.*'`

## 仕様
1. `CurtainTarget` に `val cancellable: Boolean = true` を足す。`SetRunner.targetFor` のホーム(`SetNavigator.HOME` と、セットが無くてホームに落ちる分)は `false`。セット・リンク(`openForLink` の「リンクを開く」)は `true`。
2. `HideReason` に `Cancelled` を足す。`CurtainController` に `fun cancel()`(出ていれば `hide(HideReason.Cancelled)`、出ていなければ何もしない)。タイマー(4 秒の Safety など)は今の `hide` と同じく全部止める。4 秒の上限・0.8 秒の `PROGRESS_AFTER_MS` は変えない。
3. `CurtainContent(target, showProgress, iconFor, onCancel: () -> Unit = {})`:
   - セット名の**すぐ下**に「開いています」(`Dims.BodyText`、`Palette.Muted`、`testTag("curtain_opening")`)。ホームの幕(`cancellable == false`)にも出してよい。
   - `showProgress && target.cancellable` のとき、進行線の下(12dp 空けて)に「ホームへ戻る」のボタン(高さ・最小幅 `Dims.MinTouch` 以上、白い輪郭 2dp・白い文字 `Dims.BodyText`・角丸 36dp、`testTag("curtain_cancel")`)。押すと `onCancel()`。`showProgress` が false、または `cancellable` が false のときは出さない(場所も取らない)。
   - 出し入れで、左右の絵・点の位置がずれないこと(今の `SpaceBetween` の並びを保つ。ボタンの分は真ん中の Column の中に入れる)。
4. `CurtainWindow` のコンストラクタに `onCancel: () -> Unit = {}` を足し、`CurtainContent` に渡す。`hide(HideReason.Cancelled)` は今の `Ready` と同じ消え方(フェード)。`EdgeStripService` は `CurtainWindow(..., onCancel = { AppGraph.curtain.cancel() })` で渡す。
5. `SetRunner`(`onCurtainHidden` に `Cancelled` の分岐):
   - 開く処理を取り消す: タイマー(再試行・監視)を止め、`pending` を捨て、リンクのコールバックは**どちらも呼ばない**(ブラウザを開かない)、`selector.onFailure` を呼ばない、通知を出さない。
   - ホームへ戻す: `currentIndex = HOME`、`lastIndex.lastSetIndex = HOME`、`lastOpenedPair`・`lastOpenedAtMs`・`lastReadyAtMs` を null、`d.startHome()`。パネルのセッションは終わる(今の `reason != Ready` の処理のとおり)。
   - **遅れて分割が出たら、もう一度ホームを前へ**: 取り消した時点から `CANCEL_GUARD_MS = 5000` の間、監視が使えるなら `POLL_MS` ごとに `d.watcher.lastForegroundSince(基準の時刻)` を見て、取り消した開き方の左・右のパッケージ(中継 `panelHostPkg` を含む)か、`VendorBridge.VENDOR_PKG` が前に出ていたら `d.startHome()` を呼ぶ(呼んだら基準の時刻を今にして見張りを続ける。呼ぶのは見張りの間で最大 2 回)。監視が使えないときは、最初の `startHome()` だけ。
   - 見張りは、`open`・`next`・`prev`・`home`・`openForBoot`・`openForLink` のどれかが呼ばれたら止める(次の操作を妨げない)。
   - `fun cancelOpening()` も足す(`d.curtain.cancel()` を呼ぶだけ。外から取り消す口)。
   - ログ: `DiagLog.d("SetRunner", "opening cancelled")`、もう一度ホームへ戻したとき `DiagLog.d("SetRunner", "cancel guard home pkg=$pkg")`(場面テストで使う)。
6. `NoticeOverlay.onCurtainHidden(reason)`(092 で入った口)で、`reason == HideReason.Cancelled` なら、幕待ちの通知を**出さずに捨てる**(取り消した開き方の「全画面で開きました」などを出さないため。`CurtainController.hide` は `view.hide` を `onHidden` より先に呼ぶので、SetRunner の側では間に合わない)。今出ている通知帯は消さない。

## テスト(足す。既存は弱めない。`HideReason` の網羅などで既存の期待値を変えたら報告)
- `CurtainControllerTest`: 出ている間の `cancel()` で `onHidden(Cancelled)` が1回、あとで 4 秒を過ぎても Safety は来ない。出ていないときの `cancel()` は何もしない。
- `CurtainContentTest`: 「開いています」が出る。`showProgress=true`・`cancellable=true` で「ホームへ戻る」が出て高さ 72dp 以上、押すと `onCancel` が1回。`showProgress=false` か `cancellable=false` なら出ない。ボタンの有無でセット名(`curtain_set_name`)・左右(`curtain_left`)の位置が変わらない。
- `CurtainWindowTest`: 渡した `onCancel` がボタンで呼ばれる。`hide(Cancelled)` で窓が外れる。
- `SetRunnerTest`(偽の時計・スケジューラ・監視で):
  - セットを開いて ready の前に `cancelOpening()` → `startHome` が呼ばれ、`currentIndex == HOME`、`selector` の失敗は数えない、通知は出ない、`RETRY_AT_MS` を過ぎても `launcher.open` は2回目が呼ばれない。
  - 取り消しの 1.5 秒後に左のアプリが前に出た → `startHome` がもう一度呼ばれる。見張りの間に何度出ても、取り消し後の `startHome` は合わせて最大 3 回(最初 + 2 回)。
  - 取り消しの後に `next()` → 次のセットが普通に開き、その後に前のセットのアプリが前に出てもホームへは戻さない。
  - 開いている間に `next()` で溜めた `pending` は、取り消しで捨てられる(開かない)。
  - リンク(`openForLink`)を取り消す → `onReady`・`onNotSplit` のどちらも呼ばれない。
  - ホームの `targetFor(HOME).cancellable == false`、セットは `true`。
- `NoticeOverlayTest`: 幕が出ている間に受けた通知は、`onCurtainHidden(Cancelled)` では出ずに捨てられる(その後 5 秒の見張りでも出ない)。`Ready` などでは今どおり出る。今出ている通知帯は `Cancelled` で消えない。

## この Task でしないこと
- 幕の見た目のほかの変更(緑→白・パネルの絵は段階 C)。4 秒の上限の変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、変えた既存の期待値、**決めたこと**(見張りの基準の時刻の扱いなど)。
