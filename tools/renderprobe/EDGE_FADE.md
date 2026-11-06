# 歌詞領域の上下端フェード・範囲限定プローブ（Task220）

本体から独立した、帯分割合成の画素一致を調べる実験。`EdgeFadeActivity` と
`EdgeFadeInstrumentation` を追加する。旧 `ProbeActivity` / `ProbeInstrumentation` の
663ケース、`LyricWordBrush`、本体 `LyricsPane` は変更しない。

実装担当によるbuildは0回。その後司令塔が **offline build 1回・10秒で成功**を確認した。
実機実行はADB切断により0回で、画素一致、CPU / RenderThread負荷、実メモリ使用量は未確認。帯分割は未採用の仮説であり、
プローブの合格だけで本体や実車60Hzの改善を示さない。

## 司令塔による実行

以下は実行方法の記載であり、今回の実装担当は実行していない。
プロジェクトルートで、既存の独立ビルドを使用する。

```sh
/home/munde/tools/gradle-run.sh --offline -p tools/renderprobe -Pandroid.useAndroidX=true assembleDebug
```

APKの導入は司令塔が既存の手順で行う。対象端末と前面windowを確認してから実行する。

```sh
adb shell am instrument -w -r com.gt8d.launcher.renderprobe/.EdgeFadeInstrumentation
```

同じActivityの同じ対象矩形を、参照→候補の順で取得する。スクリーン全体、
system UI、他アプリは取得しない。画像の端末保存は行わない。
各取得はwindow/Composeルートcanvasのhardware描画と実コンテンツ描画後の
2回のframe commitを必須とする。commit callbackを登録したフレームとは別に
invalidateを要求する、既存プローブの修正済みパターンを使用する。
メインスレッドではlatchやFutureの完了を待たない。

ADBの終了コードだけで合否を判断しない。最終Bundleの次の値をすべて確認する。

- `success=1`、`planned=count=pass=216`、`fail=unrun=0`。
- `hardware=1`、`maxDelta<=1`、`tolerance=1`、`componentScale=255`。
- `activityDestroyed=bitmapCleanup=cleanup=1`、`pendingBitmaps=0`。
- `bitmapsCreated=bitmapsRecycled`。通常完走時は参照・候補の計432枚。
- `fatal` と `abortReason` が存在しない。

不一致・描画例外・単発timeoutでも後続ケースを継続する。同じ段階のtimeout
（描画と2commit待ち、またはPixelCopy）が2ケース連続した場合だけケース走査を中止し、
両失敗、`abortReason`、残りの未実行ケースを残す。起動・終了など走査外の異常は
`fatal` で区別する。各待機の上限は15秒。

PixelCopy送信後のtimeoutでは、そのコールバックが終わるまでbitmapをrecycleしない。
遅延コールバックが回収し、Activity終了後にも最大15秒の回収待ちを行う。
回収を確認できなければ `bitmapCleanup=0` と残数を返し、成功にはしない。
ActivityのComposition破棄でrememberしたGraphicsLayerも解放する。

## 描画の比較対象

| 方式 | 1回の内容描画に対する端フェードの処理 |
| --- | --- |
| `full`（参照） | 全領域Offscreenに内容を描き、本体 `lyricEdgeBrush` と同じ4stopの縦gradientを `DstIn` で適用 |
| `bands`（通常候補） | 全内容をGraphicsLayerへ1回recordし、重ならない上端・中央・下端のclipへ3回drawLayer。上端・下端だけ矩形saveLayer内で同じ全領域座標のgradientをDstIn適用 |
| `bands`（小高さfallback） | 実際の整数px高さが `2 × 32dpのpx値` 以下なら、全領域Offscreen + DstIn。record/drawLayer/明示saveLayerは0回 |
| `none`（計測専用） | 端フェードだけを省略。同じ文字・単語マスク・行のalpha/scaleは維持。採用候補にはしない |

参照画像は専用の全体合成コードで作る。候補の帯分割コードや画像から期待画像を
生成しない。小高さのgradient stopも本体の式をそのまま保ち、交差・重複stopを
並べ替えたりclampしたりしない。背景は常にフェード処理の外側に置く。

候補のGraphicsLayerは `rememberGraphicsLayer` で保持し、draw-scopeの `record`
拡張で `drawContent` を記録する。3回の再生でTextを再compose・再measureする
構造にはしない。端フェードを各行のalphaに置き換えず、重なった内容を合成後にマスクする。
帯のclip座標は小数pxを丸めず、全体gradientの座標系も移動しない。

`casesJson` 内の `full` / `bands` は次を記録する。

- `record` / `drawLayer` / `saveLayer` / `drawContent`：最後の内容描画passの明示呼び出し回数。
- `contentDrawPasses`、`recordTotal` / `drawLayerTotal` / `saveLayerTotal`、`maxRecordPerPass`：取得までの累積と最大値。
- `configuredFullOffscreen`：コードで指定した全領域Offscreen数。native saveLayer実行回数の測定値ではない。
- `compositeAreaPx2`：端フェード用に要求した合成矩形の面積の合計。
- `clips`：実際に描画へ渡した `[left, top, right, bottom]` のpx座標。
- `gradientStops`：実際のbrush構築に使った4個のstop位置。参照・候補それぞれを期待式と照合する。
- `compositions` / `rowCompositions` / `measures` / `placements`：scene・行のcompositionとscene Layoutのmeasure/配置カウンター。
- `composeDuringRecord` / `measureDuringRecord` / `composeDuringReplay` / `measureDuringReplay`：recordと再生中の上記カウンター増分。
- `commits` / `hardware`：当該取得の2commit待ちとhardware条件。

