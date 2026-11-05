# 歌詞描画の負荷軽減 実施計画

2026-11-05。対象はv2.3.0 / 47。依頼範囲は計画作成。[設計](../specs/2026-11-05-lyrics-render-cost-design.md)と[現状分析](../../performance/2026-11-05-bottlenecks.md)に基づく。以下のTask番号・ファイル・コマンドは将来の指示書/allow候補であり、まだ実行しない。

## 実施順

| 予定Task | 担当 | 内容 | 完了条件 |
|---|---|---|---|
| 192 | 司令塔 | 比較区間、画面配置、版/プロファイル条件、基準値を固定 | 同一条件の測定が成立し、除外規則と記録様式を確定 |
| 193 | codex-run→司令塔レビュー | 前奏/間奏の点灯数不変時の描画無効化を抑制 | 表示・時刻・フェードの互換、不要draw削減、対象テスト成功 |
| 194 | codex-run→司令塔レビュー | 背景だけの独立層/再利用を比較 | 背景色/座標不変、CPU/メモリの比較から採否を判断 |
| 195 | codex-run→司令塔レビュー | 単語マスクのShader試作、成立した場合だけ統合 | 旧方式との画素比較、更新/寿命、GT8Dの描画時間で採否を判断 |
| 196 | 司令塔＋版変更のみcodex-run | 各候補のbeta作成/比較、最後に採用変更の統合 | 人工ケース・実曲・利用者の見た目確認、目標達成/未達を明記 |

同じ描画コードを共有するため直列に進める。着手時に既存Task番号との重複、実Git状態を確認し、**実装担当には各Taskの変更可能ファイルとコマンドを具体化したmd/allowを渡す**。本計画自体を広い変更許可として渡さない。192で基準を整えた後、193を最初の実装にする。

196の版変更/ビルド/比較手順は、193〜195の各実機評価時にも使う共通工程。最後まで実機比較を先送りしない。最初の候補からbeta名を付け、版変更の指示書にはその回の正確な版名/コードを明記する。実機で不採用と決めた変更を次候補の基準へ混ぜない。

## Task 192: 測定の条件を固定

アプリコード変更なし。司令塔が、最新版Git/実機/画面ID、単一の可視Home、30Hzと仮想60Hzの区分、scrcpy設定、音声転送、温度、アプリ状態を確認する。IDやPIDは毎回読み直し、6/9825を固定しない。

- 既存 `/tmp/yluncher-perf-sample.py` を参考に、計測開始/終了の全PlaybackState、曲ID、position/updated/speedと端末時計を記録する手順を文書化する。前回の不足（曲名だけの判定、BUFFERING等の状態の取りこぼし）を引き継がない。
- 基準は単語が動くLemonの30秒区間。前奏/間奏・行送り/折り返しは別区間として位置を決める。ネットワーク取得済みの状態から測定する。前回の約35%は参考であり、新betaの比較条件が違えばそのまま分母にしない。
- 基準も候補も同じrelease最適化/署名/プロファイル状態で測る。R8なしのdebugと正式APKを比較しない。APKごとのコード差分と計測条件を記録し、計測専用ログ/フックを使う場合は両方へ同条件で入れ、採用版では除去する。
- `/proc`差分、gfxinfo、meminfoを通常窓で取得し、atrace/simpleperf/転送は別枠にする。復帰/曲替え直後の準備時間を定常CPUへ混ぜない。

将来の司令塔コマンドは `adb.exe devices -l`、対象serialの`dumpsys display/activity/media_session/gfxinfo/meminfo`、`/proc`取得、`atrace`、`simpleperf`。画面操作/シークは司令塔だけが行う。保存するのは統計と固定メタデータで、トークン/歌詞本文を採取しない。

## Task 193: 変化しない点灯状態の描画を抑制

将来の変更候補（基点 `app/src/`）:

