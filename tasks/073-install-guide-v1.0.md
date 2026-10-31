# Task 073: v1.0 の導入手順書を書く

最初に `tasks/RULES.md` を読み、従うこと。**adb は実行しない。**

## 背景
YLuncher v1.0(git タグ `v1.0`。右側パネルの構成)を、GT8D に一から入れて使える状態にする手順をまとめる。読む人はこのプロジェクトのユーザー本人(Android と adb に詳しい。Windows + WSL で作業し、adb は Windows の `adb.exe`)。

## 作るファイル(これで全部)
- create `docs/install-v1.0.md`

使ってよいコマンド: なし(読み取り系のみ)。

## 書く内容(コード・マニフェスト・記録で確かめてから書く。確かめられないことは「未確認」と書く)
1. 前提: 端末(GT8D、Android 15、1920x1080、density 238)、PC 側の道具(WSL の JDK17+SDK と `~/tools/gradle-run.sh`、Windows の `adb.exe`)、ワイヤレスデバッグ(端末の再起動ごとに有効にし直す。`adb.exe connect 192.168.137.248:5555`。ポートが変わることがある)。
2. ビルド: 本体 `app`(`assembleDebug`)と中継アプリ `panelhost`(`:panelhost:assembleDebug`)。出力の APK の場所。v1.0 を作るなら `git switch --detach v1.0` から。
3. インストールの順番: **本体を先、中継を後**(署名権限 `com.gt8d.launcher.permission.OPEN_PANEL` のため。理由も1行)。`adb.exe install -r <Windows のパス>`(WSL からは `wslpath -w`)。
4. 初回の設定(それぞれ、端末の画面での操作と、adb での代わりのコマンドの両方を書く。マニフェストと `cmd`/`appops`/`settings` の実際の名前をコードで確かめる):
   - 通知へのアクセス(`MediaListenerService`。ミニプレイヤーに必要)。**入れ直すと外れることがある** → `cmd notification allow_listener com.gt8d.launcher/com.gt8d.launcher.media.MediaListenerService`
   - 使用状況へのアクセス(前面のアプリの判定に使うなら。コードで使っているか確かめる)
   - 他のアプリの上に重ねて表示(帯・幕。`SYSTEM_ALERT_WINDOW`)
   - 既定のホーム(YLuncher にするか純正のままか。v1.0 で確認した構成は「既定のホーム=純正、場面テストで一時的に YLuncher」。どちらでも動くこと、切り替えの `cmd role add-role-holder android.app.role.HOME ...`)
   - 既定のブラウザ(リンクを YLuncher の受け口 `LinkEntryActivity` で受けるため YLuncher を既定にする。開くブラウザは設定で選ぶ・既定 Chrome)
   - その他、マニフェストやコードが求める権限・初回の許可の案内(パネルの「許可が必要」)があれば全部。
5. 動作の確認(短いチェックリスト): ホームが出る、帯のスワイプでセットが切り替わる、セット1(YouTube | パネル)が分割で開く、ミニプレイヤーに曲が出る、リンクが「左のアプリ | ブラウザ」で開く、設定の開き方(ドックのアプリ一覧の長押し)。
6. 場面テストの流し方(`bash scripts/device-scenarios.sh [--with-ylauncher-home] [--only 場面名]`、結果の場所、デバッグ版の受け口 `DebugPanelReceiver` が要ること、`uiautomator dump` が GT8D では使えないこと)。
7. よくあること: ミニプレイヤーに曲が出ない(通知リスナーのつなぎ直し、再起動直後は再生歴が無いと待ち受け表示)、adb がつながらない(ワイヤレスデバッグ)、インストール中のまれなソフトリブート(10/31 に1回、原因不明)。
8. 元に戻す: 既定のホームを純正に戻す、アンインストールの順番(中継→本体)。

文体: 日本語、短い文、手順は番号つき、コマンドはコードブロック。記録として `docs/superpowers/plans/`・`specs/`・`tasks/0*.md`・`scripts/device-scenarios.sh`・`AndroidManifest.xml`(app と panelhost)・`.codex.conf` を読んでよい。

## この Task でしないこと
- コード・スクリプトの変更。adb の実行。git のコミット等。

## 報告
書いた節の一覧、「未確認」と書いた箇所、**決めたこと**。
