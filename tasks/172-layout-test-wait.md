最初に tasks/RULES.md を読み、従う。

# Task 172: LayoutMatrixTest の appList_survivesWidthChanges を、裏の取得の完了を待つ形に直す

v2.2.0 の全テストで `HomeResizeStateTest > appList_survivesWidthChanges`(`app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt` 469〜492 行)が落ちた(485 行 `onNodeWithTag("dock_applist")` が見つからない)。Task 167 で、ホーム・パネルのアプリ情報は裏で取り、初回は一覧と必要なアプリ情報が揃ってから表示する作りに変えたため、テストの開始直後にはドックがまだ組み立てられていない。

## 直すこと
- このテストで、`dock_applist` を押す前に、それが現れるまで待つ(`rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTag("dock_applist").fetchSemanticsNodes().isNotEmpty() }` など。裏の取得が Robolectric の上で進むように、必要なら `ShadowLooper.idleMainLooper()` や既存テストの待ち方(PanelScreenTest などで Task 167 が入れた dispatcher の注入・待ち方)に合わせる)。
- 確かめる中身(アプリ一覧を開き、幅を 516dp・1291dp に変えても `applist_close` が表示されたまま)は変えない。
- 同じ理由で落ちる layout のテストが他にもあれば、同じ形に直してよい(報告する)。本体のコードは変えない(テストだけ)。

## 変えるファイル
- `app/src/test/java/com/gt8d/launcher/ui/layout/LayoutMatrixTest.kt`

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.layout.*'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
