最初に `tasks/RULES.md` を読み、従う。

# Task 191 接続保持の画面遷移テストを承認済み仕様へ移行

起動前確認済み: 初回codex-verifyは9分16秒/終了1。app DebugのXML130スイート、2,008件中失敗は下記1件のみ、error/skip0。指定の/tmpへ全XMLを保存済み。作業場所にTask190由来の版2ファイル、README/導入文書/紹介ページ/Task190記録の未コミット変更があるが、これは司令塔の既存変更なので触らない。

正式版の全テストでSettingsActivityTest.appleMusicRouteOpensConnectionSettingsが旧「アプリの終了・再起動後は再入力が必要です。」を期待して失敗した。接続保持設計の検証節ではこの文言の新仕様移行を明記し、ユーザーが設計を承認済み。Task 185ではScreen単体テストを更新したが、Activityのルート統合テストを漏らした。これは保持仕様に対応するテスト移行で、実装の不具合に合わせた期待値変更ではない。

## 変更範囲

- app/src/test/java/com/gt8d/launcher/settings/SettingsActivityTest.kt のみ。

`appleMusicRouteOpensConnectionSettings` の旧再入力文言の表示assertを、承認済みの「接続情報をこの端末に保存し、再起動後に復元します。」の表示assertへ置換する。performScrollTo/assertIsDisplayedは維持。加えて旧再入力文言のassertDoesNotExistを追加し、誤った旧案内が併記されないことも検証する。テスト名、画面タイトル/トークン欄/設定一覧項目の非存在を含む他のassert、他のテスト、共通fixtureは変更しない。アプリ本体/版/依存/ビルド設定は変更しない。

## 検証

司令塔は最初のcodex-verifyが終了してから本Taskを起動する。初回の全Debugテスト結果XMLを `/tmp/yluncher-v2.3.0-initial-tests` へ保存し、失敗が上記1件だけであることを確認する。1件以外の失敗があればこの指示だけで進めず、司令塔へ報告する。

1. 変更したSettingsActivityTestだけDebugで検証する。7テストの名前/内容を維持する。成功済みの他のDebugクラスは再実行しない。
2. 初回全テストがDebug失敗で未実行だったRelease側の全テスト、panelhost両variantの全テスト、両debugビルドを下記コマンドで1回実行する。これにより初回の成功済みDebug結果と合わせて、元の `test assembleDebug` と同じ検証範囲を完了させる。各モジュール/variantの件数・失敗・error・skipをXMLで報告する。
3. アプリのrelease APKは本体の実装/版が変わらないため再ビルドしない。テスト修正後に全Debugをもう一度流すことやcodex-verifyの重複実行をしない。別の失敗があれば検証を弱めず報告する。

## 許可コマンド

- 状態を変えない読み取り。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.settings.SettingsActivityTest'`
- `/home/munde/tools/gradle-run.sh :app:testReleaseUnitTest :panelhost:testDebugUnitTest :panelhost:testReleaseUnitTest :app:assembleDebug :panelhost:assembleDebug`

## 報告

変更差分、保持設計との対応、全assertの維持、正確なコマンドと結果/件数、未実行事項、決めたことを日本語で報告。Git/ADB/pushや司令塔の文書編集は行わない。初回codex-verifyの失敗を隠さず、修正後の対象再検証と未実行分の補完を区別する。

## 実装・検証結果

- 委譲ログ `.codex-bak/20261104-204428/`、終了0。変更は指定テスト1ファイルの3行追加・1行削除のみ。司令塔が差分/全文/実コマンド/XMLを照合した。タイトル・トークン欄・設定一覧のassert、7テストの名前、共通fixtureは維持。実装担当の追加判断なし。
- 1本目の許可コマンドを1回実行、58秒/終了0。SettingsActivityTestの7件すべて成功、failure/error/skip0。初回保存XMLと今回XMLの7つのcase IDが一致し、他の初回Debug2,001件にfailure/error/skipがないことを司令塔が再実行なしで照合した。
- 2本目の許可コマンドを1回実行、10分55秒/終了0。app Releaseは130スイート/2,008件、panelhost Debug/Releaseは各1スイート/5件、いずれもfailure/error/skip0。両Debugビルド成功。app ReleaseのXML timestampは今回の11:47:52〜11:57:07 UTCで、過去のXMLを成功結果に数えていない。
- 初回の成功結果と修正対象の再検証、未実行分の補完で既存 `test assembleDebug` の範囲を完了。variant別合計4,026件(2,008×2 + 5×2)。初回失敗や対象クラスの再実行を件数へ重複加算しない。全Debug/codex-verify/release APKの重複実行なし。
- wrapperの警告3件は、quoted --testsの正規化差1件と読み取り専用ps2件。実イベントではテストコマンドの引用符はallowどおりで、psは共通ルールの読み取り許可内。変更ファイルの逸脱なし、検査設定は変更していない。`git diff --check`も成功。
