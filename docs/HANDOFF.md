# YLuncher 共有引き継ぎ

最終更新: 2026-11-04 (JST)

## 現在の担当と合意

- **担当: Codex(暫定司令塔)**。2026-11-04 にユーザーがこの引き継ぎを行っている親セッションを指名し、移行方式を承認した。
- 期間: 今週の代行。ユーザーが Claude のトークン復活後に復帰を伝えたら交代する。日付による自動交代は行わない。
- この記録を読むだけで別の Codex が司令塔になるわけではない。新しいセッションを司令塔にする場合は、ユーザーが直接指名し、既存の担当との同時作業を避ける。
- 司令塔と実装担当の権限・作業手順・復帰手順は [AGENTS.md](../AGENTS.md)、Claude の入口は [CLAUDE.md](../CLAUDE.md)。
- 合意済みの作業はレビュー・必要な検証後にコミットして報告する。push・公開は対象についての承認に従う。
- 2026-11-04 の追加指示: 読み取りのみで状態の変更を伴わないコマンドは一律許可。毎回ユーザーへ相談せず進める。実行環境の権限制限は別途従う。`AGENTS.md` に反映済み。

## 作業状況と次の一手

- 暫定司令塔への運用移行は `980964b` で完了(ローカル、未push)。対象3ファイルのステージ後の `git diff --cached --check` も終了コード0。
- 現在の依頼は**軽い負荷軽減を、暫定司令塔の運用で試すこと**。Task 173 [ジャケット色抽出のコピー削減](../tasks/173-artwork-sampling-copy.md)は実装・対象テスト・司令塔レビューまで完了。既にARGB_8888の入力は元サイズで複製せず直接縮小し、他のconfigは従来の変換経路を保つ。
- 作業ブランチは `perf-artwork-copy`。指示書・allow は `af5af7a`、実装は `6a2c20c`。main への取り込み・pushは行っていない。
- ユーザーが実機へのインストールと確認を依頼し、[Task 174](../tasks/174-artwork-device-beta.md)で**2.2.1-beta1 / 40を本体・中継とも実機へ上書き導入済み**。指示書は `4ca8607`。ビルド・画面・ログ・権限の確認も完了し、詳細は末尾のTask 174の結果に記録した。
- 実機 `192.168.137.248:5555` の導入前は本体・中継とも2.2.0 / 39。導入後も既定HOMEはYLuncher、通知リスナーと通知・Bluetooth権限は許可済み。ユーザー補助はcarsysoとYLuncherの両方を維持。確認終了時はホーム画面、Apple Music「怪獣」は一時停止、パネルの表示設定は元の「セット」に戻した。
- 初回セット1起動で「開くのに時間がかかっています」が表示されたが、YouTubeとパネルの分割は成立した。画像処理の確認に加えてこの観察を記録した。分割の全場面テストや、待ち通知の原因切り分けは行っていない。
- 今回の変更だけで描画速度が何ms改善したとは報告しない。
- 着手前の `ps -eo pid,ppid,etime,args` の検索では、codex-run / codex exec / Gradle の実行中プロセスは見つからなかった。追跡済みファイルの未コミット変更はなく、既存の未追跡4ファイルはそのまま。

## 移行時に確認した Git の状態

- 2026-11-04 のローカル確認: `main` / `HEAD` = `3a15ac0`、タグ `v2.2.0-release`。ローカルの `origin/main` も同じコミット。リモートへの再照会はしていない。
- 追跡済みファイルの未コミット変更は、移行着手前にはなかった。
- 移行前からある未追跡ファイルは次の4つ。今回の運用移行の変更・コミットには含めない。
  - `.mcp.json`
  - `scripts/apple-lyrics-probe.py`
  - `tasks/170-apple-lyrics-web-probe.md`
  - `tasks/170-apple-lyrics-web-probe.allow`
- 歌詞検証の3ファイルはユーザーが別の Codex で進めた作業。今後それに着手する場合は、この3ファイルの現状も確認してから範囲を定める。

## 完了済みのアプリ開発