通常候補では `record=1, drawLayer=3, saveLayer=2, drawContent=1,
configuredFullOffscreen=0` と累積回数、record・再生中のcomposition/measure増分0を検査する。
参照とfallbackは `configuredFullOffscreen=1, drawContent=1`、明示3操作は0。
面積は通常候補が約 `幅px × 2 × 32dpのpx値`、全体合成が `幅px × 高さpx`。
Floatの矩形演算に限り高さ1 ULP相当の丸め誤差を検査で扱う。画素の許容差は変更しない。

例：density=1、240×192dpでは全体46,080px²、2端15,360px²。
これは**要求矩形面積**であり、実メモリ量ではない。Textの単語マスク・alphaに必要な
共通レイヤー、ドライバのアラインメント、RenderNodeの内部確保量を含まない。
record/replayによるCPU・RenderThread負担の増加も別途測定する。
カウンターのpass数はComposeの描画呼び出しであり、保持されたdisplay listを
再送信するだけのhardware frameまでrecord回数に数えない。

## 固定216ケースと検査

次の直積を固定し、失敗後もケース・閾値を変えない。

| 条件 | 値 |
| --- | --- |
| density / fontScale | `1 / 1.4875 / 2`、fontScaleは1 |
| 幅 / 高さ | 240dp / `192, 64, 48dp` |
| 行scale | `1, 0.9`、originは `(0, 0.5)` |
| overlay alpha | `1, 0.5`、base Textのalphaは0.35 |
| 行移動の標本 | alpha=1で+1.25dp、alpha=0.5で−2.5dp |
| 背景ARGB | `FF121212`（暗色）、`FF19354B`（色付き） |
| 単語進捗 | 0 / 行ごとの途中UTF-16位置 / 全塗り |

`3 × 2 × 2 × 2 × 3 × 3 = 216` 件。通常帯分割72件、fallback144件。
64dpはdensity=1と2では2端幅と一致する。density=1.4875では整数px高さ95に対し
2端幅95.2pxとなるため、同じfallback条件に入る。

各sceneには `single / multiline / ellipsis / combining / colorEmoji / rtl` の6行を同時に描く。
ダミー文字列、途中位置（順に2.5 / 11.5 / 4.5 / 1.5 / 3.5 / 1.5）、期待行数、
ellipsis、RTL条件は `samplesJson` に格納する。multilineは明示改行を含み、
ellipsisは十分長い自作文を使う。結合文字とemojiの途中UTF-16位置も含む。
RTL行だけ `LayoutDirection.Rtl`、他はLtrとする。

本体と同じ22sp・Bold・白・Start揃え・maxLines=2・EllipsisのTextをbase/overlayで
重ね、overlayはOffscreen。単語境界は本体と同じ `getHorizontalPosition`、
24dpの水平gradientとDstIn、後続行Clearで描く。alpha=0.5は離脱行の保持進捗の標本。
行移動は整数位置へ `placeRelativeWithLayer` した後の `translationY`、拡大縮小は
layerのscaleを使う。

行の基準中心は `[0, 32dp, 高さ/2−8dp, 高さ/2+8dp, 高さ−32dp, 高さ]`。
移動・scale後も外側2端と帯境界を跨ぐこと、中央2行が重なることを検査する。
参照・候補の文字サイズ、行数、ellipsis、行の上下/左右/baseline、全UTF-16位置の
primary/secondary horizontal position、配置top/height/scale、layerに設定したbase/overlay alphaも照合する。
clipは候補の計算を呼び出さない数値オラクルで比較する。

画像は全画素・全RGBA成分で差1/255以下を必須とし、境界画素を除外しない。
対象背景込みのwindow画像なので通常alpha成分は255だが、それも比較対象に含める。
参照の描画が空でないこと、通常高さで上/中央/下にinkがあること、暗色背景で
カラーemojiに由来する色画素があることも検査する。

`casesJson` は未実行を含む全216行を保持し、`status=pass|fail|unrun`、条件、
`pass=1|0|-1`、操作・配置情報、エラーを記録する。`rows` はサンプル順に
`[topPx, heightPx, scale, lineCount, ellipsis, UTF16進捗, baseAlpha, overlayAlpha]`。
`maxDelta` / `rgbaMax` / `differentPixels` は最大成分差・RGBA別最大差・閾値超過画素数。
`x,y` は取得矩形内の最大差座標、`component` はその成分、`oldARGB,newARGB` は
符号なしARGB値。`rect` はwindow内の `[x,y,width,height]`。
`regionMax` と `ink` は上/中央/下の順で、交差する小高さでは上側への分類を優先する
診断値。未取得の最大差・座標・hardwareは−1。両画像取得後は、画素不一致と配置・clipの
不一致を別々の `errorIds` として残す。Bundleにも集計と最悪ケース・座標を返す。

