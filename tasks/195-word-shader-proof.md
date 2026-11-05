最初に tasks/RULES.md を読み、従う。

# Task195 単語マスクShaderの小さい成立確認

あなたは実装担当。計画の§6に沿った採用前の実験用worktree。画素互換が成立してから司令塔が統合指示を出す。今回Home/KaraokeLineへの配線、既存ファイルの変更はしない。新規依存、ADB、Git更新も行わない。

## 作成してよいファイル

- app/src/main/java/com/gt8d/launcher/ui/home/LyricWordBrush.kt
- app/src/test/java/com/gt8d/launcher/ui/home/LyricWordBrushTest.kt

## 成立確認

既存Android RuntimeShader + Compose ShaderBrushを、歌詞の白いoverlay文字自体に適用する候補。マスクの条件は設計書 docs/superpowers/specs/2026-11-05-lyrics-render-cost-design.md §6 と現行LyricsPane.kt。既存のdrawLyricWordBoundaryと旧描画の結果を参照にする。ネットの記述だけで対応/互換を断定しない。

- shader/brushは所有行ごと1個、毎frame再コンパイルしない。uniform更新は後でdraw段階から呼べる小さいメソッドとする。状態や時計をhelper内へ取り込まない。
- fill <= 0は透明、fill >= text.lengthは全白。途中は旧TextLayoutResultから同じ計算でx/top/bottomを得る。topより上は白、現在行はxの左右12dp、全幅24dpで白→透明、bottom以降透明。premultiplied alphaの白を返す。
- 現在行の描画ではDstIn/Clearと単語用Offscreenを除けるか調べる。ただしemphasisのグループ合成順は保持。フェードをModulateAlphaへ変えて代替しない。基底Textおよび歌詞領域全体のOffscreenは変えない。
- 先にNative Graphicsの小さい参照試験を実行する。矩形マスクだけでなく実際のCompose Text/同じTextLayoutResultを使う文字も検証する。22sp Bold/Start/maxLines2/Ellipsisを一致させる。
- 旧方式はテスト側の独立参照（白い全文字をlayerへ描画後、既存drawLyricWordBoundaryでDstIn/Clear）として残す。候補が期待値を生成する自己参照試験にしない。
- density1/1.4875/2、1行/折返し2行、0/途中/末尾、x端、RTL混在、結合文字、色付き絵文字、ellipsis、旧行のemphasis1/.5/0を比較。境界と文字AAの1/255許容を維持し、失敗後に許容を拡大しない。絵文字等でshaderが効かない可能性も推測せず実測する。
- uniformを変えた同じbrushで描画結果が更新し、別所有者のshaderは汚染されず、shader/brushインスタンスを作り直さないことを検証。
- 失敗がAndroid/Robolectric非対応または画素不一致なら、skip/ignore/失敗assert削除や、判定基準変更をせず、どの入力/座標/色差かと原因・選択肢を報告して止まる。指示範囲の実装ミスは直して再検証できるが、互換差を隠すフォールバック条件や別描画方式へ拡張しない。

## コマンド

- 状態を変更しない読み取り。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricWordBrushTest'`

既存クラス無変更なので既存57件は今回再実行しない。ビルドもまだ行わない。成功後の再実行なし。新規テストを含め失敗結果があればそのまま保持し、実装担当は削除/リセットしない。司令塔がこの実験を採用するか判断する。実行回数と件数、色差、決めたこと、実機未確認を日本語で報告する。

公式API根拠（互換性/速さの保証ではない）:
https://developer.android.com/develop/ui/compose/graphics/draw/brush
https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl
