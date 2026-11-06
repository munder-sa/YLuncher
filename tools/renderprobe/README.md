# 単語描画の実GPU比較

YLuncher本体とは独立した検証アプリ。現行のText＋Offscreen/DstInマスクを参照とし、TextへのRuntimeShader適用（`text_shader`）または通常Text描画後のRenderEffectマスク（`post_mask`）を、同じ文字配置・背景・進捗で比較する。ダミー文字のみを使い、認証・通信・メディアの権限は持たない。本体から参照しない。

プロジェクトルートからビルドする。

```sh
/home/munde/tools/gradle-run.sh --offline -p tools/renderprobe -Pandroid.useAndroidX=true assembleDebug
```

生成物は `tools/renderprobe/build/outputs/apk/debug/YLuncherRenderProbe-debug.apk`。司令塔が実機へ導入後、以下を実行する。ADB接続先はその都度確認する。

```sh
adb shell am instrument -w -r com.gt8d.launcher.renderprobe/.ProbeInstrumentation
```

無指定は従来の `text_shader`。`-e mode text_shader` でも明示できる。新候補は以下で選択する。

```sh
adb shell am instrument -w -r -e mode post_mask com.gt8d.launcher.renderprobe/.ProbeInstrumentation
```

未知のmode（空文字も含む）はActivityを起動せず、`fatal=...unknown_mode...`、`success=0`、未実行663件として失敗する。進捗・最終Bundleおよび `casesJson` の各行に `mode` を記録する。

同一window内の対象矩形だけをPixelCopyで取得し、実コンテンツ描画後のframe commitを2回確認する。全画素RGBAの各成分差が1/255以下、文字配置・省略・カラー絵文字・shader所有者の条件を満たす必要がある。色差の失敗は後続ケースも実行する。同種の描画/取得timeoutが2回続く場合は、両失敗と未実行数を残して終了する。

`adb` 自体の終了コード0は合格を意味しない。最終Bundleの `count=planned=663`、`fail=0`、`unrun=0`、`hardware=1`、fatalなしをすべて確認する。各ケースは `casesJson` にあり、失敗時の座標・最大差・old/new ARGBも残る。

`post_mask` も同じ663ケース（通常630件＋owner検証33件）を使う。全面通過・全透明、emphasis=0、カラー絵文字、RTL、UTF-16途中、2行・省略・端の画素も比較対象に含む。通常TextのRenderNode内容を `RenderEffect.createRuntimeShaderEffect` の `content` 入力へ渡し、premultiplied RGBA全成分へ同じalphaを掛ける。Textの色をShaderBrushで置き換えず、base/overlayの白・22sp Bold・maxLines=2・Ellipsis・group alphaとレイヤー範囲は参照に揃える。中間進捗は既存の行・UTF-16境界、24dpの白→透明alpha、後続行の透明化を使う。**Offscreenは残る**。

ownerごとにRuntimeShaderとeffectを保持する。通常の候補比較ではuniform更新後にeffectを明示的に生成する。owner Aの2回目以降の更新では、先にuniformだけ更新して保持済みeffectを実際に再描画し、その後uniformを再更新せず新しいeffectで再描画する。各密度4回、計12回の追加キャプチャを既存の `owner_A_update` 行に含め、ケース数は増やさない。追加キャプチャも同じ矩形・2回commit・PixelCopy・timeout処理を通る。owner BはA更新後もuniformとeffectを変更せず再描画し、画像・所有者・更新数・effect同一性を検査する。

`casesJson` の `retainedEffect` に保持済みeffectの `toReference`（現在進捗の参照）、`toPrevious`（Aの前回候補）、`toFresh`（再生成後候補）の全画素差・座標・ARGBを記録する。各比較の `oldARGB` は比較先、`newARGB` は保持済みeffectの画素。`recreationNeededForReference` は、再生成後だけ参照と一致すれば1、両方一致すれば0、再生成後も不一致なら判断不能の-1。保持済みeffectが古いuniformを使うこと自体は診断結果とし、候補の画素合否には再生成後の画像を使う。所有者・更新・再生成の手順違反や追加取得の例外は、その既存ケースを失敗にする。

`candidateUniformUpdates` と `candidateEffectCreations` は進捗・最終Bundleでは全ownerの累計、各比較済みケースのJSONではそのownerの累計。uniform更新数は初期値設定を除く `update` 完了回数（個別の `setFloatUniform` 呼出し数ではない）。既存の `shaderUpdates` も残す。effect生成数はAndroid RenderEffect＋Composeラッパーの1組を1回と数え、旧モードでは0。中断や取得失敗のない `post_mask` では更新・生成とも648回となる実装で、実測値は結果Bundleで確認する。

新候補は各キャプチャのText計測時に1回準備し、同じキャプチャのcommit待ちではeffectを再利用する。一方、毎フレーム進捗を更新する用途へ持ち込めば、毎フレームeffectとラッパーのallocationが発生する。再compose・PixelCopy・診断比較を含むため、性能向上やdraw-only更新の成立は主張しない。Software Canvasでの新Shaderの成立も検証していない。

2026-11-06のGT8Dでの **旧 `text_shader` は303成功・360不一致、最大差154/255**。全663件をハードウェア描画で比較し、未実行・取得例外は0。この候補は未採用であり、既存の失敗・判定・許容差は維持する。[結果と採否](../../docs/performance/2026-11-06-followup-implementation.md)を参照。

Task219の `post_mask` はビルド・テスト・実GPU比較とも未実施。司令塔が上記offlineビルドと実機比較を行い、両モードの663件・全透明/全面通過・owner独立性・保持済みeffectと再生成の差・生成/更新回数・未知modeの失敗を確認する。新方式の画素互換性、実効GPU時間、性能上の採否は未確定。