- `main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- `test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`

手順:

1. 現在の点灯時刻、非現在行の保持、フェード、前奏終了を参照テストで固定する。新テストは点灯数が不変の区間でdrawが増えないことと、変化時には正しく描かれることを対にする。
2. 設計§4の状態依存だけを分離。単語の塗りモデル、200msの行判定、frame clock、LyricMotionは変更しない。詳細semantics経路と通常の可視窓経路を両方扱う。
3. 対象試験は `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`。実装担当の成功結果を司令塔がXML/差分と照合し、同じ試験を理由なく再実行しない。
4. 司令塔が前奏/間奏と単語区間を別に実機比較する。点の描画削減を単語区間の性能改善と誤認しない。

## Task 194: 静止背景の独立化を実験

変更候補:

- `main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`
- `test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`
- 必要な描画専用helperのみ `ArtworkBackground.kt` / `ArtworkBackgroundTest.kt`（触る必要を司令塔が確認してからallowへ追加）。色抽出/共有holderは対象外。

手順:

1. 基準の人工背景で角丸、全域のグラデーション座標、余白、タッチ領域、歌詞/ジャケット切替を検証する。
2. 背景だけを兄弟要素として分離する候補を比較。ラスタ保持を試す場合も背景だけとし、設計§5の寿命/8MiB予算を守る。
3. 初期試験コマンドは `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.HomeMiniPlayerTest'`。helperを変更する場合だけ `--tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest'` を追記した完全なコマンドをallowへ書く。
4. 各方式を193合格後の同じ基準に対して測定し、採用/不採用を記録。背景層が増えても効果がなければ統合しない。色抽出アルゴリズム変更で性能差を作らない。

## Task 195: 単語マスクの成立確認と候補実装

変更候補:

- `main/java/com/gt8d/launcher/ui/home/LyricsPane.kt`
- 新規候補 `main/java/com/gt8d/launcher/ui/home/LyricWordBrush.kt`
- `test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt`
- 新規候補 `test/java/com/gt8d/launcher/ui/home/LyricWordBrushTest.kt`

最初の委譲は小さい成立確認に限定する。旧描画はテスト側の参照として残し、旧テストの期待値を実装に合わせて変えない。

1. 白いマスクの境界/折り返し消去と、実際の人工文字の両方を旧方式と照合。density 1/2および実機238dpi、1行/2行、0/途中/末尾、xの端、RTL/結合文字/絵文字/ellipsis、旧行フェードを含める。
2. Shader更新がdraw段階で反映され、再構成/計測/配置を増やさず、毎フレームコンパイル/割当しないことを確認する。ソフトウェア/Native Graphicsで検証できる範囲とGPU実機確認を区別する。Robolectricで未対応な部分をIgnoreして通さない。
3. 成立した場合だけ現在の単語overlayへ接続する。基底TextとTextLayoutResult、emphasisの合成順、語の時間計算、全体Offscreenを維持する。
4. 将来の対象コマンドは `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricWordBrushTest' --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`。新規クラスの名前を確定してから実際のallowを作る。
5. 画素/更新/実機のどこかで不成立なら旧方式を維持し、確認できないこと・代替案・推奨を報告する。上下端マスクの置換、全行Canvas化、新ライブラリ導入へ拡張しない。

## Task 196: 統合betaと採用判断

- 採用候補を一つずつコミット/比較し、最後に有効な変更だけをまとめる。不採用候補は取り込まない。共有作業場所でcodex-run実行中に司令塔が別編集をしない。
- 実機確認用の版は予定 **2.3.1-beta1 / 48**。後続betaは必要な時だけ番号/コードを進め、着手時に実際の配布履歴と衝突しないか再確認する。app/panelhostの版を揃える。版変更とビルドは別指示書で委譲する。
- 将来のビルド候補は `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`。同じ署名の上書き更新と設定/接続保持の確認、実機ADBは司令塔が行う。候補ごとのAPK・SHA・条件を残し、実装差以外のビルド設定を揃える。
- 固定区間3組のCPU/RenderThread/フレーム/メモリに加え、停止→再開、前後へのシーク、曲替え、連続行送り、幅変更、ホーム/パネル、歌詞ON/OFF、Plain/Loadingを確認する。仮想画面の分割起動は通常画面と分ける。
- 既存のLyricsPane/LyricWindow/LyricMotion/HomeMiniPlayerの契約、背景色/コントラストは維持。変更した経路の試験を工程ごとに実行し、全テストは正式版準備に進む場合だけ最後に1回まとめる。試験の削除/弱化はしない。
- 表示と操作感を利用者にも見てもらう。効果、未達、未確認を区別し、単語区間20%目標に未達ならその値を報告する。前奏だけの改善を目標達成としない。
- 正式版名・最終versionCode、main/tag/push/GitHub公開は成果と依頼範囲を照合する別工程。本計画作成の依頼から公開までを承認済みと扱わない。

## この計画作成時の検証と決定

実施したのはAGENTS/RULES/HANDOFF/Git状態、現在の描画コード・関連テスト・ビルド設定の読み取り、Android公式API資料の確認、計画文書の編集と整合確認。アプリ/テスト/版は未変更、実機操作・ビルド・テスト・インストール・委譲・pushは0件。

決めたこと: 実装は直列、予定Task192〜196、初手は計測条件固定と点灯数の描画依存分離。背景層とShaderは比較してから採用。性能/見た目の基準と未確認事項は設計書に固定し、最終版への移行は別工程とする。
