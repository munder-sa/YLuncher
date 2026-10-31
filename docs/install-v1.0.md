# YLuncher v1.0 導入手順（GT8D）

右側パネル構成を一から導入する手順。コマンドは利用者が実行するための例で、この手順書の作成時には実行していない。端末上の設定画面の細かい階層・表示名と、資料に実行記録がない adb コマンドの実機動作は未確認。

## 設定画面の開き方

**ドック(パネルの右端の縦のバー)の一番下にある「アプリ一覧」(点が9つのアイコン)を長押しする。** ホーム(全画面)でも、分割のパネルでも同じ。タップ(長押しでない)だとアプリ一覧が開く。

設定画面の項目(上から): 画面セット / パネルとホーム / リンクの行き先 / 操作 / 起動時 / 許可のチェック / 予備方式の準備、その下に「純正ランチャーに戻す」「デフォルトに戻す」「安全モードを解除」。

## 1. 前提と接続

1. GT8D（Android 15 / API 35、1920x1080、density 238）を用意する。メーカーの分割入口 `com.sd.factoryconfig/.SplitActivity` を使う構成。
2. PC は Windows + WSL。WSL に JDK 17、Android SDK（compileSdk 35）、`~/tools/gradle-run.sh` を用意する。adb は Windows の `adb.exe` を使う。以下はリポジトリ直下の WSL シェルから実行する例。
3. GT8D の設定でワイヤレスデバッグを有効にする。**端末の再起動ごとに有効にし直す。** IP・ポートを確認して接続する。記録上の接続先は次のとおり。ポートが変わった場合は以降の接続先も置き換える。

   ```sh
   adb.exe connect 192.168.137.248:5555
   adb.exe devices
   ```

## 2. ビルド

1. v1.0 を作る場合はタグに切り替える。作業中の変更がある場合は先に作業状態を整理する。

   ```sh
   git switch --detach v1.0
   ```

2. 本体 `app` と中継アプリ `panelhost` の debug APK を作る。同じ環境のデバッグ署名鍵で両方をビルドする。

   ```sh
   ~/tools/gradle-run.sh assembleDebug
   ~/tools/gradle-run.sh :panelhost:assembleDebug
   ```

3. 出力を確認する。本体は `app/build/outputs/apk/debug/app-debug.apk`、中継は `panelhost/build/outputs/apk/debug/panelhost-debug.apk`。このパスの APK は手順書作成時にも存在したが、今回ビルドしたものではない。

## 3. インストール

1. **本体を先、中継を後**に入れる。本体が定義する署名権限 `com.gt8d.launcher.permission.OPEN_PANEL` を中継が使って `PanelActivity` を開くため、先に権限の定義元を入れる。両 APK の署名も一致させる。
2. `adb.exe install -r <Windows のパス>` を使う。WSL のパスは `wslpath -w` で変換する。

   ```sh
   adb.exe -s 192.168.137.248:5555 install -r "$(wslpath -w "$PWD/app/build/outputs/apk/debug/app-debug.apk")"
   adb.exe -s 192.168.137.248:5555 install -r "$(wslpath -w "$PWD/panelhost/build/outputs/apk/debug/panelhost-debug.apk")"
   ```

3. YLuncher のホームを一度開く。純正ホームを既定のまま使う場合も、これで本体を起動できる。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell am start -n com.gt8d.launcher/.ui.home.HomeActivity
   ```

## 4. 初回の設定

1. ドックの「アプリ一覧」を長押しして YLuncher の設定を開く。「許可のチェック」から各項目の「設定を開く」を押す。Android のアプリ表示名は `GT8D Launcher`。

   パネルの「許可が必要」は、通知アクセス・重ねて表示・使用状況アクセスのどれかが不足すると出る。現コードでバナーのタップ先は Android のアプリ情報画面。各特別アクセスは、ドック長押しから「許可のチェック」で設定する。

2. **通知へのアクセス**を開き、GT8D Launcher を有効にする。ミニプレイヤーの `MediaListenerService` に必要。**入れ直すと外れることがある。** adb の代替は次のとおり（指示書に指定されたコマンド）。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd notification allow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService
   ```

