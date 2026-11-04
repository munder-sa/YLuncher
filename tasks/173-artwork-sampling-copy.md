最初に `tasks/RULES.md` を読み、従う。

# Task 173: ジャケットの色抽出で ARGB_8888 の元サイズのコピーを省く

2026-11-04、ユーザーの「実験として負荷軽減の軽い物を」に基づく小規模な改修。暫定司令塔が既存の性能改善候補から選定した。実装担当は司令塔の権限を引き継がない。

## 目的・範囲

`ui/home/ArtworkBackground.kt` の `sampleArtworkColors` は、最大辺32pxに縮小する前に、元画像全体を `ARGB_8888` にコピーしている。入力が既に `ARGB_8888` のときだけ、この元サイズのコピーを省く。例として1024×1024のARGB画像なら、元サイズのコピーの画素約4MiB分を作らなくなる(計算上の値。実機の時間・メモリ計測値ではない)。

変更範囲は色抽出の Bitmap の準備・後始末だけ。鍵、キャッシュ、コルーチン、ログ、背景の色の計算・描画、他の画面、版番号、新規依存は変更しない。非表示タイマー・再生情報・履歴の改善は別件。

## 変えるファイル(これ以外は変えない)

- `app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt`
- `app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt`(テストの追加のみ)

## 実装

1. `bitmap.isRecycled` の早期終了を維持する。
2. `bitmap.config == Bitmap.Config.ARGB_8888` のときは、入力をそのまま縮小処理の元に使う。元サイズの `copy` は呼ばない。
3. それ以外の config(HARDWARE、RGB_565、RGBA_F16、null を含む)は、従来どおり `copy(Bitmap.Config.ARGB_8888, false)` してから縮小する。今回はこの経路を最適化しない。
4. `artworkSampleSize`、縦横比、最大辺32px、`createScaledBitmap` の `filter=false`、`getPixels`、`extractArtworkColors` は維持する。元画像を変更しない。
5. `createScaledBitmap` が入力と同じオブジェクトを返す場合もある。入力の Bitmap を recycle せず、自分で作った Bitmap だけを一度ずつ後始末する。小さい画像・同じ大きさの画像でも、呼び出し後に元画像が引き続き利用できること。
6. CancellationException の再送出、RuntimeException と OutOfMemoryError の null フォールバックは維持する。例外の対象を広げない。
7. 性能計測のための公開APIや新しい抽象化は追加しない。コピー削減は実コードの差分で確認し、回帰テストでは色と元画像の保持を確認する。

## 司令塔が確認した API の根拠

Android 公式 Bitmap リファレンス(2026-11-04 確認):
https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,int,int,boolean)

指定した幅・高さが元と同じ場合は元の Bitmap が返り、新しい Bitmap は作られない。したがって縮小結果を無条件に recycle しない。実装担当による追加のネットワーク調査は不要。

## 回帰テスト

既存テスト・期待値・実行対象は変更しない。現行の Native Graphics / SDK35 の Robolectric テストに次の3件を追加する。旧方式をテスト内だけの参照関数として使ってよい(全体をARGBへコピー→縮小→画素取得→色抽出、参照関数の生成物も後始末する)。

- `largeArgbMatchesPreviousSampling`: 32pxを超える横長・縦長のARGB画像で、複数の色と半透明の画素を含む固定パターンを使う。旧方式と色が厳密一致すること、結果がnullではないこと、呼び出し後も入力の全画素が変更されず recycle されていないことを確認する。
- `smallImmutableArgbRemainsUsable`: 最大辺32px以下の immutable ARGB画像で、2回続けて抽出して期待する色が得られ、元画像が読み出せることを確認する。同一オブジェクトが縮小結果になる場合の寿命を検証する。
- `rgb565MatchesPreviousSampling`: RGB_565 の画像で旧方式との厳密な色の一致、nullではないこと、元画像の全画素と寿命が維持されることを確認する。ARGB以外の変換経路を残したことの回帰確認。

## 使ってよいコマンド

- 読み取り(cat, sed -n, rg, ls, git diff など)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest' --tests 'com.gt8d.launcher.ui.theme.ArtworkColorsTest'`

実装・テストを追加してからこの対象テストを1回流す。失敗を修正した場合の再実行は可。全テスト・assemble・実機操作・Git操作は行わない。

## 報告

- 変更点と、ARGB以外の経路を維持した根拠。
- 実行コマンド、テスト件数・失敗件数、XMLの場所。
- ソースで確認したコピー削減と、実機未測定の効果を区別する。
- 決めたこと(RULES.md 1)。
