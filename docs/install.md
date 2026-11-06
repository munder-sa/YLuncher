# YLuncher 導入手順(GT8D)

現在の版(v2.3 系)を入れる・更新する・元に戻すための手順です。版ごとの変更点は [更新履歴](CHANGELOG.md)、v1.x 当時の手順は [docs/archive/](archive/) にあります。

YLuncher は2つのアプリでできています。

| アプリ | パッケージ名 | 表示名 | 役割 |
|---|---|---|---|
| 本体 | `com.gt8d.launcher` | YLuncher | ホーム・設定・分割の制御 |
| 中継 | `com.gt8d.launcher.panel` | YLuncher パネル | 分割の片側にパネルを置く |

## 1. 前提と接続

- 端末: GT8D(Android 15 / API 35、1920×1080)。メーカーの分割入口 `com.sd.factoryconfig/.SplitActivity` を使う。
- PC: Windows + WSL。WSL に JDK 17・Android SDK(compileSdk 35)・`~/tools/gradle-run.sh`。adb は Windows の `adb.exe` を使う。コマンドはリポジトリ直下の WSL シェルから実行する。
- GT8D の設定でワイヤレスデバッグを有効にし、表示された IP・ポートへ接続する。**端末を再起動するたびに有効にし直す**(ポートも変わる)。

```sh
adb.exe connect <IP>:<ポート>
adb.exe devices
```

以下のコマンドでは `-s <IP>:<ポート>` を省略している。複数の端末をつないでいるときは付ける。

## 2. ビルド

```sh
~/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease
```

- 出力: 本体 `app/build/outputs/apk/release/app-release.apk`、中継 `panelhost/build/outputs/apk/release/panelhost-release.apk`。
- 署名: `keystore.properties`(Git に含めない)が無い場合は debug の署名鍵で署名される。本体と中継は**同じ鍵**で署名する。鍵が変わると上書きできない。
- 版: 本体と中継の `versionCode` / `versionName` は常にそろえる(`app/build.gradle.kts`、`panelhost/build.gradle.kts`)。実機確認用は `<次の版>-betaN` とする。
- 場面テスト(§8)には debug 版(`~/tools/gradle-run.sh assembleDebug`)が要る。

## 3. インストール・更新

**本体を先、中継を後**に入れる(中継は本体が定義する権限を使う)。新規でも更新でも同じ。

```sh
adb.exe install -r "$(wslpath -w "$PWD/app/build/outputs/apk/release/app-release.apk")"
adb.exe install -r "$(wslpath -w "$PWD/panelhost/build/outputs/apk/release/panelhost-release.apk")"
```

設定・保存済みの Apple Music の接続は、同じ署名での上書きなら引き継がれる。

### 更新した後に確かめること

上書きすると、次の2つが外れることがある(実機で確認済み)。

1. **通知へのアクセス**: 外れていたら許可し直す。

   ```sh
   adb.exe shell cmd notification allow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService
   ```

2. **ユーザー補助「YLuncher(PiP の判定)」**(使っている場合): 外れていたら有効にし直す(§4 の 7)。

`am force-stop com.gt8d.launcher` でもユーザー補助が外れる。

## 4. 初回の設定

YLuncher の設定は、**アプリ一覧の上の「設定」ボタン**、または**ドックの一番下の「アプリ一覧」(点が9つのアイコン)の長押し**で開く。設定 →「許可のチェック」に、足りない許可と各設定画面への入口が並ぶ。