3. **使用状況へのアクセス**を開き、GT8D Launcher を許可する。`PACKAGE_USAGE_STATS` と `OPSTR_GET_USAGE_STATS` を使う実装がある。`UsageStatsForegroundEvents` が前面化イベントを読み、`ForegroundWatcher` が分割後に左右のアプリがそろったかを判定する。未許可では片側欠けを検知できず、幕は時間で外す。adb の代替は設計資料にも記載されている。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell appops set com.gt8d.launcher GET_USAGE_STATS allow
   ```

4. **他のアプリの上に表示**を開き、GT8D Launcher を許可する。`SYSTEM_ALERT_WINDOW` は左右の帯と切り替えの幕に必要。adb の代替は設計資料にも記載されている。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell appops set com.gt8d.launcher SYSTEM_ALERT_WINDOW allow
   ```

   許可後は YLuncher のホームに戻る。ホーム・パネルの再開時に帯のサービスを開始する。

5. **既定のホーム**を選ぶ。「許可のチェック」の同名項目、または設定の「純正ランチャーに戻す」で Android のホーム選択画面を開く。YLuncher にするなら GT8D Launcher、純正のままなら純正ランチャーを選ぶ。

   v1.0 で確認した構成は「既定のホーム＝純正、場面テストで一時的に YLuncher」。両方で分割が動く構成。中継アプリが右側のパネル用タスクを分離する。adb の切り替えは場面テストでも使っている。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd role get-role-holders android.app.role.HOME
   adb.exe -s 192.168.137.248:5555 shell cmd role add-role-holder android.app.role.HOME com.gt8d.launcher 0
   ```

   純正を選ぶ場合は次を使う。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd role add-role-holder android.app.role.HOME com.android.launcher3 0
   ```

6. **既定のブラウザ**は GT8D Launcher にする。「許可のチェック」→「既定のブラウザ」で選ぶ。コードは `RoleManager.ROLE_BROWSER` の選択画面を開く。http/https を受ける入口は `.link.LinkEntryActivity`。adb の代替例は次のとおり（この BROWSER 付与コマンドの実機動作は未確認）。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd role add-role-holder android.app.role.BROWSER com.gt8d.launcher 0
   adb.exe -s 192.168.137.248:5555 shell cmd role get-role-holders android.app.role.BROWSER
   ```

   実際にページを表示するブラウザは YLuncher の設定「リンクの行き先」で選ぶ。既定値は Chrome（`com.android.chrome`）。分割を確認する場合は「ブラウザで分割」を選ぶ。Android の既定ブラウザと、ここで選ぶ表示用ブラウザは別の設定。

7. **Bluetooth 接続**は「許可のチェック」から開き、許可ダイアログで許可する。コードは `BLUETOOTH_CONNECT` の実行時許可を要求する。ドックの Bluetooth 状態表示に使い、未許可では状態不明になる。adb の代替例（実機動作は未確認）：

   ```sh
   adb.exe -s 192.168.137.248:5555 shell pm grant com.gt8d.launcher android.permission.BLUETOOTH_CONNECT
   ```

8. **通知の表示**は Android の設定→アプリ→GT8D Launcher→通知で許可する。`POST_NOTIFICATIONS` を宣言し、帯のフォアグラウンドサービスは「GT8D Launcher 動作中」の通知を作る。通知へのアクセスとは別の許可。現コードにこの権限の実行時要求は見つからない。adb の代替例（実機動作は未確認）：

   ```sh
   adb.exe -s 192.168.137.248:5555 shell pm grant com.gt8d.launcher android.permission.POST_NOTIFICATIONS
   ```

9. 通常はメーカー方式を使う。設定の分割状態が「メーカー方式で動作中」か確認する。アクセシビリティは予備方式（AppPair）のときだけチェック項目が出る。通常の導入では有効化しない。予備方式の画面は次でも開けるが、有効にすべきサービスと予備方式の実運用手順は未確認。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell am start -a android.settings.ACCESSIBILITY_SETTINGS
   ```

   debug マニフェストには実験用 `SpikeAccessibilityService` がある。場面テストの `DebugPanelReceiver` のためにこれを有効化する必要はない。

