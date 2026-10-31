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
- `app/src/test/java/com/gt8d/launcher/boot/BootRunnerIntegrationTest.kt`(偽の Messenger を `show(notice)` に替えるだけ。「通知が来たら失敗」の検証は残す)

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.notice.*' --tests 'com.gt8d.launcher.sets.*' --tests 'com.gt8d.launcher.link.*' --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.boot.*'`

## 仕様
1. `Notice.kt`(型は全部 `Notice` の中に入れ、それぞれ `: Notice` を実装する。参照は `Notice.SplitUnsupported(...)` の形):
   ```kotlin
   enum class NoticeAction(val label: String) { EditSets("セットを編集"), Home("ホームへ"), ChooseBrowser("ブラウザを選ぶ") }
   sealed interface Notice {
       val text: String
       val action: NoticeAction?
       data class SplitUnsupported(val appLabel: String) : Notice // "<appLabel>は分割に対応していないため、全画面で開きました" / EditSets
       data class SplitFailed(val appLabel: String) : Notice      // "分割で開けませんでした。<appLabel>を全画面で開きました" / Home
       data class OpenFailed(val appLabel: String) : Notice       // "<appLabel>を開けませんでした" / Home
       data class AppMissing(val appLabel: String) : Notice       // "<appLabel>が見つかりません" / EditSets
       data object Slow : Notice                                  // "開くのに時間がかかっています" / なし
       data object NoBrowser : Notice                             // "リンクを開けるブラウザがありません" / ChooseBrowser
       data object BrowserFailed : Notice                         // "ブラウザを起動できませんでした" / なし
   }
   ```
2. `Messenger` を `fun show(notice: Notice)` の1つに置き換える(旧 `showSplitUnavailable`・`showSwitchFailed` は消す)。SetRunner の呼び出し:
   - resize 不可(分割非対応)→ `SplitUnsupported(非対応のアプリの名前)`(左が非対応なら左、そうでなければ右。両方非対応なら左)。
   - `launcher == null`(LeftOnly)と `handleLaunchFailure` → `SplitFailed(全画面で開いたアプリの名前)`。
   - 上の2つで、全画面の起動(`d.apps.startApp`)が **false を返した**ときは、「全画面で開きました」と言わず `OpenFailed(そのアプリの名前)`。
   - アプリが入っていない逃がし先 → `AppMissing(入っていないアプリの名前)`。名前は `d.labelFor`(取れなければパッケージ名)。中継が無いとき(パネルの側)は「YLuncher パネル」。**知らせるのは、今のコードの判定の順で最初に見つかった1件だけ**: 左パネルはアプリ → 中継の順、左右アプリと右パネルは左 → 右(中継)の順。両方無ければ左(左パネルならアプリ)。
   - 4 秒の Safety(今の `showSwitchFailed` の所)→ `Slow`。
3. `NoticeOverlay`(`AppGraph` が持つ。`Messenger.show` の実装):
   - 重ねて表示(`TYPE_APPLICATION_OVERLAY`。重ねて表示の許可が無ければ今の Toast で文字だけ)で、画面の**下の中央**(下から 24dp)に出す。幅は `min(画面の幅の 50%, 720dp, 画面の幅 − 2 × (88dp + 48px + 16dp))`(両端の帯 48px とドック 88dp と重ならない)。背景 `Palette.Panel`・角丸 16dp・白文字 `Dims.BodyText`、右に操作のボタン(72dp 以上の高さ、白い文字)。幅の計算は純粋な関数(例 `noticeWidthPx(screenWidthPx, density)`)にしてテストする。
   - **6 秒**で消える(6 秒は表示を始めた時点から)。新しい通知が来たら置き換える(6 秒も数え直す)。操作のボタンを押したら消して操作を行う。
   - 幕が出ている間は出さない: 通知を受けた時点で `AppGraph.curtain.isShowing` なら、**最新の1件だけ**を待たせ、幕が外れたら出す。外れたことは、`AppGraph` の `CurtainViewProxy.hide(reason)` から `NoticeOverlay.onCurtainHidden(reason: HideReason)` で知らせる(理由も渡す。Task 093 で「取り消し」のときに待たせた通知を捨てるのに使う。`CurtainController.onHidden` は SetRunner が使っているので**上書きしない**)。幕の窓が出せなかった場合に備え、待たせてから 5 秒たっても幕が外れたと知らされなければ、その時点で幕が出ていなければ出し、まだ出ていれば捨てる。
   - 通知帯を出している間に新しい幕が出たら、通知帯はそのまま(幕の下になる。6 秒で消える)。
   - 通知帯の外のタッチは下のアプリに届く(`FLAG_NOT_TOUCH_MODAL`・`FLAG_NOT_FOCUSABLE`)。窓の大きさは通知帯の大きさだけ(全画面にしない)。
   - Compose を使う場合は、サービス・Application の Context で `ComposeView` に `ViewTreeLifecycleOwner` などを用意する(`edge/CurtainWindow.kt` の `OverlayLifecycleOwner` の作りを参考に)。普通の View で作ってもよい(どちらにしたか報告)。
   - 操作: `EditSets` → SettingsActivity を `open_route = "sets"` で(`FLAG_ACTIVITY_NEW_TASK`)、`Home` → `AppGraph.runner.home()`、`ChooseBrowser` → `open_route = "link"`(NEW_TASK)。Intent を作る所は純粋な関数(例 `noticeActionIntent(context, action): Intent?`。`Home` は null)にしてテストする。
4. `LinkEntryActivity`: ブラウザが選べない → `Notice.NoBrowser`、ブラウザの VIEW の起動に失敗 → `Notice.BrowserFailed`(どちらもログは今のまま残す)。
5. `HomeSetCards`: 入っていないアプリのマスに、今の頭文字の下に「見つかりません」(`Dims.BodyText`、`Palette.Muted`)。
6. ログ: `Messenger.show` で `DiagLog.d("Notice", "show ${notice::class.simpleName} text=…")`(場面テストで使える)。

## テスト(足す。既存は弱めない。旧メソッドを確かめていた既存のテストは、新しい通知で同じ場面を確かめる形に置き換え、報告する)
- `NoticeTest`: 各通知の文言と操作。
- `SetRunnerTest`(偽の Messenger で受けた通知を確かめる):
  - 分割非対応: 左だけ・右だけ・両方 → `SplitUnsupported(左/右/左)`。全画面の起動が false → `OpenFailed`。
  - LeftOnly・起動失敗 → `SplitFailed`。全画面の起動が false → `OpenFailed`。
  - 未インストール: 左右アプリで左だけ・右だけ・両方、左パネルでアプリ・中継・両方、右パネルでアプリ・中継 → それぞれ `AppMissing(上の順で最初の1件)`。通知は1回だけ。
  - Safety → `Slow`。
- `NoticeOverlayTest`(Robolectric): 表示で文言とボタン(72dp 以上)、6 秒で消える、新しい通知で置き換わり 6 秒を数え直す、ボタンで操作が1回呼ばれ消える、幕が出ている間は出ない → 外れたら出る(6 秒はそこから)、幕の間に2件来たら新しい方だけ出る、幕が外れたと知らされなくても 5 秒で(幕が無ければ)出る、重ねて表示の許可が無ければ Toast、窓のフラグ(`FLAG_NOT_TOUCH_MODAL`・`FLAG_NOT_FOCUSABLE`)と位置(下の中央)。
- 幅の関数: 1920×1080・密度 238 で、通知帯の左右の端が両端の (48px + 88dp) の内側に収まる。狭い画面(幅 1280px)でも同じ。
- 操作の Intent の関数: `EditSets` → SettingsActivity・`open_route="sets"`・NEW_TASK、`ChooseBrowser` → `"link"`・NEW_TASK、`Home` → null(`runner.home()` は Overlay の側で呼ぶ。偽の口で1回呼ばれることを確かめる)。
- `LinkEntryActivityTest`: ブラウザ無し → `NoBrowser`、起動失敗 → `BrowserFailed`。通常の経路と、例外の後の逃がし(`openWithConfig`)の両方で。
- `HomeSetCardsTest`: 未インストールのマスに「見つかりません」。
- `SettingsActivityTest`: `"sets"` → 画面セット、`"link"` → リンクの行き先。

## この Task でしないこと
- 幕の見た目・取り消し(Task 093)。安全モード(Task 094)。git のコミット等。

## 報告
変更点、通知帯を何で作ったか、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
