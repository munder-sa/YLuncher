# Task 077: ホームのカードとパネルのアイコンを左パネルに対応させる(v1.5 Task 5)

最初に `tasks/RULES.md` を読み、従うこと。

## 背景
v1.5(設計書 `docs/superpowers/specs/2026-10-31-v1.5-left-panel-design.md` §5、計画 Task 5)。Task 074 で `ScreenSet.left: Slot`・`slotAt(Side)` ができている。画面セットのカード(`HomeSetCards`。全画面のホームと狭いパネルのセット表示で共用)で、パネルが左のセットも正しく描く。パネルのアイコン(`PanelIcon`。右側の画面とドックの図柄)は、左パネルのときは左右反転して向きが分かるようにする。

## 変更するファイル(これで全部)
- `app/src/main/java/com/gt8d/launcher/ui/home/HomeSetCards.kt`
- `app/src/main/java/com/gt8d/launcher/ui/home/PanelIcon.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/HomeSetCardsTest.kt`

使ってよいコマンド:
- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.*' --tests 'com.gt8d.launcher.ui.panel.*'`

## 仕様
1. `PanelIcon(modifier: Modifier = Modifier, mirrored: Boolean = false)`。`mirrored` なら `Modifier.graphicsLayer(scaleX = -1f)` で図柄を左右反転。semantics に `PanelIconMirrored`(`val PanelIconMirrored = SemanticsPropertyKey<Boolean>("PanelIconMirrored")`、同じファイルで公開)を付ける。`contentDescription = "パネル"` は今のまま。
2. `HomeSetCards`: カードの左右のマスを `set.slotAt(Side.Left)`・`set.slotAt(Side.Right)` から作る。マスの幅は今のまま比率(左:右)。Panel のマスは `PanelIcon(mirrored = (そのマスが左))`。名前は実際の並び(左の名前 | 右の名前。Panel は「パネル」)、中央寄せは今のまま。074 で入った仮の分岐は、この形に置き換える。

## テスト(足す。既存は弱めない)
- `leftPanelSet_showsPanelIconOnLeft`: `ScreenSet("s1", Slot.Panel, Slot.App("right"), SplitRatio.R4_6)` のカードで、`contentDescription = "パネル"` の要素の中心 x がカードの中心 x より左、名前が「パネル | 右アプリ」。
- `panelIcon_mirroredOnlyOnLeft`: 左パネルのセットのアイコンは `PanelIconMirrored = true`、右パネルのセットは false。
- 既存のテスト(中央寄せ・56dp・長い名前・カードの高さ等)はそのまま通る。

## この Task でしないこと
- 設定画面・起動・リンクの変更。git のコミット等。

## 報告
変更点、テストの結果(件数)、**決めたこと**。

## 補足(点検の反映)
1. パネルのマスは `PanelIcon(Modifier.size(56.dp), mirrored = …)`(56dp を保つ)。左パネルのアイコンが 56dp であることもテストする。
2. `leftPanelSet_ratioWidths`: R4_6 の左パネルのセットで、左マスの幅 < 右マスの幅。
