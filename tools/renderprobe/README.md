# 単語描画の実GPU比較

YLuncher本体とは独立した検証アプリ。現行のText＋Offscreen/DstInマスクと、TextへのRuntimeShader適用を、同じ文字配置・背景・進捗で比較する。ダミー文字のみを使い、認証・通信・メディアの権限は持たない。本体から参照しない。

プロジェクトルートからビルドする。

```sh
/home/munde/tools/gradle-run.sh --offline -p tools/renderprobe -Pandroid.useAndroidX=true assembleDebug
```

生成物は `tools/renderprobe/build/outputs/apk/debug/YLuncherRenderProbe-debug.apk`。司令塔が実機へ導入後、以下を実行する。ADB接続先はその都度確認する。

```sh
adb shell am instrument -w -r com.gt8d.launcher.renderprobe/.ProbeInstrumentation
```

同一window内の対象矩形だけをPixelCopyで取得し、実コンテンツ描画後のframe commitを2回確認する。全画素RGBAの各成分差が1/255以下、文字配置・省略・カラー絵文字・shader所有者の条件を満たす必要がある。色差の失敗は後続ケースも実行する。同種の描画/取得timeoutが2回続く場合は、両失敗と未実行数を残して終了する。

`adb` 自体の終了コード0は合格を意味しない。最終Bundleの `count=planned=663`、`fail=0`、`unrun=0`、`hardware=1`、fatalなしをすべて確認する。各ケースは `casesJson` にあり、失敗時の座標・最大差・old/new ARGBも残る。

2026-11-06のGT8Dでは **303成功・360不一致、最大差154/255**。全663件をハードウェア描画で比較し、未実行・取得例外は0。この候補は未採用であり、性能の改善を示すツールではない。[結果と採否](../../docs/performance/2026-11-06-followup-implementation.md)を参照。
