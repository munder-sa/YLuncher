最初に tasks/RULES.md を読み、従う。

# Task194 背景だけを独立した描画層へ分離する候補

あなたは実装担当。ユーザーは歌詞軽量化計画の5までを承認済み。この独立worktreeは採用前の候補実験で、実機比較後に司令塔が採否を決める。設計 docs/superpowers/specs/2026-11-05-lyrics-render-cost-design.md §5 に従う。

## 変更してよいファイル

- app/src/main/java/com/gt8d/launcher/ui/home/HomeMiniPlayer.kt
- app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt

版・依存・色抽出・ArtworkBackground.kt・その他のファイルは変更しない。Git更新/ADBは司令塔担当。

## 今回の候補 B1

Surface内の背景と動的コンテンツを兄弟要素へ分ける。Surfaceの直下をBoxとし、背面に matchParentSize のBox + graphicsLayer() + 既存artworkBackground(colors)、前面に元のBoxWithConstraints（背景modifierのみ除去、paddingは保持）。背景は元のpadding前の全域を占める。必要なら同等の構造を選んで報告してよいが、余白/サイズ/座標/角丸/タッチ/semanticsを変えない。

- この段階では描画命令を保持する通常のgraphicsLayerのみ。追加のOffscreen/Bitmap/RenderEffect/ラスタキャッシュを作らない。背景へのposition/fill/indexの依存を追加しない。通常の層はcomposeの破棄/サイズ/色更新に従わせる。
- Player全体や歌詞領域のlayer、LyricsPaneの方式、色抽出/共有holderを変更しない。
- 目的は実機で効くかを比較すること。層を追加しただけで性能改善と報告しない。効果がなければ司令塔が不採用にする。
- 既存の背景画素/角丸/3色/余白/歌詞切替/クリック/レイアウト試験を保持。
- 意味のある追加検証は背景が色と実寸に追従すること: 同一Compositionでカード幅/高さとdensityを変更し、余白部分の旧3色座標参照と画素一致（既存ArtworkColorReferenceの誤差を維持）、角丸外は従来どおり、色→null時Palette.Panelへ復帰。既存テストと重複するだけの検証を増やさない。
- GPU上の保持/メモリはRobolectricで証明したとしない。実機は司令塔。

## コマンド

- 読み取りのみのコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.HomeMiniPlayerTest'`
- `/home/munde/tools/gradle-run.sh :app:assembleRelease :panelhost:assembleRelease`

対象テスト成功後にrelease buildを1回。既存の版2.3.1-beta1 /48を維持。単体テストを成功後に繰り返さず、全試験はしない。APKは実機比較用で公開しない。テスト/ビルド結果、全判断、未確認事項を日本語で報告する。
