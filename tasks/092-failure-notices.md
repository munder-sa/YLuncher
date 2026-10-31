# Task 092: 失敗の理由と次の操作を、6秒残る通知帯で知らせる(v1.6 B2)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §4 B2 と §2、計画の Task 092 と Review Focus 1 を読むこと(この指示書が優先)。
- 今は `Messenger`(`sets/SetRunner.kt`)の `showSplitUnavailable()`(「分割が使えなくなりました」)と `showSwitchFailed()`(「切り替えに失敗しました」)を、`AppGraph` が短い Toast で出すだけ。分割非対応・分割の方式が無い・起動失敗が同じ文言で、見逃すと理由が分からない。
- 未インストールのアプリを含むセットを開いても、何も知らせない(逃がし先へ行くだけ)。
- リンク(`link/LinkEntryActivity.kt`)で、ブラウザが無い・ブラウザの起動に失敗しても、ログだけ。

## 変更・作成するファイル(これで全部)
- create `app/src/main/java/com/gt8d/launcher/notice/Notice.kt`
- create `app/src/main/java/com/gt8d/launcher/notice/NoticeOverlay.kt`
- `app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt`
- `app/src/main/java/com/gt8d/launcher/AppGraph.kt`
- `app/src/main/java/com/gt8d/launcher/link/LinkEntryActivity.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsActivity.kt`(`open_route` に `"sets"`・`"link"` を足すだけ)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`(未インストールの文字だけ)
- create `app/src/test/java/com/gt8d/launcher/notice/NoticeTest.kt`
- create `app/src/test/java/com/gt8d/launcher/notice/NoticeOverlayTest.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt`
- `app/src/test/java/com/gt8d/launcher/link/LinkEntryActivityTest.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`
- `app/src/test/java/com/gt8d/launcher/settings/SettingsActivityTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.notice.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.link.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.boot.*'`

## 仕様
1. `Notice.kt`:
   ```kotlin
   enum class NoticeAction(val label: String) { EditSets("セットを編集"), Home("ホームへ"), ChooseBrowser("ブラウザを選ぶ") }
   sealed interface Notice { val text: String; val action: NoticeAction? }
   data class SplitUnsupported(val appLabel: String)  // "<appLabel>は分割に対応していないため、全画面で開きました" / EditSets
   data class SplitFailed(val appLabel: String)       // "分割で開けませんでした。<appLabel>を全画面で開きました" / Home
   data class AppMissing(val appLabel: String)        // "<appLabel>が見つかりません" / EditSets
   data object Slow                                    // "開くのに時間がかかっています" / なし
   data object NoBrowser                               // "リンクを開けるブラウザがありません" / ChooseBrowser
   data object BrowserFailed                           // "ブラウザを起動できませんでした" / なし
   ```
2. `Messenger` を `fun show(notice: Notice)` の1つに置き換える(旧 `showSplitUnavailable`・`showSwitchFailed` は消す)。SetRunner の呼び出し:
   - resize 不可(分割非対応)→ `SplitUnsupported(非対応のアプリの名前)`(両方非対応なら左)。
   - `launcher == null`(LeftOnly)と `handleLaunchFailure` → `SplitFailed(全画面で開いたアプリの名前)`。
   - アプリが入っていない逃がし先(左パネル・右パネル・両方アプリのどの分岐でも)→ `AppMissing(入っていないアプリの名前)`(名前は `d.labelFor`。入っていないアプリの名前が取れなければパッケージ名)。中継が無いとき(パネルの側)は「YLuncher パネル」。
   - 4 秒の Safety(今の `showSwitchFailed` の所)→ `Slow`。
3. `NoticeOverlay`(`AppGraph` が持つ。`Messenger.show` の実装):
   - 重ねて表示(`TYPE_APPLICATION_OVERLAY`。重ねて表示の許可が無ければ今の Toast で文字だけ)で、画面の**下の中央**に、幅は画面の 50%(最大 720dp)、背景 `Palette.Panel`・角丸 16dp・白文字 `Dims.BodyText`、右に操作のボタン(72dp 以上の高さ、白い文字)。左右の端の帯(48px)と重ならない。
   - **6 秒**で消える。新しい通知が来たら置き換える。操作のボタンを押したら消して操作を行う。
   - 幕(`CurtainController`)が出ている間は出さず、幕が外れてから出す(今の幕の状態を見られる口があればそれで、無ければ通知を受けた時点で幕が出ていれば、外れるのを待って出す)。
   - 通知帯の外のタッチは下のアプリに届く(`FLAG_NOT_TOUCH_MODAL`・`FLAG_NOT_FOCUSABLE`)。
   - Compose を使う場合は、サービス・Application の Context で `ComposeView` に `ViewTreeLifecycleOwner` などを用意する。普通の View で作ってもよい(どちらにしたか報告)。
   - 操作: `EditSets` → SettingsActivity を `open_route = "sets"` で(NEW_TASK)、`Home` → `AppGraph.runner.home()`、`ChooseBrowser` → `open_route = "link"`。
4. `LinkEntryActivity`: ブラウザが選べない → `Notice.NoBrowser`、ブラウザの VIEW の起動に失敗 → `Notice.BrowserFailed`(どちらもログは今のまま残す)。
5. `HomeSetCards`: 入っていないアプリのマスに、今の頭文字の下に「見つかりません」(`Dims.BodyText`、`Palette.Muted`)。
6. ログ: `Messenger.show` で `DiagLog.d("Notice", "show ${notice::class.simpleName} text=…")`(場面テストで使える)。

## テスト(足す。既存は弱めない。旧メソッドを確かめていた既存のテストは、新しい通知で同じ場面を確かめる形に置き換え、報告する)
- `NoticeTest`: 各通知の文言と操作。
- `SetRunnerTest`: 分割非対応 → `SplitUnsupported("…")`、LeftOnly・起動失敗 → `SplitFailed`、未インストール(左パネル・右パネル・両方アプリ)→ `AppMissing`、Safety → `Slow`(偽の Messenger で受けた通知を確かめる)。
- `NoticeOverlayTest`(Robolectric): 表示で文言とボタン(72dp 以上)、6 秒で消える、新しい通知で置き換わる、ボタンで操作が1回呼ばれ消える、幕が出ている間は出ない → 外れたら出る、重ねて表示の許可が無ければ Toast。
- `LinkEntryActivityTest`: ブラウザ無し → `NoBrowser`、起動失敗 → `BrowserFailed`。
- `HomeSetCardsTest`: 未インストールのマスに「見つかりません」。
- `SettingsActivityTest`: `"sets"` → 画面セット、`"link"` → リンクの行き先。

## この Task でしないこと
- 幕の見た目・取り消し(Task 093)。安全モード(Task 094)。git のコミット等。

## 報告
変更点、通知帯を何で作ったか、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
