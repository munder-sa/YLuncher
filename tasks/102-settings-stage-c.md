# Task 102: 設定の見た目を段階 C に(白い選択・← だけ・編集とセット一覧の作り直し)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.6 設計書 `docs/superpowers/specs/2026-10-31-v1.6-ux-design.md` の §5「段階 C の決定」の **C3・C6** を実装する(この指示書が優先)。ユーザーがモックで選んだ見た目は `/mnt/f/YLuncher/build/mock/stage-c/c3-selection.png`(**案A**)・`c6-set-list.png`(**案B**)・`c6-edit-dialogs.png`(a〜d)。HTML は同じ名前の `.html`(寸法の参考に読んでよい)。今の実機の画面は `/mnt/f/YLuncher/build/mock/stage-c/ref/settings-*.png`。
今の問題: 選択・主ボタンが緑(`Palette.Accent`)。編集の「左」「右」の見出しと注意書きが色の指定なしで背景に溶けて見えない。アプリは「左のアプリ: YouTube」の文字だけで押せるように見えない。比率が丸いチップ5つの2段。ヘッダーに ← と × が両方ある。セット一覧の各行に ▲▼削除 の大きなボタンが並ぶ。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/settings/SettingsUi.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SettingsScreen.kt`
- `app/src/main/java/com/gt8d/launcher/settings/SetsScreen.kt`
- `app/src/main/java/com/gt8d/launcher/settings/DailySettings.kt`・`GestureScreen.kt`・`BootSettingsScreen.kt`・`LinkScreen.kt`・`ListsScreen.kt`(自動保存の案内を足す所だけ。要らなければ触らない)
- `app/src/main/java/com/gt8d/launcher/ui/theme/Theme.kt`(テーマの `primary` を白に。`Palette.Accent` の定義は、使う所が無くなっても消さない)
- テスト: `app/src/test/java/com/gt8d/launcher/settings/SettingsUiTest.kt`・`SetsScreenTest.kt`・`SettingsScreenTest.kt`・`DailyScreensTest.kt`・`SettingsNavTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.ui.layout.*'`

## 仕様
1. 共通の部品(`SettingsUi.kt`):
   - `SettingsChoice`: 選ばれたもの = 白い面(`Palette.PanelAccent`)に暗い文字(`Palette.Background0`)、選ばれていないもの = 地 `Palette.Background1`・白い文字・輪郭なし。角丸 12dp(丸い錠剤の形はやめる)。72dp 以上は今のまま。
   - `SettingsButton` の Primary = 白い filled・暗い文字。Secondary = tonal(地 `Palette.Panel`、白い文字)。Danger は今のまま。新しく `SettingsButtonKind.Outlined`(地は透明、白い輪郭 1dp、白い文字)を足す。角丸 12dp。
   - 新しく `SettingsSegments(options: List<Pair<String, String>>(label, tag), selected: Int, onSelect: (Int) -> Unit, enabled)`: 1 行で等幅に並ぶセグメント(高さ 72dp、地 Background1、選ばれたマスは白い面に暗い文字、角丸は外側 12dp)。
   - 新しく `SettingsAppRow(label: String, icon: ImageBitmap?, tag: String, placeholder: String?, highlighted: Boolean, onClick)`: 72dp の押せる行(地 Background1、角丸 12dp)。左にアプリのアイコン 48dp(無ければ頭文字の四角。未選択なら + のアイコン)、名前(未選択なら `placeholder`)、右に「変更」と ›。`highlighted` なら白い輪郭 1dp。
   - `SettingsTopBar` から × をやめ、← だけ(`onClose` の引数は残してよいが使わない。`settings_close` のタグは無くなる)。← の動きは今のまま。
   - 新しく `SettingsAutoSaveNote()`: 「変更は自動で保存されます」(`Dims.BodyText`、`Palette.Muted`)。
2. 画面セットの編集(`SetsScreen.kt` の編集の部分):
   - 「左」「右」「比率」の見出しは `Palette.Muted` の `Dims.BodyText`。注意書き「分割に対応していないアプリは、分割できないことがあります」も `Palette.Muted`。
   - 左と右の各段: 1 行に「パネル」「アプリ」の `SettingsChoice` と、その右に `SettingsAppRow`(アプリのとき。パネルのときは出さない)。行を押すと今のアプリの選択に進む(今の `pickingLeft`)。
   - 片側をパネルにして反対側のアプリが未選択なら、その `SettingsAppRow` を `highlighted` にし、placeholder「アプリを選んでください」、行の下に「反対側のアプリを選んでください」(Muted)。
   - 「左右を入れ替える」は Secondary(tonal)。比率は `SettingsSegments`(使える比率だけを1行に。使えない比率は出さない、今の絞り込みのまま)。「保存」は Primary、全幅。
   - 保存せずに ← で戻ろうとしたとき(下書きが開いた時の内容から変わっている)、Material の basic dialog「変更を保存しますか?」/本文「保存していない変更があります。」/ボタン「続ける」(text)「破棄」(Danger の text)「保存」(Primary。保存できない下書きなら無効)。続ける=閉じるだけ、破棄=保存せずに戻る、保存=保存して戻る。変わっていなければ確かめずに戻る。Android の戻る操作(`BackHandler`)も同じ。
