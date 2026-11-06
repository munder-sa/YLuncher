最初に tasks/RULES.md を読む。

# Task219 通常Text描画後の単語マスク・実GPUプローブ

あなたは実装担当。ユーザー承認の別方式を独立プローブで検証する。既存tools/renderprobeの不採用Text Shaderモードと663ケースを残し、post_maskモードを追加する。本体へ配線しない。既存失敗/許容誤差1/255を変更しない。**この委譲ではGradle/ADB/Git/ネットワークを実行しない**。他の2委譲がGradleを使う。司令塔が後でbuild/実機比較する。

- `adb shell am instrument -w -r -e mode post_mask com.gt8d.launcher.renderprobe/.ProbeInstrumentation` で新モードを選べるようにする。無指定は旧モード、旧実装と結果判定を維持。未知modeは失敗。結果Bundle/JSONにmodeを明記する。
- 新候補は参照と同じ通常Textを描き、そのRenderNode内容をRenderEffect.createRuntimeShaderEffectのinput shaderとして受け取る。文字自体へのShaderBrush適用はしない。base Text/overlay Text、group alpha、字形/色/22sp Bold/maxLines2/Ellipsis/RTL/clipを現行参照と同一にする。
- overlayの通常Textを描いた結果へ、既存の単語境界と同じalphaを掛ける。fill<=0透明、>=length全通過、中間は既存TextLayoutResultの行/UTF16境界/24dp白→透明/後の行Clearを保持。shader入力のpremultiplied RGBA全成分をalpha倍する。絵文字の色を置換しない。所有者は行単位で共有しない。
- RuntimeShader uniformを変更した後のRenderEffectが古い値を持つ可能性を検証する。同じownerに対する複数更新と別ownerの独立性を663ケースの既存構造で比較する。更新後のeffect再生成が必要なら明示的に生成し回数を数える。1frame毎allocationがある場合は報告し、性能向上を断定しない。古いeffectを実際に描いても新uniformで勝手に変わると仮定しない。
- 既存663ケースをpost_maskでも全件実行し、同一矩形/レイアウト/画像比較/2framecommit/timeout/fail集計を使う。normal fully white/transparentも含む。画素差を丸めたり端/色emoji/alpha0を対象から除外しない。旧ケースの文字や期待値は変更しない。
- オブジェクト構造の共通化は新モード追加に必要な最小範囲。無指定旧モードの経路と判定を追えるようにする。候補effect生成数・uniform更新数を数値で結果へ記録。新Shaderに対しSoftware Canvasでの成立は主張しない。
- READMEへ新モードの実行方法・未検証・Offscreenが残ることを追記。記載するbuildは既存offlineコマンドだけ。プローブに本物の歌詞/認証値/通信権限を追加しない。

allowの既存ProbeActivity.kt/READMEと新規PostRenderWordMask.ktだけを変更。ビルド/テストは0回として、決めたこと・変更範囲・司令塔の確認点を日本語で報告。
