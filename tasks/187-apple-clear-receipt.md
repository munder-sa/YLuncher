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

## 実装・検証結果 (2026-11-04)

- `.codex-bak/20261104-172934/`終了0、許可4ファイルのみ変更。指定テスト1回で9件成功、失敗/エラー/スキップ0(53秒)。両release build1回成功(39秒)。既存8件無変更。司令塔が全差分・正確なコマンド・XML/ログを照合、再実行なし。
- 決めたこと: 登録集合へ追加した直後にCLEAREDだけIDLEへ戻す。追加テストはrunCurrent後にもbackend回数が増えないこと、解除済み登録へのclose呼出しがないことを検証する。
- 両metadata44 / beta4、apksigner verify両方成功、旧版と同じ証明書SHA-256 `44d8c612bd3948e265840f28adff0f820d5893d0ba24d888b041ca16544f7f6d`。

| APK | bytes | SHA-256 |
|---|---:|---|
| app/build/outputs/apk/release/app-release.apk | 3022454 | 61a6b0f5d6bf8f6847c4897bc014dd6e8d9c0b7f6b96567ec5368e98f5ce6cee |
| panelhost/build/outputs/apk/release/panelhost-release.apk | 2021093 | deac67098507f5180167f0f2e5917863758ae9ba8430ee6f6eef624228fbc63e |

- 利用者はbeta3でAppleログイン自体に成功したが接続が失敗と回答。設定へ戻るとTIMEOUT文言、保存未完了だった。公開サイトへの端末curlは認証なし・本文破棄でweb301/0.314秒、APIルート404/0.437秒、実Provider用us/browseは同UA/identityで200・2,455,875 bytes/2.505秒。Wi-Fiが既定/VALIDATED。設定は変更せず、原因は特定していない。
- 画面を開き直し、既存Webログインのまま「このアカウントで接続」を1回再試行したところ自動的に設定へ戻り、**接続済み(地域jp)・この端末に保存済み**を実機確認。実トークン/本文は取得していない。古い削除完了表示が併記される問題も実画面で確認できた。次はこのbeta4を上書きして復元を確かめる。
