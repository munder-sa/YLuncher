最初に `tasks/RULES.md` を読み、従う。

# Task 180 ジャケット背景の複数色と色差改善

あなたは実装担当。司令塔のGit/ADB権限は引き継がない。ユーザーは背景コード改善と、旧暗色仕様に依存するテストを新仕様へ更新することを明示承認した。設計は `docs/superpowers/specs/2026-11-04-artwork-palette-design.md`。これを実装する。旧v2.0仕様の最頻1色/成分56上限は新仕様に置き換わるが、コントラスト4.5:1以上、既存ライフサイクル、処理量上限、元画像の所有権は維持する。

## 変更してよいファイル

- app/src/main/java/com/gt8d/launcher/ui/theme/ArtworkColors.kt
- app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt
- app/src/test/java/com/gt8d/launcher/ui/theme/ArtworkColorsTest.kt
- app/src/test/java/com/gt8d/launcher/ui/theme/LyricsVisualStyleTest.kt
- app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt
- app/src/test/java/com/gt8d/launcher/ui/home/HomeMiniPlayerTest.kt
- app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt

新規ファイル、版、依存、設定、ネットワーク、メディア/歌詞取得/パーサー、他のUI、ドキュメントを変更しない。

## 実装の具体化

- 設計の得点/候補1%/色相差35度/HSV調整/最大3色を純Kotlinで実装する。Android Colorや追加ライブラリに依存しない純計算を保つ。Float/Doubleの境界を明示し、同点順序は安定させる。
- `ArtworkColors(light, dark, accent = light)` とし、lightは開始、accentは中央、darkは終了という意味をコメントする。既存2引数の注入値と共有ホルダーの型は維持する。3停止点の固定線形グラデーションを `drawWithCache` で描く。
- 元の有効画素フィルタ・4096箱のalpha重み・代表平均の四捨五入は維持。総重み1%未満の箱は候補にしない。全候補が落ちた時は最多箱(同点は小さい箱番号)の1色へ戻す。
- 純色1色の `darkenArtworkColor` は新変換の1.0/0.8/0.6倍3停止点を返す。黒を直接渡した場合は色相を捏造せず黒3点を返す。無彩色の直接入力は彩度を増やさず暗くする。抽出側の無彩色のみの画像は従来どおりnullでPanelへ戻す。
- 彩度0.55〜0.90、V=0.45+0.15*元Vを経て、RGB共通倍率16回の二分探索で各停止点を明るくできる最大まで調整する。相対輝度≤0.045と、白/Muted(#B0B0BB)/合成白0.62の比≥4.65を満たす。比の算出は既存compositeSrgb/contrastRatioを活用できる。最終丸めで基準を割らない。恒常的なループを設けない。
- 新実装の読みやすさは停止点だけでなく全区間の256分割補間を独立計算で検証する。4.5未満が見つかれば、テストを弱めず入力と最小値を報告する。未検証の仮定で合格としない。
- Bitmap処理の最大辺32、ARGB_8888のコピー回避、例外処理、cancel、元画像をrecycleしない処理、共有ホルダー/キーを変更しない。抽出ログには3色を出すが曲/寸法以外の新しいデータを加えない。

## テスト

テスト仕様移行はユーザー承認済み。旧固定赤色/成分56上限/最頻1色の期待値だけを新しい仕様へ更新する。旧仕様を名乗る `allChannelsAtMost56` は新内容に対応する名前へ改名してよいが、ケースを削除しない。その他の既存名と検証目的は維持する。assertの削除だけで済ませず、以下の強さと読みやすさの条件へ置き換える。

1. 赤/青単色の色相、彩度の維持、最大成分>56、開始→中央→終了の暗さ。旧方式では通らないテストにする。出力の期待値を実装呼び出しから生成しない。色相差等は独立した参照計算を用いる。
2. 面積が多い低彩度色と十分な面積の高彩度色からの選択、1%未満のノイズ除外、色相差35度、最多候補への復帰、alpha重み、同点/入力順の再現性、最大3色。人工画素でどの色が選ばれるべきかを先に示す。
3. 赤青2色で両方の色味が残り、3原色では3色が残る。1色/2色時の中央色を確認する。白黒/空/透明のみはnull。
4. 多色の高彩度/低彩度/極値/境界入力と全停止点間を256分割した背景について、白/Muted/白0.62/単語下地のコントラスト≥4.5。独立sRGB計算と8bit丸めを使用する。フェードの最小値と白が重なるケースも従来の目的を維持して検証する。実行時に最小比を報告する。
5. サンプリング旧実装比較はコピー方法の非破壊/等価性の検証として維持する。同じ新色抽出への入力が一致するかを見る。Task 173のコピー回避条件を壊さない。
6. Home/MediaCardの赤背景画像検証は、色味と56超の色成分、および読める色という基準へ移行。既存の角丸、背景なし、レイアウト不変、歌詞切替、共有回数、連続描画なしの検証は残す。必要なら人工2色画像の描画検証を追加する。新描画が背景全体で使われることを確認する。

## 使ってよいコマンド

- 読み取りコマンド。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.theme.ArtworkColorsTest' --tests 'com.gt8d.launcher.ui.theme.LyricsVisualStyleTest' --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest' --tests 'com.gt8d.launcher.ui.home.HomeMiniPlayerTest' --tests 'com.gt8d.launcher.ui.panel.MediaCardTest'`

コードを変更していない成功後の再テスト/全テスト/ビルド/ADB/Git更新は禁止。失敗を実装修正した場合は同じ対象コマンドを再実行してよい。仕様の数値を失敗に合わせて変えない。

## 報告

変更ファイル、テストの実行回数・件数・失敗/スキップ数・最小コントラスト、追加/移行した検証、決めたこと、未確認を日本語で報告する。実機の見た目/性能は司令塔が後で確認するため完了扱いにしない。

## 実装・司令塔レビュー結果

- `.codex-bak/20261104-151806/`、終了0。指定7ファイルのみ変更。上記コマンドは1回で101件成功(ArtworkColors17、LyricsVisualStyle6、ArtworkBackground19、HomeMiniPlayer39、MediaCard20)、失敗/エラー/スキップ0。司令塔は全差分・コマンド・XMLを照合、成功テストを繰り返していない。
- 独立計算による最小比: 境界色/256分割補間4.936374656958221、多色/フェード/白の重なり4.949967208384362。旧成分56上限は承認済み新仕様へ移行し、既存の非破壊/共有/キャンセル/レイアウト/連続描画なしを維持。人工3色のホーム/パネル実描画も1階調以内で照合。
- allowの警告1件は、検査が引用符を除去して比較する既知の正規化問題。eventsの実コマンドは指示どおり引用符付き。許可外ファイルや禁止コマンドの逸脱なし。
- 決めたこと: HSV/得点/倍率はDouble、候補1%はLongの整数比較。無彩色の直接入力を元より明るくしない。二分探索中は丸め後の合格色を保持。独立期待色は65,537倍率点の列挙から算出。追加検証の許容差は色相1度・描画1階調、既存の許容差は緩めない。
- 実機確認はTask 181へ記録。