10. その他の宣言は `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`RECEIVE_BOOT_COMPLETED`、`INTERNET`。本体に利用者向けの個別許可要求はない。`BIND_NOTIFICATION_LISTENER_SERVICE` は通知サービスの保護指定で、利用者が別途付与するものではない。中継の署名権限にも画面上の許可操作はない。

## 5. 動作の確認

1. [ ] YLuncher のホームが表示される。純正ホームを既定にした場合は §3 の明示起動を使う。
2. [ ] 左の帯を内側にスワイプすると次、右の帯を内側にスワイプすると前のセットになる。
3. [ ] セット1が「YouTube | パネル」の分割で開く。初期設定の比率は 6:4。YouTube がインストールされていることを確認する。
4. [ ] 音楽アプリで曲を再生するとミニプレイヤーに曲名が出る。
5. [ ] 左のアプリからリンクを開くと「左のアプリ | ブラウザ」になる。YLuncher のホームからのリンクは全画面ブラウザになる。
6. [ ] ドックのアプリ一覧を長押しすると設定が開く。

## 6. 場面テスト

1. debug 版の本体と中継を入れ、§4 の許可を済ませる。Chrome、ChatGPT（`com.openai.chatgpt`）、YouTube、Apple Music を用意する。スクリプトはブラウザを Chrome に固定しているため、表示用ブラウザも Chrome にする。セット1・2は初期構成（YouTube | パネル、YouTube | Apple Music）にする。
2. 次の形式で実行する。角括弧は省略できる引数を示す。

   ```text
   bash scripts/device-scenarios.sh [--with-ylauncher-home] [--only 場面名]
   ```

   ```sh
   bash scripts/device-scenarios.sh
   bash scripts/device-scenarios.sh --with-ylauncher-home
   bash scripts/device-scenarios.sh --with-ylauncher-home --only link_appPanelFront
   ```

3. 接続先が変わった場合は `ADB` で指定する。

   ```sh
   ADB='adb.exe -s 192.168.137.248:5555' bash scripts/device-scenarios.sh
   ```

4. 場面名は `link_cold_noTasks`、`link_browserTaskPresent`、`link_warm_noTasks`、`link_homeTaskPresent`、`link_fromYlHome`、`set_strip_coldPanel`、`set_panelCard_switch`、`link_appPanelFront`。`--only` でも必要な前提場面を先に実行する。前提が不合格なら後続は SKIP。
5. 通常は純正ホームで実行する。`--with-ylauncher-home` は YLuncher を既定ホームにした場面も追加する。終了時には開始時の既定ホームを復元する。既定ブラウザの変化は検出するが、スクリプトは復元しない。
6. 結果は `build/device-scenarios/<日時>-<PID>/summary.md`。各場面のディレクトリに画面、操作ログ、logcat、Activity・タスクの記録を保存する。パネルの表示切り替えとカード起動には debug 版限定の `.debug.DebugPanelReceiver` が必要。release 版では代用できない。
7. GT8D の `uiautomator dump` は `ERROR: could not get idle state.` で失敗する記録がある。現スクリプトはこの受け口で操作し、画面取得には `screencap` を使う。

## 7. よくあること

1. **ミニプレイヤーに曲が出ない**：音楽アプリを開いて再生する。再起動直後に再生歴がない場合は待ち受け表示になる。通知へのアクセスが外れていないか確認する。外れていなくても画面で一度 OFF→ON にしてつなぎ直す。adb での代替例は次のとおり（`disallow_listener` を含む再接続の実機動作は未確認）。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd notification disallow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService
   adb.exe -s 192.168.137.248:5555 shell cmd notification allow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService
   ```

2. **adb がつながらない**：端末でワイヤレスデバッグを有効にし直す。Windows 側から届くネットワークか、IP・ポートが現在の値か確認し、§1 の接続をやり直す。WSL 側の adb ではなく `adb.exe` を使う。
3. **インストール中に端末が再起動した**：指示書には 2026-10-31 に1回のまれなソフトリブートの記録がある。原因は不明。今回の再現・原因調査は未確認。再起動後はワイヤレスデバッグを有効化し直し、本体・中継のインストール状態と通知アクセスを確認してから動作確認する。

## 8. 元に戻す

