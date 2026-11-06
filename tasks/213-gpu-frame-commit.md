最初に tasks/RULES.md を読む。

# Task213 GPUプローブのフレーム提出待ち修正

実機のTask212 APKはProbeActivityが前景でクラッシュなしだが、25件の進捗に到達せず描画待機中。司令塔が検証プロセスだけforce-stopし、/tmp/yluncher-gpu-proof-run1.txtを保持した。まだ画素互換の結論は出していない。

原因のコード根拠: Android15 ViewRootImpl.performDrawは **addFrameCommitCallbackIfNeeded()の後にdraw()** を呼ぶ。現在のafterWindowDraw/registerFrameCommitCallbackはdraw中なので、そのframe用callback採取に間に合わず、callbackを待ってから次invalidateする循環になる。公式 https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/view/ViewRootImpl.java のperformDrawとcaptureFrameCommitCallbacksを司令塔が確認した。

- ProbeActivity.ktだけ修正。callback登録直後に次のframeを確実に要求するか、登録を次のdrawより前へ移す。現行コンテンツが描かれた後に2回の実commitを確認する条件は維持。単なるsleep/2vsync待ちへ弱めない。stale requestの拒否を維持。
- 最初の5ケースは毎ケースprogressを出す。その後は従来25件ごとでよい。失敗行があれば固定エラー識別子を進捗にも含めて、初期化問題が見えるようにする（ダミー文字のみ）。最終全caseJsonは維持。
- 同じ描画/PixelCopy timeoutが2件続く場合は両失敗を保持してfatal終了し、残りunrunを明記する。これは成功/skip扱いではない。画素差は全663ケース続行。閾値1/255/全画素RGBA/文字条件/旧参照は変更不可。
- .codex.confは司令塔が生成物の限定除外を追加済み。541件は .gradle13/build528 と分類照合済み、実装逸脱ではなかった。ビルド1回、実装修正を伴う失敗再実行のみ。

許可コマンド:
`/home/munde/tools/gradle-run.sh --offline -p tools/renderprobe -Pandroid.useAndroidX=true assembleDebug`

ADB/Git/他ファイル変更なし。ビルド後は司令塔へ戻し、実機成功とは報告しない。
