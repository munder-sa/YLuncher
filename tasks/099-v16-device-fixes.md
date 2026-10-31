# Task 099: 実機で見つかった v1.6 の崩れ3件を直す

最初に `tasks/RULES.md` を読み、従うこと。

## 背景(司令塔が実機 GT8D 1920×1080・密度 238 で確認)
1. **通知帯のボタンが読めない**: `notice/NoticeOverlay.kt` の操作のボタンが Android の標準の `Button` のまま(灰色の地に白い文字)で、「セットを編集」がほとんど読めない。
2. **「見つかりません」がカードからはみ出す**: `ui/home/HomeSetCards.kt` の `SetBlock` で、入っていないアプリのマスに 56dp の頭文字の下へ「見つかりません」(20sp)を足したため、マスの高さを超え、カードの下のセット名に重なって切れる。
3. **入っていないアプリのセットを、ホームの上から開くと幕が 4 秒残る**: `sets/SetRunner.kt` の `openSet` で、アプリが無く逃がし先がホーム(`d.startHome()` + `waitSingle(d.ownPkg, …)`)のとき、ホームが**すでに前に出ている**と新しい前面化が起きないため ready にならず、4 秒の Safety まで幕(と「ホームへ戻る」)が出たまま。ログ: `right uninstalled: com.example.missing` の 4 秒後に `curtain hidden reason=Safety`。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/notice/NoticeOverlay.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`(新しい口を渡すだけ)
- `app/src/test/java/com/gt8d/launcher/notice/NoticeOverlayTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.notice.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. 通知帯のボタン: 地は透明、白い輪郭 2dp・角丸 36dp(幕の「ホームへ戻る」と同じ見た目)、白い文字 `Dims.BodyText`、高さ・最小幅 72dp、左右の内側の余白 24dp、大文字化しない(`isAllCaps = false`)、標準の影・押したときの色の変化は消すか白系に。文字とボタンは縦の中央。普通の View のまま(`GradientDrawable` の輪郭で)でよい。
2. `SetBlock` の入っていないアプリ: 頭文字の四角と「見つかりません」を縦に積むのをやめ、**マスの中央に「見つかりません」だけ**(`Dims.BodyText`、`Palette.Muted`、1 行、収まらなければ省略)を出す。マスの大きさ・カードの高さは変えない。`contentDescription` は今のまま(「<名前>(アプリが見つかりません)」)。
3. `SetRunnerDeps` に `val homeVisible: () -> Boolean = { false }` を足し、`AppGraph` は `{ AppGraph.homeResumed }` を渡す。`openSet` でアプリが無くホームへ逃がす分岐(左パネルで右のアプリが無い、左が無くホームへ、の2か所)で、`d.startHome()` の前に `homeVisible()` が true なら、ホームの前面化を待たずに完了にする(幕なら `d.curtain.ready()`、幕なしなら今の幕なしの完了の処理)。通知(`AppMissing`)は今どおり。false なら今どおり `waitSingle(d.ownPkg, …)`。

## テスト(足す。既存は弱めない)
- `NoticeOverlayTest`: ボタンの地が透明(背景が輪郭つきの `GradientDrawable`)、文字が白、高さ 72dp 以上、`isAllCaps == false`。
- `HomeSetCardsTest`: 入っていないアプリのマスに「見つかりません」があり、そのマスの中に収まる(マスの範囲の中)。セット名(カードの名前の行)と重ならない。頭文字は出ない。
- `SetRunnerTest`: `homeVisible = { true }` で、左パネル・右アプリ無しのセットを開く → `startHome` が呼ばれ、幕は Safety を待たずに `Ready` で外れる(時計を 4 秒進める前に)。`AppMissing` が1回。左アプリ無し(右も無い)でも同じ。`homeVisible = { false }` なら今どおり前面化を待つ。

## この Task でしないこと
- ホームの「＋ セットを追加」の行の位置・カードの高さ(ユーザーの判断待ち)。通知帯の位置。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。

## 追記(司令塔の判断)
- 既存の `missingApp_showsMissingTextBelowInitial`(Task 092 で足した、今回直す見た目を確かめるテスト)は、この仕様に合わせて置き換えてよい: 頭文字が出ない・「見つかりません」がマスの中に収まる・セット名と重ならない。置き換えたことを報告する。ほかに、この Task の仕様と食い違う既存のテストがあれば、同じく仕様に合わせて置き換え、報告する(止まらない)。
