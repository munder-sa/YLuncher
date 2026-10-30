# Task 052: 設定画面の見た目を、役割で見分けられる形にする(Material の設定の一覧に合わせる)

最初に `tasks/RULES.md` を読み、従うこと。仕様 §6・§4.9・§9 に従う。UI の決まり: 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp、本文 20sp 以上。
迷ったら Android の Material Design 3(リストの項目、ボタンの種類: Filled / Tonal / Outlined / Text、上のバー)と iOS のヒューマンインターフェースガイドライン(設定のグループ化された一覧)に合わせる。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 背景(司令塔が実機で確かめた事実)
設定の一覧の項目、上のバーの「戻る」「閉じる」、各画面の操作ボタンが、すべて明るいライム色で塗りつぶしたボタン(Material3 の `Button` の既定)になっている。
どれが画面の移動で、どれが主な操作で、どれが取り消せない操作かを見分けられない。ユーザーは「より使いやすい UX」を求めている。

## 作成・変更するファイル(これで全部)
- 新規 `M/settings/SettingsUi.kt`(共通の部品)
- `M/settings/SettingsScreen.kt`、`M/settings/SetsScreen.kt`、`M/settings/ListsScreen.kt`、`M/settings/LinkScreen.kt`、`M/settings/GestureScreen.kt`、`M/settings/BootSettingsScreen.kt`、`M/settings/PermissionsScreen.kt`、`M/settings/AppPairPrepScreen.kt`、`M/settings/SafetyRecoveryScreen.kt`
- 新規 `T/settings/SettingsUiTest.kt`
- 既存の設定のテスト(`T/settings/*Test.kt`): 上のバーの「戻る」「閉じる」を**文字で**確かめている箇所だけ、`contentDescription` での確認に置き換えてよい(アイコンにするため。司令塔が許可)。タグ(`settings_back` など)・期待する動き・ほかの確認は変えない。置き換えた行は報告に全部並べる。

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.ui.layout.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
### 共通の部品(SettingsUi.kt)
1. `SettingsTopBar(title, onBack, onClose)`: 左に「戻る」のアイコンのボタン(`Icons.AutoMirrored.Filled.ArrowBack`、`contentDescription = "戻る"`、タグ `settings_back`)、題名(`OneLineText`、白、SemiBold)、右に「閉じる」のアイコンのボタン(`Icons.Filled.Close`、`contentDescription = "閉じる"`、タグ `settings_close`)。ボタンは 72dp 角、アイコンは白。
2. `SettingsSection(title: String?, content)`: 区分の見出し(小さめの灰色の文字、`Dims.BodyText` 以上、上に 16dp の間)と、角丸 16dp の面(`Palette.Panel`)の中に行を縦に並べる。行と行の間に細い区切り線。
3. `SettingsRow(title, tag, onClick, subtitle = null, enabled = true, kind = Navigate)`: 幅いっぱい・高さ最小 72dp の行。題名は白(`OneLineText`)、副題は灰色(複数行可)。右端に、`Navigate` なら右向きの矢印(`Icons.AutoMirrored.Filled.KeyboardArrowRight`)、`External`(Android の設定などの別の画面へ)なら `Icons.AutoMirrored.Filled.OpenInNew`、`Danger` なら矢印なしで題名を赤(`Color(0xFFFF6B6B)` 程度。`Palette` に `Danger` として足してよい→ その場合 `M/ui/theme/Theme.kt` も変更対象に加えたことを報告)。無効のときは全体を灰色にする。
4. `SettingsButton(text, tag, onClick, kind = Secondary, enabled = true)`(ボタンの種類):
   - `Primary`: 塗りつぶし(`Palette.Accent`、文字は黒系)。**1つの画面に1つまで**(例: 編集の「保存」、確認の「はい」)。
   - `Secondary`: 控えめな面(`Palette.Panel` より明るい面、文字は白)。
   - `Danger`: 控えめな面、文字は赤(削除・デフォルトに戻すの「はい」など取り消せない操作)。
   - 72dp 以上、文字は `OneLineText`。
5. `SettingsChoice(label, tag, selected, onClick)`(比率・帯の幅などの選択肢): 選ばれているものは塗りつぶし(`Palette.Accent`、黒系の文字)、それ以外は枠線だけ(白い文字)。`selected` の semantics(`Modifier.selectable` など)を付け、今のテストの `assertIsSelected` 等が通ること。
### 画面への当てはめ
- 設定の Root: 区分を3つにする。「画面」(画面セット、パネルとホーム、リンクの行き先、操作、起動時)、「システム」(許可のチェック、予備方式の準備(表示条件は今のまま)、純正ランチャーに戻す(`External`))、「元に戻す」(デフォルトに戻す(`Danger`)、安全モードを解除(今の有効・無効の条件と副題のまま))。項目のタグ・順番・押したときの動きは今のまま。
- 各詳細画面: 一覧の行は `SettingsRow`/行の形、操作は `SettingsButton`、選択肢は `SettingsChoice` に揃える。▲▼・削除は `Secondary`/`Danger` のアイコンのボタンでもよい(72dp、`contentDescription` を付ける。タグは今のまま)。
- 確認ダイアログ: 「はい」は取り消せない操作なら `Danger`、そうでなければ `Primary`。「いいえ」は `Secondary`。
- 今の 050 の狭い幅の規則(上下に並べる等)は保つ。

## テスト(先に書き、修正前に失敗することを確かめる)
`T/settings/SettingsUiTest.kt`(Robolectric + Compose):
- `topBar_usesIcons_withDescriptions`(戻る・閉じるが文字を持たず、contentDescription が「戻る」「閉じる」)
- `root_hasThreeSections_inOrder`(見出し「画面」「システム」「元に戻す」がこの順で、各項目が正しい区分にある)
- `row_kinds_showTrailingIcon`(Navigate は矢印、External は別画面のアイコン、Danger は矢印なし)
- `choice_selected_isFilled_andSelected`(selected の semantics)
- `editScreen_hasAtMostOnePrimary`(セットの編集画面で Primary の印の付いたボタンが1つ。印は独自の semantics で付ける)
- 既存のテスト(050 の `OtherScreensLayoutTest` を含む)が、上で許した置き換え以外は変えずに通ること。

## 手順(TDD)
1. テストを書く。2. 修正前に失敗することを確かめる。3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。

## この Task でしないこと
- 設定の機能・項目・文言(題名・説明文)の変更。ブラウザ・パネル・ホームの変更。全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、置き換えた既存テストの行、**決めたこと**。
