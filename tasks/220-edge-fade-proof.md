最初に tasks/RULES.md を読む。

# Task220 歌詞領域の上下端フェード・範囲限定プローブ

あなたは実装担当。ユーザー承認済みの独立実験。本体LyricsPaneや旧プローブを変更しない。既存tools/renderprobeへ新しいEdgeFadeActivity/EdgeFadeInstrumentationだけを追加する。Gradle/ADB/Git/ネットワークは実行しない。司令塔がbuild/実GPU比較する。

## 方式

- 全内容をOffscreenへ描き、上下32dpの既存lyricEdgeBrush相当のDstInを全領域にかける参照を作る。ダミーの複数Text/単語塗り/行移動と拡大を、既存LyricsPaneのText/alpha/位置/単語境界と同じ方式で表示する。背景は外側に置く。
- 比較候補は内容を1回GraphicsLayerへrecordし、上端/中央/下端の互いに重ならないclipへdrawLayerする。上端と下端だけにその矩形のsaveLayerと同じ全画面座標gradientのDstInを適用、中央はマスクなしで描く。これは「3回の再描画命令が増える」懸念を含む**試験用仮説**で、採用方式と決めない。各行へ別々にalphaを掛ける方式は、行の重なり時の不一致を避けるため使わない。
- candidateは同じframe内で全内容のrecordを1回だけ行い、3領域でTextを再compose/再measureしない。先に2端だけ独立bufferになることをコード/数値で追えるようrecord/drawLayer/saveLayer回数と合成領域面積を結果に記録。実メモリ使用量の削減と断定しない。高さが2端幅以下なら従来の全体合成へfallbackし、一致を検証する。
- 参照・候補のgradient/clip/alphaを独立に判定できるよう、候補で期待画像を作らない。背景を黒で塗ってごまかす、端のフェードをなくす、RGB許容差を広げることは禁止。

## 画素比較

- `am instrument -w -r com.gt8d.launcher.renderprobe/.EdgeFadeInstrumentation`。同一window内の対象領域をPixelCopy、hardware必須、2framecommit待ち（既存ProbeActivityの修正済みパターンを参考）、mainを待機で塞がない。スクリーン全体や他アプリは取らず、bitmapは回収して端末へ画像を保存しない。
- 少なくともdensity1/1.4875/2、複数行/ellipsis/結合文字/色emoji/RTL、境界を跨ぐ行、行の重なり、scale1/.9、途中のalpha1/.5、暗色/色付き背景、単語進捗0/途中/全塗り、端が交差する小高さを組み合わせた計96件以上。すべて自作ダミー文字。条件をJSONに残す。文字layoutとclip領域も照合する。
- 全画素RGBA各成分差1/255以下。失敗ケースも続行して成功/失敗/未実行/最大差/座標をBundle・casesJsonへ。描画/取得同種timeout2連続だけ明示中止。activityは終了し結果にhardware/cleanup状態を記録。失敗後も閾値やケースを変えない。

## 計測モードの準備

- EdgeFadeActivityの明示intent extra `mode=perf` と `strategy=full|bands|none` で、同じ固定ダミー内容/サイズ/文字/word進捗の毎フレーム変化を再現。noneはフェードを除いた寄与診断専用で採用候補ではない。知らない値は明示失敗。
- 計測内容は1つの固定scene（例:320dp×400dp、現端末で画面に収まることを司令塔確認）。word進捗はdrawで読むStateとframe clockから更新し、毎frameのcomposition/measureを避ける。background・文字数・scale・densityは全strategyで同じ。開始/フレーム数だけlogcatへ数値出力し、永続ログ/UIトグルは不要。CPU/RenderThread計測は司令塔が同じAPKで交互に行う。
- プローブの成功がそのまま本体や実車60Hzの改善を意味しない。本体へは配線しない。

allowのManifestと新規EdgeFadeProbe.kt/EDGE_FADE.mdだけを編集。旧663ケース、既存ManifestのActivity/Instrumentationを維持。build/test0回として、実行方法/条件/決めたこと/未確認を日本語で報告する。
