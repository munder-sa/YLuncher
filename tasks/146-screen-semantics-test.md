最初に tasks/RULES.md を読み、従う。

# Task 146: ScreenSemanticsTest の期待を、ユーザー補助に渡る木(マージした木)で確かめる形に直す

`app/src/test/java/com/gt8d/launcher/ui/theme/ScreenSemanticsTest.kt` の `clearsChildrenWhenNotDetailed` が落ちる(59 行の `onNodeWithTag("inner", useUnmergedTree = true).assertDoesNotExist()`)。`clearAndSetSemantics` で隠した子は、ユーザー補助に渡る木(マージした木)からは消えるが、テスト用のマージしない木には残る(Task 144 でも同じ挙動)。本番の目的はユーザー補助に渡る木を小さくすることなので、確かめ方をそちらに合わせる。

## 変えるファイル
- `app/src/test/java/com/gt8d/launcher/ui/theme/ScreenSemanticsTest.kt`

## 変更
- `clearsChildrenWhenNotDetailed` で、`onNodeWithTag("inner")`(既定=マージした木)と `onNodeWithText("中の文字")`(マージした木)が存在しないことを確かめる。contentDescription「パネル」の節が1つあることは今どおり。マージしない木についての確かめは外す(本番の目的と関係ないため)。
- `keepsChildrenWhenDetailed` は今どおり。

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.theme.ScreenSemanticsTest'`

## 報告
- 実行したコマンドと結果、決めたこと(RULES.md 1)。
