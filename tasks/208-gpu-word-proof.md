最初に tasks/RULES.md を読む。

# Task208 単語Shaderの実GPU画素比較プローブ

あなたは実装担当。ユーザーは7候補すべての実装検証を承認。旧Task195はSoftware CanvasがRuntimeShaderに非対応で12件失敗し保留した。今回は既存実験を変更せず、実機GPUで独立した参照と比較する専用プローブだけを作る。YLuncher本体へ配線しない。司令塔がビルド/ADB/実機実行を担当する。**このTaskではGradle/ADB/ネットワーク/Git操作をしない**（他2本が検証中）。

## 独立構成

- tools/renderprobe/以下に独立Android application Gradle projectを置く。rootのsettingsには追加しない。AGP8.7.3/Kotlin2.1.0/Compose BOM2025.01.00/activity-compose1.9.3の既存依存だけ、compile/min/target35。既存root gradlewを `-p tools/renderprobe` で司令塔が使う。新しいテストライブラリ不要。applicationId com.gt8d.launcher.renderprobe、debuggable、HOME登録や認証/ネットワーク権限なし。
- Android標準Instrumentationを同じAPKへ宣言し、targetPackageは自分自身。ProbeActivityはComposeを表示、hardwareAccelerated=true。InstrumentationがonCreate→start→onStartでstartActivitySyncし、UI更新とPixelCopy(Window,Rect,...)で候補/対照の同じ矩形を順に取得する。待機はinstrumentation/backgroundで行い、UIをブロックしない。draw/測定完了と次フレーム提出を待ち、timeoutは明示失敗。スクリーン全体や本物の歌詞/認証画面は取らない。
- Activity終了後Instrumentation result Bundleに件数/pass/fail/最大差/failedCaseと座標/old-newARGB/hardware判定を数値中心で返す。失敗を握り潰さずfinishのcodeを変える。失敗ケースも後続比較して一覧を残す。全画素RGBAの各成分誤差**1/255以下**が合格。失敗後に閾値を変えたり端を除外しない。

## 描画の比較

- 現行LyricsPane.kt/KaraokeLineのbase Text + overlay Textを独立参照としてプローブに実装。22sp Bold/Start/maxLines2/Ellipsis、overlay Offscreen + DstIn境界24dp + bottom Clearを忠実に写す。base alpha、overlay emphasisのgroup alphaと背景を両側同一にする。候補で期待画像を作らない。
- 候補はTask195の `/tmp/yluncher-lyrics-shader/app/src/main/java/com/gt8d/launcher/ui/home/LyricWordBrush.kt` を読み取り参考として、プローブ内の独立LyricWordBrush.ktに作る。RuntimeShaderは行所有1個、uniformをdrawで更新。同じTextLayoutResultのUTF16境界式、以前のlineは白/current lineは左右12dp白→透明/後のline透明、fill<=0透明/>=text.length白を維持。
- 候補文字自体へShaderBrushを適用し単語用Offscreen/DstInを除く。ただしemphasis group alphaの合成順は維持しModulateAlphaへ置換しない。TextStyle brushを毎frame再Composeする形で実験を成立させても性能採用とは主張しない。実装上drawフェーズから直接drawText(layout, brush)で比較する場合は参照も同じtext測定を使うが、現行Compose Textとの画素同等性を先にチェックするケースを必ず設ける。
- density1/1.4875/2、1行/折返し2行/ellipsis、fill0/途中/末尾/境界端、RTL混在/結合文字/色付きemoji、emphasis1/.5/0、暗色単色/色付き背景を含む。文字は全て自作のダミー文字列。最低24ケース、総数と条件が機械可読に分かるようにする。alpha量子化や色emojiで差があれば正しく失敗させる。
- 同じshaderへの2回目uniform更新の反映、別所有者shaderへ影響しないことも比較する。
- ProbeActivityにcase/old-new切替以外のUIは不要。実機が30Hzでも有効な画素比較であり、60Hz性能とは扱わない。

allowの5ファイルだけ作成。日本語で構成/ケース数/司令塔のbuild・am instrumentコマンド案/決定事項/未ビルド・未検証を報告する。新規依存取得や実機操作はしない。
