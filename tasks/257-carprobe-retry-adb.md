最初に tasks/RULES.md を読み、従う。

# Task 257: carprobe に adb: NO 時の再試行ボタン(Retry ADB)を追加する

実車で初回の `checkRoot()` がタイムアウト等で `adb: NO` になったときや、`Finish & Disable ADB (Normal Mode)` のあとに再度プローブしたくなったときに、アプリ強制終了や端末再起動なしで自己 bootstrap からやり直せるよう、`MainActivity` に最小差分で `Retry ADB` ボタンを足す。

## 作る/変えるファイル(これ以外は触らない)
- write: `tools/carprobe/src/main/java/com/gt8d/carprobe/MainActivity.java`
- write: `tools/carprobe/src/test/java/com/gt8d/carprobe/AdbMessageTest.java`

## 仕様

### 1. ボタン有効判定の純粋ヘルパー追加（`MainActivity.java`）
- パッケージプライベートの静的メソッドを2つ追加し、UI とクリックガードの両方から使う:
  - `static boolean canRunCommand(boolean rootAvailable, boolean busy)` → `return rootAvailable && !busy;`
  - `static boolean canRetryAdb(boolean rootAvailable, boolean busy)` → `return !rootAvailable && !busy;`

### 2. `Retry ADB` ボタンの配置と動作（`MainActivity.java`）
- フィールド `private Button retryButton;` を追加する（既存の `buttons` リストには入れない）。
- `onCreate` の3段目の行（現在 `Finish & Disable ADB (Normal Mode)` だけを置いている `addRow`）を2列にし、左に `Retry ADB`、右に既存の `Finish & Disable ADB (Normal Mode)` を並べる:
  - ラベル: `"Retry ADB"`
  - 見た目（文字サイズ `22`、`setAllCaps(false)`、最小高さ `dp(120)`）は既存の `button(...)` と揃える。
  - クリック時: `if (canRetryAdb(rootAvailable, busy))` を満たすときのみ、`rootStatus = "adb: checking...";` に戻して `startOperation("Checking adb", MainActivity::checkRoot);` を呼ぶ。
- 既存の `button(...)` 内のクリックガードは `if (canRunCommand(rootAvailable, busy))` を使う形に整理する。
- `render()` で既存の `buttons` ループ（`button.setEnabled(canRunCommand(rootAvailable, busy))`）に加え、`if (retryButton != null) retryButton.setEnabled(canRetryAdb(rootAvailable, busy));` を実行する。

### 変えないこと
- `onCreate` の初回自動プローブ条件（`if (!rootChecked && !busy)`）。画面再生成や `Finish` 後の再表示で勝手に `adbd` を再起動させない。
- `sessionPath` の扱い（`Retry ADB` で `sessionPath` を消さない）。
- `AdbClient.java`・`ServiceSummary.java`・既存6ボタンのラベルと処理内容。

## テスト(`AdbMessageTest` に足す)
- `buttonEnablementSeparatesNormalCommandsAndRetryAdb`:
  - `(rootAvailable=false, busy=true)`（初回チェック中・再試行中）では `canRunCommand == false` かつ `canRetryAdb == false`。
  - `(rootAvailable=true, busy=false)`（`adb: OK` 待機中）では `canRunCommand == true` かつ `canRetryAdb == false`。
  - `(rootAvailable=false, busy=false)`（初回 `adb: NO` 失敗後または `Finish` 停止後）では `canRunCommand == false` かつ `canRetryAdb == true`。
  - `(rootAvailable=true, busy=true)`（コマンド実行中）では両方 `false` であることを確かめる。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true testDebugUnitTest`
- `/home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true assembleDebug`

## 報告
- 変えたファイルと足したテスト名
- 実行したコマンドと結果(件数・失敗数)と生成された APK のパス
- RULES.md の 1「決めたこと」
