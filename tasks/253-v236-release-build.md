最初に `tasks/RULES.md` を読み、従う。

# Task 253: 正式版 2.3.6 の APK 作成

beta1 の実機確認が済んだので正式版にする。次の2ファイルの defaultConfig だけを **2.3.6-beta1 / 67 → 2.3.6 / 68** にする。

- `app/build.gradle.kts`
- `panelhost/build.gradle.kts`

アプリ・テスト・依存・署名・R8/ビルド設定・文書は変えない。署名鍵や keystore.properties を表示しない。下の release コマンドを1回だけ実行し、成功したら繰り返さない。テスト・assembleDebug・ADB・Git は実行しない。

## 使ってよいコマンド
    /home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease

## 報告
- 版の差分、コマンドと結果(時間)、APK のパス、決めたこと
