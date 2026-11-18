最初に tasks/RULES.md を読み、従う。

# Task 258: GT8D-IPv6-Guard をワンショット適用＋adbd 即時完全停止(常駐ゼロ)にする

## 背景(実機確認済み、2026-11-18)
- GT8D 5G では SIM ON 時、モバイルデータ OFF でも `rmnet_data2` が `proto ra` で `main` テーブル(`32766`)に IPv6 `default` ルートを張るため、AOSP 標準の `32000: from all unreachable` が必須。
- 実機の `/data/local/gt8d-ipv6-guard/guard.log` 15回分の起動履歴により、`rule 32000` は起動ごとに1回追加すれば再起動まで消えないことを確認済み。
- 一方、`adbd` の起動残留(`service.adb.listen_addrs` 未クリアを含む)と `/data/local/gt8d-ipv6-guard/guard.sh` の10秒周期ループが Android Auto / DHU のレイテンシー悪化を招いていた。
- `guard.sh` の常駐ループを廃止し、非 root で `rule 32000` 未設定と分かったときだけ一瞬 `adbd` を起動してワンショット投入し、直後に `service.adb.listen_addrs=""` と `ctl.stop adbd` で完全停止させる。

## 作る/変えるファイル(これ以外は触らない)
- write: `GT8D-IPv6-Guard/AndroidManifest.xml`
- write: `GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/Bootstrap.java`
- write: `GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/Guard.java`
- write: `GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/MainActivity.java`
- write: `GT8D-IPv6-Guard/README.md`

## 仕様

### 1. まず現在の `GT8D-IPv6-Guard/` 内の全ファイルを読む
- 11月15日時点の既存変更(`guardEffectiveNoRoot()`、`adbdWasUp` による `ctl.stop adbd`)を確認したうえで、以下の仕様へ統一する(旧名 `guardEffectiveNoRoot` は `rulePresentNoRoot` に置き換えて消す)。

### 2. `Guard.java`: 非 root ルール確認とワンショット適用(`guard.sh` 全廃)
- **非 root 判定 `static boolean rulePresentNoRoot()`**:
  - `ProcessBuilder("/system/bin/ip", "-6", "rule", "show")` を直接実行する(`adbd` 不使用)。`waitFor(2, TimeUnit.SECONDS)` が false なら `destroy()` して `false`。終了コード 0 以外も `false`。出力は小さいので、終了を待ってから標準出力を読んでよい。
  - 標準出力の各行を `trim()` し、`^32000:\\s+from all unreachable$` に一致する行があれば `true`。例外時は `false`。`finally` で `destroy()`。
- **`apply(Context context, String reason)`**(シグネチャ `static String apply(Context, String) throws Exception` は維持):
  - `synchronized (APPLY_LOCK)` 内で、`!enabled(context)` なら `"自動適用は停止中です。"` を返す。
  - 有効時は最初に、旧版の `enabled` フラグファイル(`flag(context)`)が存在すれば削除する(旧 `guard.sh` はこのファイルが消えると10秒以内に自分で終了するため、アップデート直後のファストパスでも旧常駐ループが止まる)。削除失敗はログだけ残して続行。
  - **ファストパス(`adbd` 起動なし)**: `rulePresentNoRoot()` が `true` なら `Bootstrap` や `LocalAdb` を一切呼ばずに `last_success` を更新し、ログ `reason + ": rule present (adbd untouched)"` を残して完了文字列を返す。
  - **ワンショット投入パス(`rulePresentNoRoot() == false` のときのみ)**:
    - `Bootstrap` でローカル ADB ソケットを準備し、`try / finally` で必ず後片付け(下記 3 の `end`)を行う。
    - `LocalAdb.shell(...)` で実行する root コマンドは以下のワンショット処理のみとし、`nohup`・`guard.sh` 生成・`base64` 書き出し・バックグラウンド常駐は一切行わない:
      1. `[ "$(id -u)" = 0 ] || { echo GUARD_NOT_ROOT; exit 1; }`
      2. 旧版の残骸掃除: `/data/local/gt8d-ipv6-guard/pid` があれば、中身が数字で `/proc/<pid>/cmdline` に `/data/local/gt8d-ipv6-guard/guard.sh` を含むことを確認してから `kill` し(既存 `stopCommand()` の判定方法を流用)、`rm -rf /data/local/gt8d-ipv6-guard` を実行する。
      3. ルール追加: `/system/bin/ip -6 rule show | /system/bin/grep -Eq '^32000:[[:space:]]+from all unreachable([[:space:]]|$)' || /system/bin/ip -6 rule add priority 32000 unreachable`
      4. `echo GUARD_ONESHOT_OK`
    - 出力に `GUARD_ONESHOT_OK` が無ければログに残して例外を送出する(後片付けは `finally` で実行される)。
    - `finally` で `Bootstrap` の後片付けを終えたあと、再度 `rulePresentNoRoot()` が `true` であることを確認する(`false` なら例外を送出して `RetryJob` の再試行に乗せる)。`true` なら `last_success` を更新し、ログを残して完了文字列を返す。
  - 完了文字列(両パス共通): `"設定済み：SIM側へのIPv6経路の流れ込みを防止しています。\n動作方式：ワンショット（常駐なし）\n最終確認：" + now()`
- **`status(Context context)`**:
  - `LocalAdb` / `Bootstrap` を一切呼ばず(`Bootstrap.listening()` も呼ばない)、`enabled(context)` と `rulePresentNoRoot()` と `last_success` だけで次の4行を `\n` 区切りで返す:
    - `自動適用：有効` または `自動適用：停止中`
    - `ルール：設定済み` または `ルール：未設定`
    - `動作方式：ワンショット（常駐なし）`
    - `最終確認：<last_success>`(未保存なら `未実行`)