| # | 許可 | 用途 | adb で行う場合 |
|---|---|---|---|
| 1 | 通知へのアクセス | ミニプレイヤー・歌詞(再生中の曲を知る) | `cmd notification allow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService` |
| 2 | 使用状況へのアクセス | 分割の左右がそろったかの判定、よく使うアプリ | `appops set com.gt8d.launcher GET_USAGE_STATS allow` |
| 3 | 他のアプリの上に表示 | 画面両端の取っ手・切り替えの幕・失敗の通知帯 | `appops set com.gt8d.launcher SYSTEM_ALERT_WINDOW allow` |
| 4 | 通知の表示 | 常駐通知「YLuncher 動作中」 | `pm grant com.gt8d.launcher android.permission.POST_NOTIFICATIONS` |
| 5 | Bluetooth 接続 | ドックの Bluetooth 状態表示 | `pm grant com.gt8d.launcher android.permission.BLUETOOTH_CONNECT` |
| 6 | 既定のホーム | YLuncher をホームにする | `cmd role add-role-holder android.app.role.HOME com.gt8d.launcher 0` |
| 7 | ユーザー補助(PiP の判定)※任意 | 小窓(PiP)再生中のアプリをパネルから開いたとき、分割に組み直す。画面の内容は読まない | 下記 |
| 8 | 既定のブラウザ※任意 | リンクを分割で開く。実際に表示するブラウザは設定「リンクの行き先」で選ぶ(既定 Chrome) | `cmd role add-role-holder android.app.role.BROWSER com.gt8d.launcher 0` |

adb のコマンドは `adb.exe shell` に続けて実行する。

ユーザー補助を adb で有効にするときは、**今の値に足す**(`settings put` は値を丸ごと置き換えるため、ほかのサービス、例えば `com.carsyso.main` の分を消さない)。

```sh
adb.exe shell settings get secure enabled_accessibility_services
adb.exe shell settings put secure enabled_accessibility_services "<今の値>:com.gt8d.launcher/com.gt8d.launcher.pip.PipWatcherService"
```

## 5. 動作の確認

- [ ] ホームボタンで YLuncher のホームが出る。
- [ ] セットのカードから分割で開く(初期のセット1は「YouTube | パネル」)。
- [ ] 画面両端の取っ手を内側にスワイプするとセットが切り替わる。
- [ ] 音楽アプリで再生するとミニプレイヤーに曲名が出る。
- [ ] 分割中のアプリからリンクを開くと、隣にブラウザが開く。

## 6. 機能ごとの設定

### 歌詞表示

ホームのミニプレイヤー右上の歌詞ボタンで、ジャケットと歌詞を切り替える(既定はオフ)。オンの間だけ、再生中の曲名・アーティスト・アルバム名・長さを歌詞の取得元へ送る。オフの間は通信しない。

取得の優先順位は、Apple Music の単語同期歌詞(接続している場合)→ [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db) → LRCLIB(lrclib.net)の同期歌詞 → LRCLIB の平文。

### Apple Music の歌詞に接続する(任意)

1. 設定 →「音楽」→「Apple Music の歌詞」→「Apple Musicにログイン」。
2. YLuncher 内の画面で Apple にログインし、「このアカウントで接続」を押す。
3. 「接続済み(地域: jp など)」と「この端末に保存済み」が出れば完了。

接続情報は端末内に暗号化して保存し、プロセス終了・同じ署名での更新・端末の再起動の後も復元する。パスワードや Web 用トークンをチャットやログに貼る必要はない。手動のトークン入力も同じ画面にある。接続を解除するときは、YLuncher 内の Web ログイン情報も消すかを選べる。認証が無効になったら再ログインする。

### 自作のショートカットのアプリを分割で開く

起動するとすぐ別のアプリを開いて自分は閉じるアプリ(例: 自作の GPT Live)は、そのままでは分割の片側に置けない。そのアプリの `AndroidManifest.xml` のランチャーの Activity に次の `<meta-data>` を書くと、YLuncher は**行き先のアプリを分割に置き、分割ができてから1回だけリンクを送る**。

```xml
<activity android:name=".LauncherActivity" android:exported="true" ...>
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <!-- 必須: 分割に実際に置くアプリ -->
    <meta-data android:name="com.gt8d.launcher.split.PACKAGE" android:value="com.openai.chatgpt" />
    <!-- 任意: 分割ができてから送る先(パッケージ/クラス) -->
    <meta-data android:name="com.gt8d.launcher.split.COMPONENT" android:value="com.openai.chatgpt/com.openai.chatgpt.MainActivity" />
    <!-- 任意: 送るリンク(ACTION_VIEW の data) -->
    <meta-data android:name="com.gt8d.launcher.split.URI" android:value="https://chat.com/?mode=voice" />
</activity>
```

