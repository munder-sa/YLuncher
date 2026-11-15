最初に `tasks/RULES.md` を読み、従う。

# Task 240: 車内ワンタップ検証ツール `tools/carprobe`(独立 APK)

実車で、GT8D の画面だけ(PC・外部 ADB なし)で、ログの保存と `a0call` の実行ができる検証アプリ。**YLuncher 本体(`app/`・`panelhost/`)には一切触れない・混ぜない**。`tools/renderprobe` と同じ「独立 Gradle プロジェクト」にする。

## 背景(決まっていること)
- root の取り方: GT8D には setuid root の `/system/xbin/sdsu_no`(`su` 相当、パスワード不要、書式 `sdsu_no 0 <コマンド…>`)がある。ベンダーのアプリもこれを使う。**通常アプリからこれが通るかは未確認**(このアプリで確かめる)。`su` コマンドは無い。
- `/data/local/tmp/a0call`(実行中の `sdAutoReverse` に ID 13 の NAV フォーカスを送る root ツール)は配置済み。使い方: `a0call status` / `a0call native` / `a0call toggle <待機ms>`。
- 画面は 12.9 インチのタッチ(横長)。車内で押しやすい大きなボタンにする。
- 言語: 画面の文言は英語の短いラベル(下記)+結果は日本語可。

## 作るファイル(これ以外は作らない・変えない)
- `tools/carprobe/settings.gradle.kts`、`tools/carprobe/build.gradle.kts`(`tools/renderprobe` を手本に。Compose・Kotlin は使わない。**Java だけ**。plugins は `com.android.application` version "8.7.3" のみ。namespace/applicationId `com.gt8d.carprobe`、minSdk 35、targetSdk 35、versionCode 1、versionName "0.1.0"、Java 17。テスト用に `testImplementation("junit:junit:4.13.2")`。`rootProject.name = "YLuncherCarProbe"`)
- `tools/carprobe/src/main/AndroidManifest.xml`(権限は不要。Launcher の Activity 1 つ、ラベル「RAV4 Probe」、横向き固定 `sensorLandscape`、テーマは `@android:style/Theme.DeviceDefault.NoActionBar`)
- `tools/carprobe/src/main/java/com/gt8d/carprobe/MainActivity.java`
- `tools/carprobe/src/main/java/com/gt8d/carprobe/RootShell.java`
- `tools/carprobe/src/main/java/com/gt8d/carprobe/ServiceSummary.java`(Android に依存しない純粋な Java。ログの解析だけ)
- `tools/carprobe/src/test/java/com/gt8d/carprobe/ServiceSummaryTest.java`

## RootShell
- `static Result run(String command, int timeoutMs)`: 次の順で試し、最初に root(`id` が `uid=0`)になった経路を覚える。1) `/system/xbin/sdsu_no 0 sh -c <command>` 2) `/system/xbin/sdsu 0 sh -c <command>` は**使わない**(パスワードが要る) 3) `su -c <command>`(あれば)。どれも root にならなければ「no root」として結果に失敗理由を入れる。
- `Result` は `exitCode`、`stdout`(stderr も混ぜてよい)、`ok`、`via`(経路名)。タイムアウトで子プロセスを止める。メインスレッドでは呼ばない(`ExecutorService` で単一スレッド)。
- 起動時に `id` を root で実行し、画面の最上部に `root: OK (uid=0 via sdsu_no)` か `root: NO (<理由>)` を出す。

## ボタン(5 つ。大きく、押下中は他を無効にして二重実行を防ぐ)
画面の構成: 上段にステータス行(root と、セッションのログファイル名)、中段に 2 列のボタン、下段に結果のテキスト枠(スクロール可、等幅、18sp 以上、直近の操作の結果を**上書き**で表示)。ボタンは高さ 120dp 以上、文字 22sp 以上。

