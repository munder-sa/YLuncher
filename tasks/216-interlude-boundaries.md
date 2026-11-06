最初に tasks/RULES.md を読む。

# Task216 前奏・間奏の点灯境界更新

あなたは実装担当。ユーザーはこの改善と、既存テストの「点灯不変でも毎フレーム増える」という旧期待を新仕様へ更新することを明示承認した。画素・点灯数/時刻・停止再開の期待は維持。Git/ADB/依存/版/時計/アイコン/GPUコードは変更しない。

## 実装

- LyricsPaneのframe loopを、単語塗り（従来毎frame）、前奏/♪（次の点灯境界までdelay、その後frame上でposition更新）、固定表示（初期同期だけ）へ分ける。introの200ms判定、行indexの200ms判定、LyricMotion/alphaアニメは変更しない。通常windowとdetail両方に同じpositionを供給し、非current行のretained litを保持する。
- 境界は既存interludeLitDotsの数式と丸めを唯一の仕様にし、整数msで「現在より先で点灯数が最初に変わる時刻」を求める。Double端数やLong.MAX_VALUE付近も同じ点灯結果になること。必要ならmonotonicな範囲のbinary searchを使い、加減算overflowを避ける。start/end/現在が不正/空/終端なら不要な予約はしない。helperはInterludeUpdatePlan.ktへ。
- 再生速度が有限正ならpositionAtと同じ単位でdelayを計算し、境界後最初のframeで更新。再開時に最新positionを初期同期。delay中の曲/行/metadata/seek/speed/STOPの変化で古い予約をcancelし、STARTで再同期。速度0はpositionが不変なので待機のみ、負または非有限速度は従来のframe補間へfallbackして表示を壊さない。duration clampで次境界に届かない場合は無限再予約しない。
- delayの丸めで早く起床した場合は現在値を再確認し、0ms busy loopにしない。通常速度でも1秒固定poll等へ置換しない。frameUpdatesは実際にframe callbackでposition更新した回数を保ち、テストを通すため初期同期などを架空加算しない。必要なら独立した予約回数observerを追加可。

## テスト

- 既存LyricsPaneTestのcheckInterludePlaybackChanges内の「点灯不変中frameUpdates>frames」を「増えない」へ変更し、境界では更新・点灯が正しいことを追加。既存の再開後clock+=500だけで1frame進める箇所は、仮想再生時計とdelay側の時間を同じ500ms分進めるfixtureへ修正してよい。期待する点灯数/画素/保持alpha/seek/曲変更結果は変えない。
- VisibleUiLifecycleTestのlyricIntroDelay_stopsAndRechecksLatestPositionOnStartだけ、境界前400msの毎frame増加要求を「更新なし」へ。STOP中read停止/START後の同期/200msintro再判定のassertは維持。単語の毎frame増加要求はすべて維持。他の既存テスト・閾値・名前は変更しない。
- 新規InterludeUpdatePlanTestで非整数1/3境界、短区間/同時刻/逆区間、負start、終端、Long大値、速度0/.5/1/2/負/非有限、duration終端を検証。新規InterludeBoundaryUiTestで実LyricsPaneのframe/read/draw観測により点灯間の休止と境界更新、再予約キャンセル、pause/seek/resume/track変更/STARTED未RESUMEDを確認。既存画素試験もそのまま通す。

## 許可コマンド

`/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest' --tests 'com.gt8d.launcher.ui.home.LyricIndexTest' --tests 'com.gt8d.launcher.ui.home.InterludeUpdatePlanTest' --tests 'com.gt8d.launcher.ui.home.InterludeBoundaryUiTest' --tests 'com.gt8d.launcher.ui.lifecycle.VisibleUiLifecycleTest'`

allow内のみ。成功後の再実行なし。変更した既存assertとその承認範囲、検証件数/失敗、決めたことを日本語で報告。
