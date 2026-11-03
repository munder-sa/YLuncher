最初に tasks/RULES.md を読み、従う。

# Task 151: ジャケット色の静止背景(ホームのミニプレイヤーと、分割中のパネルのメディアカード)

仕様: `docs/superpowers/specs/2026-11-04-v2.0-lyrics-visual-design.md`。計画: `docs/superpowers/plans/2026-11-04-v2.0-lyrics-visual.md` の **Task 151** の Files・Interfaces・Step をそのまま実施する。Task 150 の `ui/theme/ArtworkColors.kt`・`ui/theme/LyricsVisualStyle.kt` を使う(変えない)。

## 計画からの変更(両文書の末尾「ユーザーの決定」)
- **MediaCard にも同じ背景を使う**。色の取り出しは1回にして、ホームの `HomeMiniPlayer` と `MediaCard` で共用する(例: `ArtworkBackground.kt` に、今のジャケットの1件だけを保持するプロセス内の入れ物を置き、両方がそこから読む。同じ Bitmap なら取り出しをやり直さない)。MediaCard のカードの角丸・余白・高さは変えない。
- そのため、`ui/panel/MediaCard.kt` と `ui/panel/MediaCardTest.kt`(追加のみ)も変えてよい。MediaCard にも計画の HomeMiniPlayer と同じ確かめ(背景がジャケットの色になる・ジャケットが無ければ今の色)のテストを1〜2件足す。

## 変える・作るファイル(これ以外は変えない)
- 作る: `app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt`
- 変える: `app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt`、`app/src/main/java/com/gt8d/launcher/ui/panel/MediaCard.kt`、`app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt`(追加のみ)、`app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt`(追加のみ)

## 使ってよいコマンド
- 読み取り(cat, sed -n, rg, ls, git diff)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest' --tests 'com.gt8d.launcher.ui.home.HomeMiniPlayerTest' --tests 'com.gt8d.launcher.ui.panel.MediaCardTest'`

## 報告
- 実行したコマンドと結果(テストの件数・失敗数)、決めたこと(RULES.md 1)。