1. **`Save Boot Log & Services`**: root で `logcat -d -v threadtime -b all` の全文を `/sdcard/Download/rav4_probe_<yyyyMMdd_HHmmss>.log` に保存する(root のシェル側で `>` リダイレクトして保存。アプリ側では大きなログを抱えない)。そのファイルの**先頭**に、`ServiceSummary` の抽出結果(下記)を入れる。実装は、まず `logcat -d -v threadtime -s SdAAReverse:V` を取って `ServiceSummary` で解析し、要約をファイルの先頭に書いてから、全文を追記する。**このファイルがその実行の「セッションのログ」になり、以降のボタン 2〜4 の出力は、同じファイルに追記する**(まだ無ければ、ボタン 2〜4 の初回に新しく作る)。画面には、成功/失敗、保存先のファイル名(フルパス)、ファイルサイズ、要約(`Cluster sink: YES/NO`、`Nav status service: YES/NO`、解像度の一覧、`NAV_FOCUS` の通知の有無)を出す。
2. **`Run a0call status`**: root で `/data/local/tmp/a0call status` を実行し、出力を画面に出し、セッションのログへ時刻つきで追記する。
3. **`Run a0call native`**: `/data/local/tmp/a0call native` を実行。直後に `logcat -d -v threadtime -s SdAAReverse:V` から `number=13` と `focus_type` を含む直近 6 行を取って、実行の出力と一緒に画面表示・追記する(送出の証拠)。
4. **`Run a0call toggle`**: 横に 2 つのボタン `toggle 800ms` と `toggle 3000ms`(それぞれ `a0call toggle 800` / `a0call toggle 3000`)。結果の扱いは 3 と同じ。
5. **`Finish & Disable ADB (Normal Mode)`**: 確認ダイアログ(「ADB を OFF にして通常の車載モードへ戻します。よろしいですか」、OK/Cancel)のあと、root で次を順に実行し、各コマンドの成否を画面に出す: `setprop persist.adb.tcp.port ""`、`settings put global adb_wifi_enabled 0`、`settings put global adb_enabled 0`、`setprop ctl.stop adbd`。最後に「ADB OFF 完了。通常モードです」を出す。**このボタンは最後のテスト用。結果は adbd を止める前に画面とログへ書く**。

## ServiceSummary(純粋な Java、テストする)
- 入力: `logcat -v threadtime -s SdAAReverse:V` の全文(複数行の文字列)。
- 解析: 各行の `SdAAReverse: ` より後ろだけを使う。**最後の `MESSAGE_SERVICE_DISCOVERY_REQUEST` を含む行より後**の行だけを対象にする(接続が何度もあっても最新のもの)。同じ内容が 2 回出る(1 回目は行の長さの上限で途中が切れる)ので、集合・重複排除で扱う。
- 出す値: `hasCluster`(`display_type: DISPLAY_TYPE_CLUSTER` がある)、`hasNavStatus`(`navigation_status_service` がある)、`displayTypes`(出てきた `DISPLAY_TYPE_*` の重複なしの一覧)、`resolutions`(`codec_resolution:` の値の重複なしの一覧)、`navFocusSeen`(`NAV_FOCUS` を含む行がある)、`found`(最後のサービス一覧が見つかったか。見つからなければ「サービス一覧が見つかりません(ログが入れ替わった可能性。USB を抜き挿ししてやり直し)」を要約に入れる)。`String toText()` で画面・ファイル先頭用の複数行の要約を返す。
- テスト `ServiceSummaryTest`(JUnit 4)は次の 4 件。実機の実ログの形(`11-15 15:15:09.127  1843  3543 D SdAAReverse: ` で始まる行、`    display_type: DISPLAY_TYPE_MAIN` のようにインデントのある行)を使った短い文字列で書く。
  1. メインだけの一覧 → `hasCluster=false`、`hasNavStatus=true`(`navigation_status_service {` を含める)、`displayTypes=[DISPLAY_TYPE_MAIN]`、`resolutions=[VIDEO_1920x1080]`。
  2. クラスターを含む一覧 → `hasCluster=true`、`displayTypes` に MAIN と CLUSTER。
  3. サービス一覧が複数回ある → 最後のものだけを見る(前のものにだけ CLUSTER があっても、最後に無ければ false)。
  4. `MESSAGE_SERVICE_DISCOVERY_REQUEST` が無い → `found=false`。

## 注意
- 通常アプリから `sdsu_no` が通らなければ、画面にそう出して、その時点で動く部分(何も root 不要な表示)だけで終える。**ここで別の root 経路(ADB のループバックなど)は作らない**(司令塔が結果を見て決める)。
- `/sdcard/Download/` に書くファイルは root のシェルが作る。アプリ側にストレージ権限は付けない。
- 認証情報・個人情報を扱わない。ネットワーク権限は付けない。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true testDebugUnitTest
    /home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true assembleDebug

## 報告
- 変えた/作ったファイル、コマンドと結果(件数・時間)、APK のパス、決めたこと(RULES.md の 1)