- v2.2.0 / versionCode 39: 歌詞の構築範囲を可視・出入りする行に限定し、明暗処理・重ね文字の Offscreen を削減。アプリ情報の非同期取得と共有。本体(app)の R8・資源縮小。profileinstaller 1.3.1 と手書きのベースラインプロファイル。
- Task 165〜169 の実装と、Task 172 の非同期取得を待つレイアウトテスト修正は Git 履歴に存在する。アプリ基準のマージコミットは `3a15ac0`。
- Claude の最終会話・引き継ぎ記録では、v2.2.0 の全テスト **3,704件成功**、push、[GitHub リリース](https://github.com/munder-sa/YLuncher/releases/tag/v2.2.0-release)、APK 2つと SHA256SUMS の添付まで完了。
- 同記録では実機も release 2.2.0、プロファイル適用済み。ホーム復帰 120→83ms、doFrame p90 14.2→11.3ms と報告されている。
- **上記のテスト・公開・実機状態・性能値は過去の記録からの引き継ぎであり、今回の移行で再実行・再測定していない。**
- 設計・計画: [負荷軽減の設計](superpowers/specs/2026-11-04-perf-design.md)、[負荷軽減の計画](superpowers/plans/2026-11-04-perf.md)。導入: [v2.2.0 を含む導入手順](install-v1.6.md)。

## 別作業の Apple Music 歌詞検証

- [検証指示書と結果](../tasks/170-apple-lyrics-web-probe.md)には、Lemon(カタログ ID `1537460612`)で認証ありの単語同期 TTML を取得できたと記録されている。HTTP 200、40行、開始・終了時刻付き span 277個。
- 認証なしでは曲情報200、歌詞エンドポイント404。ユーザーが PC の非表示ダイアログへ Web 用トークンを入力した検証は成功。認証情報・歌詞本文は記録に転記しない。
- 本体への統合、他曲・別地域、トークン更新、既存 Kotlin TtmlParser と描画への実データ入力は未確認。今回この検証を再実行していない。
- Task 番号170は負荷軽減計画内の統合・実機確認にも使われている。参照時は番号だけでなくファイル名と作業名を併記し、新規指示書を作る前に番号の重複を確認する。

## 保留候補と運用上の知見

- 性能改善の残り: MediaWatcher のセッションごとの更新、SetRunner の履歴照会の非同期化、利用履歴の集計頻度、非表示時のタイマー停止。ArtworkBackground の元サイズのコピーは Task 173 でARGB_8888だけ削減し、他のconfigは維持した。
- AMLL 索引の世代に応じた負キャッシュ無効化は延期中。
- Gradle は `/home/munde/tools/gradle-run.sh`。対象テストは `:app:testDebugUnitTest --tests '…'` の形で指示書・allow に完全なコマンドを書く。Gradle は同時2本まで。同じテストを実装担当と司令塔で重ねて流さない。
- 実機確認用は `<次の正式版>-betaN`、確認後に正式版。app と panelhost の版・署名を揃え、本体→中継の順に導入する。
- 実機接続は Windows の `adb.exe -s 192.168.137.248:5555` で確認済み。通常サンドボックスではWSLのソケットエラーが出たため、承認された権限で実行している。再起動後はワイヤレスデバッグの再有効化が必要。
- 再インストール後は通知アクセスを確認する。ユーザー補助の既存サービス(carsyso と YLuncher)を失わないよう、変更前の状態を確認する。
- このセッションの `.git`・`.codex` は読み取り制限の対象。役割の記載だけで実行権限が広がるわけではない。Git 更新や `codex-run` の実行時は必要な権限を確認する。

## 移行作業の記録

- 変更内容: 役割ごとの範囲、暫定交代と復帰の手順、共通の引き継ぎ先を整備。
- 検証: `git diff -- AGENTS.md CLAUDE.md` と `cat docs/HANDOFF.md` で内容をレビューし、実装担当への司令塔権限の継承がないこと、旧来の Git 禁止と新しい分担が矛盾しないことを確認した。参照先のローカル文書も `ls` で存在を確認した。
- `git diff --check`: 終了コード0。`git diff --no-index --check /dev/null docs/HANDOFF.md`: 空白エラーの出力なし(新規ファイルとの差分があるため終了コード1)。コミット前に `git diff --cached --check` で3ファイルを確認する。
- ビルド・アプリのテスト: 文書だけの変更のため実行なし(実行件数0、失敗件数は該当なし)。過去の成功件数を今回の実行結果として扱わない。
- この移行のコミットを探す場合は、件名 `docs: Codex 暫定司令塔への移行と共有引き継ぎを整備` の Git 履歴を参照する。公開・実機導入はこの文書変更では行わない。
- 決めたこと: 日付による自動交代は行わず、ユーザーの復帰指示と実行中の処理の整理を交代の条件とした。検証は文書の差分・参照先・空白検査に絞った。

## 更新と交代の記録方法

作業の区切りごとに「現在の担当」「作業状況と次の一手」を更新し、Task・変更理由・実行コマンド・テスト件数/失敗件数・ログの場所・コミット・実機の版・未確認事項を残す。古い状態は履歴と分かる形で扱う。

Claude への復帰時は、実行中の処理と未コミット変更を整理してから担当を「Claude への引き継ぎ待ち」にする。Claude が実際の Git 状態と照合した後、担当を「Claude」に更新する。

移行元の記録: `/home/munde/.claude/projects/-mnt-f-YLuncher/memory/progress-handoff.md` と同ディレクトリの `MEMORY.md`、`commit-after-verify.md`、`release-beta-first.md`、Claude の最終会話。今後の進捗は本ファイルを更新する。

## Task 173 の結果(2026-11-04)

- 実装・テストの変更: `app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt` の2ファイル。既存テストは削除・変更せず、3件追加。
- `codex-run tasks/173-artwork-sampling-copy.md`: 終了コード0。ログ: `.codex-bak/20261104-120418/` の `events.jsonl`・`last-message.md`・`stderr.log`。実装担当が司令塔の権限を継承せず、指定範囲で完了した。
- テストは実装担当が次のコマンドを**1回**実行し、BUILD SUCCESSFUL。司令塔は同じテストを再実行せず、実行ログとXMLを照合した。

```sh
/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest' --tests 'com.gt8d.launcher.ui.theme.ArtworkColorsTest'
```

- 結果: ArtworkBackgroundTest 19件 + ArtworkColorsTest 8件 = **27件成功、失敗0・エラー0・スキップ0**。XMLは `app/build/test-results/testDebugUnitTest/TEST-com.gt8d.launcher.ui.home.ArtworkBackgroundTest.xml` と同ディレクトリの `TEST-com.gt8d.launcher.ui.theme.ArtworkColorsTest.xml`。
- 司令塔の検証: `git diff -- app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt` でレビュー、`git diff --check` は終了コード0。縮小方式・色計算・例外処理・元画像を recycle しない後始末・鍵とキャッシュは維持されている。
- `codex-run` はコマンド照合の警告を1件出した。`events.jsonl` の実コマンドは指示書と同じ引用符付き。`/home/munde/.local/bin/codex-run` の `split_commands` が shlex で引用符を除去し、`is_cmd_allowed` が引用符を含む allow の文字列と比較するための不一致と確認した。実コマンドの逸脱ではない。共通スクリプトの修正は今回行っていない。
- `perf/artwork-copy` というブランチ名は既存の `perf` と衝突して作成に失敗(終了コード128)。`perf-artwork-copy` にして作成・指示書コミットが成功した。
- 確認した削減: ARGB_8888の色抽出で元サイズの `copy` を呼ばない。1024×1024なら画素約4MiB分のコピーを省く計算。実機の抽出時間・メモリ使用量・操作感は未測定。アプリの版は2.2.0 / 39のまま。

### 決めたこと

- 最初の小規模実験として、独立した色抽出関数のコピー削減を選んだ。ARGB_8888だけを対象にして、他のconfigの色変換を維持した。
- テストは画像抽出・色計算の2クラスに限定し、全テスト・リリースビルド・実機操作は行わない。
- 実験ブランチ名は既存名と衝突しない `perf-artwork-copy` にした。
- 実装担当はローカル変数 `source` を追加し、既存の `copy` / `sampled` の所有と後始末を維持した。
- テスト寸法はARGBが96×48・48×96、小さいimmutable ARGBが16×8・32×16、RGB_565が65×37・16×8。
- 固定パターンはARGB値 `FFFF4000・80FF4F00・C020B060・FF3050D0・7FFF00FF・00000000` を `(x / 7 + y / 5) % 6` で配置。
- immutable画像は赤一色をコピーして作り、既存の期待色 `red` と2回照合した。
- テスト専用の `patternedBitmap`・`readPixels`・`previousSampling` を追加。元画像の全画素は呼び出し前の読み出し値と比較し、生成物を finally で後始末する。参照コピーの失敗は requireNotNull でテストを失敗させる。

## Task 174 の結果(2026-11-04)

- `codex-run tasks/174-artwork-device-beta.md`: 終了コード0、逸脱なし。ログは `.codex-bak/20261104-121656/`。実装担当の変更はapp/panelhostの `build.gradle.kts` の版名・版コードだけ。司令塔は差分と両APKの `output-metadata.json` を照合した。
- ビルドコマンド `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`: BUILD SUCCESSFUL(1分3秒)。版だけの変更なので単体テスト再実行なし。Task 173の27件成功を継承し、今回の実行件数には数えない。
- 以下を本体→中継の順に実行し、どちらも `Success`。パッケージの読み取りでも両方40 / 2.2.1-beta1を確認した。

```sh
adb.exe -s 192.168.137.248:5555 install -r 'F:\YLuncher\app\build\outputs\apk\release\app-release.apk'
adb.exe -s 192.168.137.248:5555 install -r 'F:\YLuncher\panelhost\build\outputs\apk\release\panelhost-release.apk'
```

- 権限の前後: `shell dumpsys package com.gt8d.launcher` / `com.gt8d.launcher.panel`、`shell settings get secure enabled_accessibility_services` / `enabled_notification_listeners`、`shell cmd role get-role-holders android.app.role.HOME` を導入前後に照合。版以外は署名の表示・通知/Bluetooth権限・ユーザー補助の2サービス・通知リスナーの一覧・既定HOMEを維持していた。権限の再設定は不要だった。
- `adb.exe -s 192.168.137.248:5555 shell cmd package compile -r bg-dexopt com.gt8d.launcher`: `Success`。`dumpsys package` のDexopt stateは `status=speed-profile` / `reason=cmdline`。
- ホームのセット3件・アプリ列が導入前後で維持されたことを画面で確認。Apple Musicを開いて「怪獣」を再生し、導入前後でホームの背景を比較。ベータでは同じ曲に戻したときの色を確認し、セット1(YouTube | パネル)のメディアカードでも同じジャケットと背景を確認した。
- 色抽出ログ: `adb.exe -s 192.168.137.248:5555 logcat -d -s 'GT8D:D' '*:S'` を取得し、Artworkの行を確認。旧版「怪獣」も新版「怪獣」も `300x300 config=ARGB_8888`、抽出色は **#2C3338 / #161A1C** で一致。
- 記録上の抽出時間は旧版「怪獣」23ms、新版初回の「怪獣(Cornelius Remix)」135ms、その後の「怪獣」1ms。曲・初回/後続・プロファイル適用時点の条件が揃っていないため、速度改善の比較結果とは扱わない。300×300のARGB画像で省く元サイズコピーは画素360,000bytes相当の計算であり、実測メモリ使用量ではない。
- ホーム→パネル→ホームでもプロセスIDは26699のまま。取得したそのプロセスの `AndroidRuntime:E` / `libc:F` / `DEBUG:F` ログは出力なし。確認範囲で色抽出の失敗ログもなかった。
- 初回セット1起動で待ち通知あり。12:28:37にStockWarmupのtimeout、12:28:41にcurtain Safety / timeout not counted: progressというログ。最終的に画面上の分割は成立し、パネルのメディアカードを確認できた。今回の変更は分割経路に触れておらず、通知原因は今回切り分けていない。
- 画面の一時保存: `/tmp/yluncher-174-before.png`(再生前ホーム)、`/tmp/yluncher-174-baseline-art.png`(旧版の背景)、`/tmp/yluncher-174-after.png`(新版で同じ曲の背景)、`/tmp/yluncher-174-panel.png`(新版のメディアカード)。画面は目視確認のみでピクセル差分比較は行っていない。
- 確認終了時はパネル設定を「セット」に戻し、YLuncherホームへ戻った。Apple Musicは「怪獣」の先頭で一時停止。音量・認証情報・端末の再起動・アプリデータ消去は操作していない。
- mainへの取り込み、タグ、push、正式リリースは行っていない。実機で触った操作感のユーザー確認や、正式版への昇格は次の判断事項。

### 決めたこと

- 小規模修正の実機確認用に2.2.1-beta1 / 40を採用し、両モジュールを揃えた。
- 元の署名・release設定で本体→中継を `install -r` し、設定を消さずに更新した。
- 既存ライブラリの「怪獣」で色を比較し、既存セット1でパネルも確認した。パネル表示は一時的にアプリへ切り替え、確認後にセットへ戻した。
- 前の版と同様にbg-dexoptでプロファイルを適用した。起動状態が異なる抽出時間を改善率として扱わない。
- UIの証跡は `/tmp/yluncher-174-*.png` に置き、認証情報や歌詞本文を報告文には転記しない。
