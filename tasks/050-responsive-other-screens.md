# Task 050: 起動画面・ブラウザの上のバー・設定・アプリ一覧も、狭い幅で崩れないようにする

最初に `tasks/RULES.md` を読み、従うこと。仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` §4.9 に従う。
UI の決まり: 文言は日本語、ダーク固定、配色は `Palette`、タッチ領域は最小 72dp(`Dims.MinTouch`)、本文 20sp 以上(`Dims.BodyText`)。
迷ったら Android の Material Design(画面幅の区分 Compact <600・Medium 600〜839・Expanded ≥840dp)と iOS のヒューマンインターフェースガイドラインに合わせる(タッチ 72dp・本文 20sp はこの仕様の値を優先)。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

前提: Task 047 で `M/ui/layout/`(`OneLineText`、`PanelLayout` など)と `T/ui/layout/LayoutMatrixTest.kt` ができている。まずそれらを読み、同じ部品・同じ確かめ方を使う。

## 作成・変更するファイル(これで全部)
- `M/link/BrowserPane.kt`
- `M/ui/home/BootScreen.kt`
- `M/settings/SettingsScreen.kt`、`M/settings/SetsScreen.kt`、`M/settings/ListsScreen.kt`、`M/settings/LinkScreen.kt`、`M/settings/GestureScreen.kt`、`M/settings/BootSettingsScreen.kt`、`M/settings/PermissionsScreen.kt`、`M/settings/AppPairPrepScreen.kt`、`M/settings/SafetyRecoveryScreen.kt`
- `M/ui/apps/AppListScreen.kt`
- 新規 `T/ui/layout/OtherScreensLayoutTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.ui.* --tests com.gt8d.launcher.settings.* --tests com.gt8d.launcher.link.*`
- `/home/munde/tools/gradle-run.sh assembleDebug`

## 仕様
1. **ブラウザの上のバー**(BrowserPane): バーの幅が 600dp 未満(Compact)のときは、「Chromeで開き直す」をアイコンのボタン(`Icons.AutoMirrored.Filled.OpenInNew`、72dp 角、`contentDescription = "Chromeで開き直す"`、タグは今の `browser_chrome` のまま)にする。
   600dp 以上は今の文字のボタンのまま。「戻る」「閉じる」は文字のまま。サイト名は `OneLineText`(白・SemiBold は Task 049 のまま)。
2. **起動画面**(BootScreen): 2つのボタン(「今すぐ開く」「ホームのまま使う」)が横に並んで入らない幅(各ボタンの文字が1行で入らない幅。目安 600dp 未満)では、縦に並べる(幅いっぱい、間隔 8dp)。ボタンの文字は `OneLineText`。
3. **設定**: 上のバーの題名、各一覧の行の項目名・アプリ名、ボタンの文字を `OneLineText` にする(省略記号)。**説明文・案内文・警告文は複数行を許す**(1行にしない)。
   行の中で「名前+操作ボタン(▲▼削除など)」が横に入らない幅では、今の 044 の作り(516dp で上下に並べる)と同じ規則で上下に並べる。360dp でも押せる部品が 72dp 以上で、画面の内側にあること。
4. **アプリ一覧**(AppListScreen): アプリ名は今の2行・省略記号のままでよい。セルの最小幅(今 112dp)は、360dp の幅でも2列以上になることを確かめる(変えなくてよければ変えない)。

## テスト(先に書き、修正前に失敗することを確かめる)
`T/ui/layout/OtherScreensLayoutTest.kt`(Robolectric + Compose。047 の `LayoutMatrixTest` と同じ作り方: `RuntimeEnvironment.setQualifiers("w<W>dp-h<H>dp")` で大きさを決め、根の実際の大きさを最初に確かめる、クリップ後の境界で比べる、スクロールの中の部品は `performScrollTo` してから):
- 幅 360・387・516・645・1291dp × 高さ 400・726dp、fontScale 1.0 と 1.3 で、次の画面を描く:
  ブラウザの上のバー(`BrowserPane`)、起動画面(`BootScreen` のカウントダウン中)、設定の Root と各詳細(画面セットの一覧・編集、パネルとホーム、リンクの行き先、操作、起動時、許可のチェック、予備方式の準備)、安全モードの復旧画面、アプリ一覧。
- それぞれで:
  (a) `OneLineText` の印の付いた文字がすべて1行。
  (b) 押せる部品(`hasClickAction()`)のクリップ前の大きさが 72dp 以上で、画面の横の範囲の内側にある。
  (c) 同じ行の押せる部品同士が重ならない。
- ブラウザ: 360・387・516dp では `browser_chrome` が文字を持たずアイコン(`contentDescription` が「Chromeで開き直す」)、645・1291dp では文字「Chromeで開き直す」(600dp の境目どおり)。
- 起動画面: 387dp で2つのボタンが縦に並ぶ(上のボタンの下端 ≦ 下のボタンの上端)、1291dp で横に並ぶ。
- 既存のテストは変えずに通ること。

## 手順(TDD)
1. 上のテストを書く。2. 対象テストを実行し、修正前に失敗することを確かめる(どのテストがどう失敗したかを報告に書く)。
3. 実装する。4. 対象テストが全部通るまで直す(最大3回)。通ったら `assembleDebug`。
- Robolectric で Activity の中の Compose の状態を書き換えて確かめるテストでは、確かめる前に `Snapshot.sendApplyNotifications()` を呼ぶ。

## この Task でしないこと
- パネル・ホーム(047 の範囲)、幕の配置。機能の変更。`android:configChanges` や資源の幅の修飾子の追加。
- 全テストの実行、実機の操作、git のコミット等。

## 報告
実行したコマンドと結果(件数・失敗件数、修正前の失敗の内容、assembleDebug)、作成・変更したファイル、**決めたこと**。
