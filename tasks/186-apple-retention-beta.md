最初に `tasks/RULES.md` を読み、従う。

# Task 186 解除キャンセルの検証補強・保持機能の実機ベータ

Task 182〜184の36件とTask 185の55件は成功し、司令塔が差分・実コマンド・XMLを照合した。Task 185の追加UIテストはキャンセル前後のマスク表示の一致を調べるが、同じ長さの別値への変化までは検出できない。元の入力値を次の接続へ渡す契約を、合成値の実際の保存結果でも検証する。既存のassertを削除/緩和/差し替えしない。

## 変更してよいファイル

- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicLyricsScreenTest.kt`
- `app/build.gradle.kts`
- `panelhost/build.gradle.kts`

## 手順

1. UIテストへ新規 `cancelDisconnectKeepsExactManualTokenForNextConnect` を追加する。既存PersistentFixture/host/input/click/awaitPersistenceを使い、合成TOKENの保存済み接続を復元→別の合成SECOND_TOKENを入力→解除確認を開いてキャンセル→追加の入力操作をせず「接続確認」を押す。キャンセル時点で接続/暗号化保存/入力/削除backendが変更されていないこと、次の接続が保存まで成功し `store.value.token == SECOND_TOKEN` / source MANUALになること、入力破棄と秘密非出力を検証する。単にマスク表示や長さが一致するだけで合格にしない。実トークン/ネットワークは使わない。既存16件の名前・条件は変えない。
2. 本体/中継のversionCodeを42→43、versionNameを2.3.0-beta2→2.3.0-beta3へ揃える。版以外の依存/SDK/署名/最適化は変えない。鍵を表示しない。
3. 指定UIテストだけ実行する。成功した他クラス39件と基盤36件は再実行せず、変更したクラスだけ確認する。失敗時は許可範囲内の修正後だけ再実行可。アプリ本体の追加修正が必要なら報告して止める。
4. 成功後に両release APKを組み立てる。metadataの版/パッケージ/APKの存在を読む。release/R8失敗時は範囲外の修正をしない。

## 使ってよいコマンド

- 状態を変更しない読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.settings.AppleMusicLyricsScreenTest'`
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

WebKit依存はTask 185で解決済み。指定Gradleに必要な承認済み依存の解決は継続可。新しい依存やツールを追加しない。Git/ADB/インストールは司令塔が担当する。

## 司令塔が行う実機確認

- 署名/版/ハッシュを確認して本体→中継を上書き導入。直前のHOME、通知/Bluetooth、通知リスナー5件、ユーザー補助carsysoのみを比較し、SIM/回線設定は変更しない。
- 組み込み画面で利用者がAppleへログインする。入力中に画面取得やUIダンプをしない。認証情報をチャット/ADB/ログへ出さない。
- 保存表示と認証成功を確認し、プロセス再作成/上書き更新/端末再起動/解除・再取得を区別して記録する。端末再起動後に無線ADBの手動復帰が必要な場合は利用者へ依頼する。
- Apple歌詞の実採用と2曲、ホーム/パネル等のTask 179残項目を継続。実回線障害など確認できなかった場面は未確認として残す。
- 正式版/push/タグ/公開はこのTaskに含まない。

## 報告

変更、テスト件数/失敗/エラー/スキップ、正確なコマンドとXML、release結果/両版/パス、決めたこと、実機未確認を日本語で報告する。
