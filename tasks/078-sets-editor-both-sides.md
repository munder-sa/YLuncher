# Task 078: 設定画面で、パネルを左右どちらにも置けるようにする(v1.5 Task 4)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §3.2、計画 Task 4。この指示書が優先)。Task 074〜077 で、型(`ScreenSet.left: Slot`・`Side`・`swapped`・`mirrored`・`allowedFor(left,right)`・`isValid`)、左パネルのセットの起動、カードの描き方ができている。この Task で、設定画面の「画面セットの編集」から左パネルのセットを作れるようにする。今は「左のアプリ」(アプリのみ)と、右の「パネル / アプリ」がある(`SetsScreen.kt` の `SettingsRoute.SetEdit`)。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/settings/SetsEdit.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SetsScreen.kt`
- `app/src/test/java/com/gt8d/launcher/settings/SetsEditTest.kt`
- `app/src/test/java/com/gt8d/launcher/settings/SetsScreenTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.settings.*'`

## 仕様

### A. SetDraft(SetsEdit.kt)
1. `data class SetDraft(val id: String, val left: Slot?, val right: Slot?, val ratio: SplitRatio, val appPairLabel: String? = null)`。`null` は「アプリを選んでいない」。
2. `fun withLeft(slot: Slot): SetDraft`・`fun withRight(slot: Slot): SetDraft`: 指定の側を `slot` にする。`slot` が `Slot.Panel` で反対側も `Slot.Panel` なら、反対側を `null`(アプリ未選択)にする。比率は、変更後の左右で `allowedFor` を満たさなければ補正する(R3_7→R4_6、R7_3→R6_4。判定の時、`null` の側は `Slot.App("")` とみなす)。
3. `fun swapped(): SetDraft` = 左右を入れ替え、`ratio.mirrored()`、`appPairLabel = null`。
4. `val canSave: Boolean` = 左右とも非 null、両方 Panel でない、`ratio.allowedFor(left, right)`。
5. `toScreenSet()`: `ScreenSet(id, left!!, right!!, ratio, appPairLabel)`(`check(canSave)` は今のまま)。
6. `from(set)` は左右をそのまま。`new(id)` は今と同じ既定(左 未選択、右 Panel、R6_4)。

### B. 編集画面(SetsScreen.kt の SetEdit)
1. 左と右、それぞれに同じ形の部品を並べる(上から「左」「⇄」「右」):
   - 見出し(「左」/「右」、`Dims.BodyText`)
   - 横並びのボタン「パネル」(testTag `set_left_panel` / `set_right_panel`)と「アプリ」(`set_left_app` / `set_right_app`)。今の右の UI と同じ `SetButton` を使い、選ばれている方を `selected`。
   - 「アプリ」を選んでいるときは、その下に「左のアプリ: <名前>」/「右のアプリ: <名前>」、未選択なら「選んでください」。「アプリ」を押すとアプリの選択画面へ(今の `pickingLeft` の仕組みを左右に使う)。選んだら `withLeft(Slot.App(pkg))` / `withRight(Slot.App(pkg))`。
   - 反対側がパネルのとき、こちらの「パネル」は押せない(`enabled = false`)。
2. 左と右の間に「⇄ 左右を入れ替える」ボタン(testTag `set_swap`、`Icons.Default.SwapHoriz` と文字、72dp 以上)。押すと `draft = draft.swapped()`。
3. 比率の選択肢は `SplitRatio.entries.filter { it.allowedFor(left ?: Slot.App(""), right ?: Slot.App("")) }`。
4. 一覧(セットの行)の名前は実際の並び: 左の名前 | 右の名前(Panel は「パネル」)。074 の仮の分岐をこの形に置き換える。
5. 文言は日本語、タッチ領域 72dp、既存の画面の部品と見た目に合わせる。

## テスト(足す。既存は弱めない。「左はアプリだけ」を前提にした既存の期待値は置き換え、どれを変えたか報告する)
- `SetsEditTest`: `withLeft(Panel)` で右が Panel なら右が null。`withLeft(Panel)` で R3_7 → R4_6。`swapped()` で左右と R6_4→R4_6、`appPairLabel` が null。両方 Panel は作れない(`canSave == false`)。`new` の既定は今と同じ。`from`→`toScreenSet` の往復(左パネル)。
- `SetsScreenTest`: `set_left_panel` を押すと左がパネルになり `set_right_panel` が無効、右のアプリを選んで保存すると `ScreenSet(Panel, App(..), ..)` が保存される。`set_swap` で左右が入れ替わる(見出しの下の表示で確かめる)。一覧の名前が「パネル | <アプリ名>」。既存の「左はアプリだけ」のテスト(SetsScreenTest:67 付近)はこの仕様に置き換える。

## この Task でしないこと
- 起動・カード・リンクの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。