3. 画面セットの一覧(`SetsScreen.kt` の一覧の部分):
   - 各行(72dp 以上、地 Panel、角丸 12dp、押すと今どおり編集): 左に番号(Muted)、左右のアプリのアイコン(各 48dp。パネルの側は `ui/home/PanelIcon`)、名前「YouTube | パネル」(白)と下に比率(Muted)。右に「⋮」の IconButton(72dp、`set_menu_<id>`)。
   - 「⋮」を押すと `DropdownMenu`: 「上へ」(`set_up_<id>`)・「下へ」(`set_down_<id>`)・「削除」(`set_delete_<id>`、Danger の文字)。先頭の行の「上へ」・最後の行の「下へ」は無効。削除は今の確認のダイアログ(はい/いいえ)を通す。今の ▲▼削除 のボタンはやめる。
   - 一番下の「セットを追加」は Outlined、+ のアイコン付き、全幅。
4. すぐに保存される設定の画面(操作・起動時・パネルとホーム・リンクの行き先、など今のコードで「保存」ボタンが無い画面)の、ヘッダーの下に `SettingsAutoSaveNote()`。画面セットの編集・一覧には出さない。
5. 緑(`Palette.Accent`)を設定の中で使わない(このタスクの後、`rg "Palette.Accent" app/src/main/java/com/gt8d/launcher/settings` が 0 件)。

## テスト(足す。既存は弱めない。× ・▲▼ボタン・緑の色・チップの形を確かめていた既存のテストは、新しい仕様で同じ場面を確かめる形に置き換え、どれを置き換えたか報告する)
- `SettingsUiTest`: 選ばれた `SettingsChoice` が白い面・暗い文字、`SettingsButton` Primary が白、`SettingsSegments` の選択と押したときの通知、`SettingsAppRow` の 72dp・placeholder・highlighted、TopBar に「閉じる」が無い。
- `SetsScreenTest`: 見出し「左」「右」が Muted の色。アプリの行を押すとアプリの選択に進む。右をパネルにして左が未選択 → 案内と強調。比率がセグメント1行。変更して ← → ダイアログ、続ける/破棄/保存のそれぞれ。変更なしで ← → ダイアログなしで戻る。一覧の ⋮ → 上へ/下へで並びが変わる・端では無効・削除は確認の後に消える。
- 自動保存の案内が「操作」などに出て、画面セットの編集には出ない。
- `rg` の 0 件は報告に書く。

## この Task でしないこと
- 幕・起動画面(Task 103)、パネル・ホーム(Task 104)。設定の保存の仕組みの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、置き換えた既存テスト、**決めたこと**。

## 追記(実装前レビューの反映。本文より優先)
- 変更してよいテストに `app/src/test/java/com/gt8d/launcher/settings/SettingsActivityTest.kt` と `app/src/test/java/com/gt8d/launcher/ui/panel/PanelActivityTest.kt` を足す。`settings_close` を押していたテストは、← (`settings_back`)で同じ終了・パネルへの復帰を確かめる形に置き換える。
- 比率のセグメント: 各マスの幅も 72dp 以上を守る。1 行に並べると 72dp を下回る幅(例 360dp の画面)では、3 つずつの行に折り返す(今の段の数と同じ)。`OtherScreensLayoutTest` は変えない(弱めない)。
- 未保存の確認: 今の「変更後の ← / Android の戻るで即座に破棄」を確かめている `SetsScreenTest` の2件(129・133 行あたり)は、「ダイアログ → 破棄 → 保存されている内容は変わらない」を確かめる形に置き換えてよい。アプリ名の旧表示(「左のアプリ: …」)を確かめるテストも、新しいアプリの行で同じことを確かめる形に置き換えてよい。
- ダイアログの「保存」は、今の「保存」ボタンと同じ処理(`fromSplitButton` のときは保存してそのセットを開く、を含む)。保存に失敗したら下書きを残して画面に留まり、今のエラーの文を出す。保存中(`busy`)は ← / 戻るを受けない。
- アプリの選択中は、ヘッダーの ← も Android の戻るも「選択を閉じて編集に戻る」(ダイアログは出さない)。編集の画面で ← / 戻るのときだけダイアログの判定をする。
- 「アプリ」の選択肢を押したときの動きは今のまま(アプリの選択に進む)。
- `SettingsAppRow` は `placeholder != null` を未選択とみなす(未選択は + のアイコン)。選択済みでアイコンが取れないときは頭文字。アイコンは `SetsScreen` に `iconFor: (String) -> ImageBitmap? = { null }` を足して受け取り、`SettingsScreen.kt` から今のアプリの情報の取り方(`AppCatalog.icon` など、今あるもの)で渡す。左右の順・左パネルの PanelIcon の反転も一覧のテストで確かめる。
- 狭い幅(内側の幅 600dp 未満)では、「パネル/アプリ」の選択肢の行の下にアプリの行を置く(縦に積む)。名前・案内は 1 行で省略。一覧の行も名前は 1 行で省略。長い名前・未選択・文字の倍率 1.3 の組み合わせを `SetsScreenTest` か `OtherScreensLayoutTest` の今の仕組みで確かめる(後者を変えずに済むなら前者で)。
- 自動保存の案内を出すのは、今の即時保存の4画面(リンクの行き先・操作・起動時・パネルとホーム(Lists))だけ。Lists のアプリの選択中も上に出す。設定のトップ・許可のチェック・画面セットには出さない。`DailySettings.kt` は触らない。
- 使えない比率は出さない(モックの「無効で表示」より、この指示書を優先)。操作の画面の選択肢は `SettingsChoice` の見た目が変わるだけで、並びは今のまま。
- テストのコマンドに `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.settings.*' --tests 'com.gt8d.launcher.ui.layout.*' --tests 'com.gt8d.launcher.ui.panel.PanelActivityTest'` を使う。