- **`setEnabled(Context context, boolean enabled)`**:
  - `enabled == true`: `SharedPreferences` の `enabled = true` を保存し、`schedulePeriodic(context)` を呼ぶ(フラグファイルは作らない)。
  - `enabled == false`: `SharedPreferences` の `enabled = false` を保存し、旧版の `enabled` フラグファイルが存在すれば削除し(失敗時は従来どおり例外)、`RETRY`(`8011`)と `PERIODIC`(`8012`)をキャンセルする。`LocalAdb` は呼ばない(`adbd` は起動しない)。
- 不要になった `script()`・`statusCommand()`・`Base64` 等の import は削除する。`scheduleRetry`・`schedulePeriodic`・`log`・`preferences`・`enabled`・`storage` は維持。

### 3. `Bootstrap.java`: `service.adb.listen_addrs` の確実なクリアと `adbd` 完全停止
- 既存の `ensure(Context)` を、開始と後片付けが対になる形に置き換える。形は次のとおり:
  - `static Bootstrap begin(Context context) throws Exception` … インスタンスを返す。フィールド `startedByUs`、`wifiDebugWasOn`。
  - `void end(Context context)` … 後片付け。例外を投げない(失敗はログのみ)。
- `begin` の開始時点で既に `listening() == true` だった場合(例: `carprobe` 実行中)は、`startedByUs = false` としてプロパティ変更も終了時の `adbd` 停止も行わない。
- `listening() == false` だった場合は:
  - 起動前のワイヤレスデバッグ状態 `Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1` を `wifiDebugWasOn` に記録する。
  - `startedByUs = true` を**プロパティ変更より前に**記録したうえで、`setprop service.adb.listen_addrs localabstract:gt8d-ipv6-guard` と `setprop ctl.restart adbd` を実行し、`listening()` が `true` になるまで最大15回×200ms(計3秒)待機する。間に合わなければ `IOException("端末内のADB待受を起動できません")`。
  - `begin` が途中で例外を投げた場合も、`startedByUs == true` なら後片付けを行ってから例外を再送出する(`Guard.apply` 側の `finally` に `Bootstrap` インスタンスが渡らないため)。
- 後片付け(`startedByUs == true` のときのみ):
  1. `setprop service.adb.listen_addrs ""` を必ず実行してソケット待受指定を消す。
  2. `wifiDebugWasOn == false` なら `setprop ctl.stop adbd` を実行して `adbd` を完全停止する。`true` なら `setprop ctl.restart adbd` で通常のワイヤレスデバッグ待受へ戻す。
  - いずれもアプリから既存 `property()`(`/system/bin/setprop` を `ProcessBuilder` で直接実行)で行う。ADB シェル越しには行わない。
  - 各段階でログを残す(例: `bootstrap: listen_addrs cleared`、`bootstrap: adbd stopped`、`bootstrap: adbd restarted for wireless debugging`、`bootstrap: adbd was already listening; left untouched`)。

### 4. `MainActivity.java`・`AndroidManifest.xml`・`README.md`
- `MainActivity.java`: 既存の3ボタン構成(自動適用切替・今すぐ適用・状態を更新)と初回自動有効化は維持する。説明文を「起動時に一度だけ設定し、常駐しません。必要なときだけ端末内のADBを一瞬使い、すぐ停止します。PCへの接続は不要です。」の趣旨に整え、停止時メッセージ「現在のルールは再起動まで残ります。」は維持してよい。
- `AndroidManifest.xml`: `versionCode` を `"2"`、`versionName` を `"1.1"` に上げる(パッケージ名・パーミッション・`BootReceiver`・`RetryJob`・`directBootAware` は変えない)。
- `README.md`: v1.1 のワンショット・常駐なし・`adbd` 即時停止の動作仕様と、2026-11-18 実機確認の背景(`rmnet_data2` の RA デフォルトルートと `guard.log` 検証)を簡潔に反映する。旧版からの更新時に `/data/local/gt8d-ipv6-guard` が消える条件(ルール未設定時のワンショット投入時、または再起動後の初回適用時)も書く。

### 変えないこと
- `LocalAdb.java` のソケット名(`gt8d-ipv6-guard`)とプロトコル処理
- `BootReceiver.java`・`RetryJob.java` の起動/再試行フック構成
- `build.ps1` の署名鍵設定、`guard-signing.p12`、`GT8D-IPv6-Guard-README.md`
- `app/` や `tools/carprobe/` など `GT8D-IPv6-Guard/` 以外のファイル

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/jdk17/bin/javac -encoding UTF-8 -source 8 -target 8 -Xlint:all -Xlint:-options -classpath /home/munde/tools/android-sdk/platforms/android-35/android.jar -d /tmp/gt8d-guard-258-classes GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/BootReceiver.java GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/Bootstrap.java GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/Guard.java GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/LocalAdb.java GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/MainActivity.java GT8D-IPv6-Guard/src/local/gt8d/ipv6guard/RetryJob.java`
- APK の生成・署名は司令塔が行う(署名鍵のパスワードが要るため)。codex は行わない。

## 検証
- 上記 javac が警告・エラーなく通ること(ルート直下で実行)。

## 報告
- 変えたファイル一覧と要点
- javac コマンドの結果
- RULES.md の 1「決めたこと」
