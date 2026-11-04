最初に `tasks/RULES.md` を読み、従う。

# Task 188 初回接続タイムアウトの安全な段階診断・beta5

beta3初回接続とbeta4更新直後の保存値復元でTIMEOUTが発生し、いずれも待機後の1回再試行でjp接続に成功した。beta4ではKeystoreからの復号/保存表示と、再入力なしの再接続を実機確認済み。端末curlの公開us/browse(同UA/identity)は200・約2.46MB・2.5秒、APIルートは404・0.44秒だった。原因を決めつけず、接続内の処理段階を固定情報だけ記録して切り分ける。

## 更新可能

- app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsClient.kt
- app/src/main/java/com/gt8d/launcher/media/lyrics/AppleWebTokenProvider.kt
- app/src/main/java/com/gt8d/launcher/AppGraph.kt
- app/build.gradle.kts
- panelhost/build.gradle.kts

## 新規作成可能

- app/src/main/java/com/gt8d/launcher/media/lyrics/AppleConnectionTrace.kt
- app/src/test/java/com/gt8d/launcher/media/lyrics/AppleConnectionTraceTest.kt

既存Client/Providerテストと他のソースは読み取りのみ。時間切れの値、再試行/キャッシュ/通信ヘッダー/URL/解析/保存/キャンセルの仕様は変更しない。原因未特定のまま制限を緩めたり自動再試行を追加しない。

## 診断の契約

- 新helperをinternalに置き、DiagLogのtag `AppleConnect` へ固定の段階名と経過msだけを出す。必要に応じHTTP code(100〜599)、本文bytesの件数(上限16MiB)、asset番号(1〜8)、列挙型の固定結果理由を追加してよい。任意文字列/URL/ヘッダー/response/exception/toString/トークン/歌詞/アカウント情報をhelperへ渡せるAPIにしない。ログ文の文法は報告する。
- 既存Clientテストの空ログ契約を維持するため、Client/Providerのconstructor末尾に `diagnosticsEnabled: Boolean = false` を追加し、falseではtrace生成/出力を行わない。AppGraphの既存生成2箇所だけ明示trueへ配線する。他のAppGraph処理は変えない。これは既存テストの期待値変更を必要としない追加機能とする。新helperテストにはtrueの合成Client/Provider接続の段階順/非漏洩も含め、falseの既存17件と有効時の両方を検証する。
- operationはCONNECT/PUBLIC_REFRESH、段階はenum等の閉じた集合。経過時間はSystem.nanoTime等の単調時間差、テスト用時計/sinkを差し替え可能にする。helper自身のtoStringにも秘密を持たせない。sinkの例外で処理結果/キャンセルを変更しない。待機/IO/追加Job/NonCancellableを診断へ入れない。
- Client.connectに開始/終了(READYか固定失敗理由)、公開Bearer取得の前後、アカウントAPI要求の前後を記録する。requestMutex待機前の開始と取得後を区別できること。取消時も固定CANCELLEDだけ記録し元の取消契約を保つ。fetchの通常キャッシュ呼出しごとには診断を追加しない。private authenticatedRequestへ任意traceを渡す等でconnectだけに限定してよい。
- Providerは実refreshの時だけ記録。公開ページ要求の前後、ページ内トークン解析の前後、asset一覧解析の前後、各asset要求/トークン解析の前後、終了/取消を区別。ホスト名やURL文字列自体は出さず番号で区別する。cached getにはログを追加しない。ネットワークと解析の時間を切り分けられること。
- ログ保持は既存DiagLog上限500行を利用し、新しいファイル等へ保存しない。新たな実通信はテスト中に行わない。
- 新helperのテストで、固定文法/数値範囲・経過時間・任意文字列を受けない型・sink例外時にも処理が壊れないことを検証。既存Client/Providerの全17件は無変更で実行し、元の非漏洩/期限/取消等を維持する。ログの追加で既存テストが矛盾したら期待値を変えず報告する。
- 両moduleを44→45、beta4→2.3.0-beta5へ揃える。Gradleは版以外変更しない。署名情報を表示しない。

## 実行可能コマンド

- 状態変更のない読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.AppleConnectionTraceTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsClientTest' --tests 'com.gt8d.launcher.media.lyrics.AppleWebTokenProviderTest'`
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

成功後の再実行なし、失敗修正後だけ再実行可。全テスト、ADB、インストール、Git、新規依存/ツール取得は不可。司令塔が差分・ログの秘密非出力をレビューし、実機へ更新して初回復元の段階時間を確認する。

## 報告

変更/診断文法、元のタイムアウトと取消契約の維持根拠、正確なコマンド/件数/失敗/エラー/スキップ、両APKの版/存在、決めたこと、実機未確認を日本語で報告する。

## 初回委譲と再開判断

- `.codex-bak/20261104-174719/`は既存ClientTestの空ログassertとの矛盾を報告し、変更/テスト/ビルドなしで終了0。司令塔も該当assertを確認した。
- 期待値の変更は不要。診断を既定OFFの明示オプションとして追加し、AppGraphの実機用構成でONにする。許可にAppGraphの2生成箇所だけ追加し、既存17テストは無変更を維持。新規テストでON時の診断を確認する。根拠のない仕様緩和やタイムアウト延長は行わない。