## 計測モード

同じAPKで明示extraを指定する。各起動は新しいActivityでフレーム番号を0から始める。

```sh
adb shell am start -S -W -n com.gt8d.launcher.renderprobe/.EdgeFadeActivity --es mode perf --es strategy full
adb shell am start -S -W -n com.gt8d.launcher.renderprobe/.EdgeFadeActivity --es mode perf --es strategy bands
adb shell am start -S -W -n com.gt8d.launcher.renderprobe/.EdgeFadeActivity --es mode perf --es strategy none
```

上記は別々の計測起動例。司令塔がfull/bandsを交互に同じ条件で測定する。
noneはフェード寄与の診断用に限る。起動時、未知mode/strategy、perfのstrategy省略、
proofへのstrategy指定は明示エラーにする。mode省略時はinstrumentation用のproof待機。

固定sceneは320×400dp、density=1.4875、fontScale=1（476×595px）、scale=1、
base alpha=0.35、overlay alpha=1、背景FF19354B、同じ6行。起動前に現端末の
表示領域へ収まることを司令塔が確認する。コードも実測矩形が指定pxと違う場合は
縮小して続行せず明示失敗する。

`withFrameNanos` ごとにStateを更新し、進捗は `(frameIndex % 240) / 239 × 文字列長`。
0から全塗りまで240フレームで繰り返す。同じフレーム番号なら全strategyで同じ値。
Stateの値は端フェードと単語マスクのdrawだけで読む。composition/measureで読まず、
毎frameのText再構築を要求しない。Frame clockの更新数は描画成功数やfpsの保証ではない。

logcatタグは `EdgeFadeProbe`。正常時は開始 `event=0`、600フレームごとの
`event=1`、通常のActivity終了時 `event=2` に数値を出す。
strategyの数値はfull=0 / bands=1 / none=2。永続ログとUIトグルは作らない。
描画条件違反は `perf_failed` を出して終了する。計測モードではPixelCopyしない。

## 決めたこと

- 比較は上記216件の直積と6行同時表示とし、対象幅240dp・高さ192/64/48dpを選んだ。
- 自作文、途中UTF-16位置、明示改行、ellipsis長文、RTL行だけのRTL配置を上記で固定した。
- base alphaを0.35、overlay alphaを1/0.5とし、alphaに対応する行移動標本と6個の基準中心を固定した。
- 参照とfallbackの全体マスクは別のコードに置き、短い高さでも既存のstop式を維持する。
- GraphicsLayerはrememberで所有し、draw-scopeのrecordを使い、帯の小数座標を丸めない。
- 端フェードの明示操作・要求合成面積を記録し、共通の単語/alphaレイヤーや物理メモリ量とは区別する。
- 条件のJSON形式、操作数・gradient stop・alpha・配置・全UTF-16文字位置の照合、ink/emojiの空描画検出を追加した。
- 画素RGBA比較に加えて帯別差を診断記録する。面積計算のFloat誤差だけ高さ1 ULPを認める。
- 待機を各15秒、進捗報告を最初の5件・25件ごと・連続timeout時にし、未実行行もJSONに保持する。
- bitmapは送信状態と遅延コールバックを追跡し、終了時15秒以内に回収できなければcleanup失敗にする。
- 計測は320×400dp・density1.4875・6行・色付き背景・scale/overlay alpha各1に固定した。
- 計測進捗を240フレーム周期にし、drawでStateを読む。logcatは開始・600フレームごと・通常終了時のみ数値報告する。
- modeの既定値はproof、perfではstrategy必須とし、未知値とproofへのstrategy指定を拒否する。

## 確認済みと未確認

確認済みはソースの静的照合と編集範囲のみ。調査には読み取り用の `cat`、`sed -n`、
`rg`、`ls` を使用した。差分確認コマンドと結果は次のとおり。

- `git status --short`：Manifest更新と新規2ファイルだけ。
- `git diff -- tools/renderprobe/src/main/AndroidManifest.xml`：既存登録を保ったActivity/Instrumentationの追加だけ。
- `git diff --check`：指摘なし、終了コード0。
- `git diff --no-index --check /dev/null tools/renderprobe/src/main/java/com/gt8d/launcher/renderprobe/EdgeFadeProbe.kt`：空白エラーの出力なし。新規ファイル差分のため終了コード1。
- `git diff --no-index --check /dev/null tools/renderprobe/EDGE_FADE.md`：空白エラーの出力なし。新規ファイル差分のため終了コード1。

ビルド・テスト・ADB・ネットワークアクセス・Git更新は実行していない。
テスト実行件数は0件であり、成功件数・失敗件数とも未測定。

未確認はコンパイル、端末フォントでのfixture条件、交差stopを含む実GPU画素一致、
PixelCopy/timeout/cleanupの実機動作、計測sceneの表示可否とフレームごとの実行状態、
CPU/RenderThread負荷と実メモリ量。失敗時に条件や画素閾値を変更して合格扱いにしない。