- `PACKAGE` だけなら、行き先のアプリを分割に置くだけ。
- `COMPONENT` は、行き先の**本体の画面**にする。中継の画面(ChatGPT なら `ChatGptDeeplinkActivity`)に送ると、行き先が冷えているとき分割の外に全画面で出ることがある。
- 行き先が入っていない・`PACKAGE` が自分自身・左右の行き先が同じ、のどれかなら、今までどおりそのアプリ自身で開く。
- 全画面で開くとき(ホームの段など)は、そのアプリ自身を開く。
- 送れたかは、診断ログ(タグ `GT8D`)の `redirect target=… sent=true` で分かる。

GPT Live のソースは `F:\ChatGPT-Live-GT8D-source`。ビルドは PowerShell 7 で `pwsh -File F:\ChatGPT-Live-GT8D-source\build.ps1`(出力 `F:\outputs\ChatGPT-Live-GT8D.apk`)。署名鍵(ソースのフォルダの `launcher-signing.p12` と `launcher-signing.password`)を消すと上書きできなくなる。

## 7. よくあること

- **ミニプレイヤーに曲が出ない**: 音楽アプリで一度再生する。通知へのアクセスが外れていないか確かめ、外れていなくても OFF → ON でつなぎ直す。
- **分割が全部全画面で開く**: 分割の失敗が続くと全画面に切り替わる。設定 →「許可のチェック」の分割の方式が「全画面」なら、「メーカー方式に戻す」で戻す。
- **起動直後に設定画面が黒い**: HOME に戻って設定を開き直す(原因は未特定)。
- **adb がつながらない**: ワイヤレスデバッグを有効にし直し、IP・ポートを確かめる。WSL の adb ではなく `adb.exe` を使う。
- **更新直後の最初のセットが1回だけ失敗する**: 古い中継のタスクが残っているとき。2回目からは開く。

## 8. 場面テスト(開発用)

debug 版の本体・中継が必要(release 版には場面テスト用の受け口 `DebugPanelReceiver` が無い)。セット1を「左 YouTube | 右 パネル」にし、Chrome・YouTube・Apple Music・Google マップを入れておく。

```sh
bash scripts/device-scenarios.sh                        # 全場面
bash scripts/device-scenarios.sh --with-ylauncher-home  # YLuncher を既定のホームにした場面も
bash scripts/device-scenarios.sh --only <場面名>         # 1場面(前提場面は先に流れる)
ADB='adb.exe -s <IP>:<ポート>' bash scripts/device-scenarios.sh   # 接続先の指定
```

- 実行前に設定を退避し、終了時(途中失敗を含む)に戻す。結果は `build/device-scenarios/<日時>-<PID>/summary.md`。`cleanup FAIL` があれば設定の復元に失敗しているので、報告に出る退避ファイルを確かめる。
- 場面の一覧と確認内容は `scripts/device-scenarios.sh` を参照。

## 9. 元に戻す・削除する

- **純正のホームに戻す**: 設定 →「純正ランチャーに戻す」。adb なら次のとおり。

  ```sh
  adb.exe shell cmd role add-role-holder android.app.role.HOME com.android.launcher3 0
  ```

- **前の版に戻す**: 同じ署名の旧版 APK を、本体 → 中継の順に `install -r -d` で上書きする(`-d` は版を下げる指定)。v2.x の間は設定の形式(版3)が同じなので、設定の移し替えは要らない。v1.0 まで戻す場合は [archive/install-v1.0.md §9](archive/install-v1.0.md#9-v15-から-v10-に戻す) に従う。
- **削除する**: **中継 → 本体**の順にアンインストールする。アプリ内の設定も消える。

  ```sh
  adb.exe uninstall com.gt8d.launcher.panel
  adb.exe uninstall com.gt8d.launcher
  ```
