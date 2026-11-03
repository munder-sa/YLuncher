最初に tasks/RULES.md を読み、従う。

# Task 141: 調査用の記録(Task 139)を外す

`app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt` の、Task 139 で足した「キー名に LYRICS を含む metadata の値の形の記録」(`metadata lyrics key=` を出すループと、そのための `import java.io.File`)を取り除く。Task 137 の `metadata keys pkg=` の記録と、ほかの動作は残す。

## 変えるファイル
- `app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.*'`

## 報告
- 実行したコマンドと結果、決めたこと(RULES.md 1)。
