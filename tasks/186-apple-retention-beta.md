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

## 実装・APK確認結果 (2026-11-04)

- `.codex-bak/20261104-171515/`終了0。許可3ファイルだけ変更、既存16テストは無変更。指定UIテスト1回で17件成功・失敗/エラー/スキップ0、1分29秒。releaseコマンド1回で成功、1分5秒、R8/資源縮小/lintVitalを含む。司令塔が差分・引用符を含むコマンド一致・結果XML/ログを照合、再実行なし。
- 両APKのmetadataは43 / 2.3.0-beta3。SDK35のapksigner verifyは両方終了0、証明書SHA-256は旧版と同じ `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d`。並列Java実行時のhsperfdataロック警告はあったが署名検証は成功。
- aapt2でrelease APK内の非exported Activity、backup参照、legacy/cloud/device-transferの各除外5件を照合。由来assetsは14,006 bytesで同梱。通常設定全体を除外する規則なし。

| APK | bytes | SHA-256 |
|---|---:|---|
| app/build/outputs/apk/release/app-release.apk | 3022454 | 5ac521c9c0addb88a0d7489406f33678b25aebb8219a5e6e2564465842567133 |
| panelhost/build/outputs/apk/release/panelhost-release.apk | 2021093 | 84f8abd8a6efaac433e87c63bc2bb335974bda8aa4b447058a03483d84f9496a |

- 決めたこと: キャンセル前後の状態全体・保存オブジェクト・呼出回数・入力を照合。再接続は呼出計2回かつCONNECTED/SAVEDまで待ち、SECOND_TOKENとMANUALの保存・入力破棄・接続ボタン無効化を検証。ログは合成値を入力する前に初期化し、入力/確認/キャンセル/保存の各段階で非出力を確認。実機導入・保持はこの時点では未確認。
