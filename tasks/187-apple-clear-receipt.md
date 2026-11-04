最初に `tasks/RULES.md` を読み、従う。

# Task 187 再ログイン時の古い削除完了表示・beta4

司令塔の実機確認でbeta3の解除API完了を初回ログイン前の空領域で確認した。WebData.stateのCLEAREDはregister後も残るため、後続ログインでCookieを再作成しても設定に「Appleログイン情報を削除しました」が残る。過去の操作の表示を新規取得へ持ち越さないように修正する。取得/暗号化/認証/削除仕様は変えない。

## 更新してよいファイル

- `app/src/main/java/com/gt8d/launcher/settings/AppleMusicWebData.kt`
- `app/src/test/java/com/gt8d/launcher/settings/AppleMusicWebDataTest.kt`
- `app/build.gradle.kts`
- `panelhost/build.gradle.kts`

## 実装

- registerが受理され、新しいログイン画面を登録する際、直前状態がCLEAREDならIDLEへ戻す。CLEARING中はこれまで通り登録拒否し、その状態を維持。FAILEDを勝手に成功/IDLEへ変えない。backendの呼出し/WebView初期化を追加しない。
- 既存8テストの名前/条件/期待値は変更せず、追加1件 `newLoginClearsPastDeletionReceiptWithoutStartingBackend` で実際のclear→callback→CLEARED→register→IDLEを検証。次のclearのcallback待機中はregisterが拒否されCLEARING維持、完了後の再登録もIDLE、backendの呼出回数がregisterで増えないことを確認。登録の解除も行う。
- 両moduleをversionCode43→44、2.3.0-beta3→2.3.0-beta4へ揃える。版以外のGradle項目は変えない。
- 変更したWebDataクラスのテストだけ実行し、成功後に両release APKをビルドする。既存UI/ストア/Session/Repositoryを重ねて実行しない。
- 新規依存、署名変更、秘密情報の表示、ADB、Git、インストールは禁止。利用者がbeta3の実機でログイン中なので、インストールは利用者回答後に司令塔が行う。

## 使ってよいコマンド

- 状態を変更しない読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.settings.AppleMusicWebDataTest'`
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

成功後の再実行なし。失敗を修正した時だけ再実行可。範囲外の変更が必要なら報告して止める。

## 報告

差分、実コマンド/テスト件数/失敗/エラー/スキップ、両APKの版と存在、決めたこと、実機未確認を日本語で報告する。