1. YLuncher の設定「純正ランチャーに戻す」で純正ホームを選ぶ。adb の代替は次のとおり。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd role add-role-holder android.app.role.HOME com.android.launcher3 0
   adb.exe -s 192.168.137.248:5555 shell input keyevent KEYCODE_HOME
   ```

2. Android の既定アプリ設定でブラウザも希望のアプリに戻す。Chrome に戻す adb の例（実機動作は未確認）：

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd role add-role-holder android.app.role.BROWSER com.android.chrome 0
   ```

3. 削除する場合は **中継→本体** の順にアンインストールする。アプリ内の設定も削除される。

   ```sh
   adb.exe -s 192.168.137.248:5555 uninstall com.gt8d.launcher.panel
   adb.exe -s 192.168.137.248:5555 uninstall com.gt8d.launcher
   ```

## 根拠

- 端末・ネットワーク・構成：[設計書](superpowers/specs/2026-10-28-gt8d-launcher-design.md)、[実装計画](superpowers/plans/2026-10-28-gt8d-launcher.md)。
- 権限と入口：`app/src/main/AndroidManifest.xml`、`app/src/debug/AndroidManifest.xml`、`panelhost/src/main/AndroidManifest.xml`。
- 初回設定と案内：`settings/Permissions.kt`、`settings/PermissionsScreen.kt`、`settings/SettingsScreen.kt`、`ui/panel/PanelEnvironment.kt`、`ui/panel/PanelScreen.kt`（いずれも `app/src/main/java/com/gt8d/launcher/` 以下）。
- 前面化・初期値・帯：同ディレクトリの `AppGraph.kt`、`foreground/UsageStatsForegroundEvents.kt`、`config/Defaults.kt`、`config/Model.kt`、`edge/EdgeStripService.kt`、`media/MediaWatcher.kt`、`ui/panel/MediaCard.kt`。
- ビルド：`.codex.conf`、`app/build.gradle.kts`、`panelhost/build.gradle.kts`。
- 場面テストと既知事項：`scripts/device-scenarios.sh`、`tasks/071-debug-panel-hook.md`、`tasks/072-scenario-only-prereq.md`、`tasks/073-install-guide-v1.0.md`。

## 9. v1.5 から v1.0 に戻す

v1.5 は初回起動で旧設定を版3に移し、移行前の設定を `files/config-v2-backup.json` に残す。v1.0 は版3を読めず既定値になるので、**v1.0 を入れる前に、v1.5 が入っている状態でバックアップを復元する**。移行の後に v1.5 で変えた内容と、左パネルのセットは失われる。以下は debug 版用のコマンド例で、今回実機では実行していない。接続先は現在の IP・ポートに置き換える。

1. v1.0 の本体・中継 APK を §2 に従い同じ署名鍵で用意する。まだインストールしない。
2. バックアップを読み出せることを確かめる。存在しない、または読めない場合は復元できないため、ここで止める。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell run-as com.gt8d.launcher cat files/config-v2-backup.json
   ```

3. **既定のホームを純正に戻しておく**(YLuncher が既定のホームのままだと、停止の直後にシステムが YLuncher を起こし直し、v1.5 が書き戻した設定をもう一度版3に移して上書きする)。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell cmd role add-role-holder android.app.role.HOME com.android.launcher3 0
   ```

4. 次の順に、設定を復元 → 本体を停止 → v1.0 の APK を本体・中継の順で上書きする。復元後、インストールが終わるまで YLuncher を開かない。`-d` は版を下げる指定。

   ```sh
   adb.exe -s 192.168.137.248:5555 shell run-as com.gt8d.launcher cp files/config-v2-backup.json files/config.json
   adb.exe -s 192.168.137.248:5555 shell am force-stop com.gt8d.launcher
   adb.exe -s 192.168.137.248:5555 install -r -d "$(wslpath -w "$PWD/app/build/outputs/apk/debug/app-debug.apk")"
   adb.exe -s 192.168.137.248:5555 install -r -d "$(wslpath -w "$PWD/panelhost/build/outputs/apk/debug/panelhost-debug.apk")"
   ```

5. §3 のホーム起動で v1.0 を開き(YLuncher を既定のホームにする場合はこの後で)、移行前の設定と §4 の許可を確認する。
