# Task 096: 場面テストに「幕の取り消し」を足す(v1.6)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 で、幕の「ホームへ戻る」(Task 093: `SetRunner.cancelOpening()`、ログ `[SetRunner] opening cancelled`・`[SetRunner] cancel guard home pkg=…`)と、失敗の通知帯(Task 092: ログ `[Notice] show <種類> text=…`)が入った。計画 `docs/superpowers/plans/2026-10-31-v1.6-ux.md` の Review Focus 2(取り消した後に分割が遅れて出ても、ホームが前に戻る。次のセットを開く操作を妨げない)を、実機の場面テスト(`scripts/device-scenarios.sh`)で確かめられるようにする。実機での実行は司令塔が行う(codex は adb を実行しない)。

## 変更するファイル(これで全部)
- `app/src/debug/java/com/gt8d/launcher/debug/DebugPanelReceiver.kt`
- `scripts/device-scenarios.sh`

使ってよいコマンド:
- `bash -n scripts/device-scenarios.sh`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. `DebugPanelReceiver` に extra `cancel`(値は問わない)を足す: メインスレッドで `AppGraph.runner.cancelOpening()` を呼び、`DiagLog.d("DebugPanel", "cancel opening=${開いている最中か}")` を出す。開いていなければ何もしない(ログは出す)。
2. 場面を2つ足す(**`names` と、`--with-ylauncher-home` の回の固定の一覧の両方**に足し、`--only` でも選べる。今の場面の書き方・準備の関数・判定の関数に合わせる)。前提: 端末のセット1は「左 YouTube・右 パネル」(初期設定のまま。実機で司令塔が確かめる)。
   - `set_cancel_cold`: 今の `reset cold` と同じ準備に加え YouTube も force-stop(取り消しが間に合うように)→ `panel --ei open 1` → 1.2 秒待つ → `panel --es cancel 1` → 7 秒待つ。判定:
     - `[SetRunner] opening cancelled` がある(無ければ FAIL。ready の後で取り消しが間に合わなかった場合も FAIL で、期待を緩めない)。
     - 取り消しの行より後に `waitBoth ready` が**ない**。
     - 最後の時点で分割の枠が無い(今の場面と同じく `[[ ! -s $active/slots.txt ]]`)。一番前の Activity(今の `topResumedActivity` の取り出し方)が YLuncher の `HomeActivity`(`startHome` は既定のホームでなく YLuncher のホームを明示で開くため、純正の回でも同じ。`.ui.home.HomeActivity` と完全修飾の両方の書き方を受ける)。
     - 取り消しの行より後に `[Notice] show` が無い(取り消しで通知を出さない)。`reason=Safety` が無い。
   - `set_cancel_thenOpen`: `set_cancel_cold` と同じ準備 → `panel --ei open 1` → 1.2 秒 → `panel --es cancel 1` → **2 秒** → `panel --ei open 1` → 10 秒(見張りの 5 秒の間に次を開いても妨げないことを見るため、7 秒は待たない)。判定:
     - 同じ `$active/logcat.txt` の中で、`opening cancelled` → その後に `[DebugPanel] open index=1` → その後に `waitBoth ready` の順に並ぶ(行番号で比べる。取れない行は 0 とせず FAIL。ファイルをまたいで比べない)。
     - `split_shown`、`left_is com.google.android.youtube`、`right_is com.gt8d.launcher.panel`(今の関数のまま)。`reason=Safety` が無い。
   - `cancel guard home` の行の数は、合否と別の変数に持ち、PASS・FAIL どちらの理由の欄にも「見張りでホームへ戻した回数=N」と書く(`reasons` に入れると FAIL になるので入れない)。見張りの回数が 0 でも合格(遅れて分割が出るかは時と場合による)。
3. 2つの場面の合格の理由の文(`row ... PASS '…'`)を、今の場面と同じ形で足す。
4. ほかの場面の判定は変えない。

## テスト
- `bash -n scripts/device-scenarios.sh` が通る。`assembleDebug` が通る。
- 実機では司令塔が `scripts/device-scenarios.sh --only set_cancel_cold` などで流す。

## この Task でしないこと
- adb の実行。ほかの場面の変更。git のコミット等。

## 報告
変更点、足した場面の流れと判定、**決めたこと**。
