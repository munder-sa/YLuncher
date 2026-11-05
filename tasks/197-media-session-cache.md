最初に tasks/RULES.md を読み、従う。

# Task197 MediaWatcherの通知単位更新

ユーザーは負荷分析の中優先4点から効果のある改善を試し適用することを承認。あなたは実装担当。司令塔は別worktreeで作業する。公開API、セッション選択規則、位置補間、歌詞識別、transport、通知権限を維持する。

## 変更可能ファイル

- app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt
- 新規 app/src/main/java/com/gt8d/launcher/media/MediaSessionCache.kt（必要な場合のみ）
- 新規 app/src/test/java/com/gt8d/launcher/media/MediaWatcherTest.kt

## 要件

1. 現在は全通知で全controllerのmetadata/playbackStateとPackageManagerを読む。初回/セッション集合変更時に必要なsnapshotを作り、再生状態callbackでは通知されたcontrollerの再生状態だけ、metadata callbackでは当該metadataだけを更新する。callback引数を利用し、nullも正式な変更として扱う。単に古い情報を使い続けて処理を省かない。
2. 全snapshotから既存pickSessionを呼ぶ選択規則は変更しない。同一packageの複数セッションを独立管理し、transportは選ばれたcontrollerへ送る。セッション破棄時は該当entryを除外し再選択。セッション差替え/stop後にキュー済みの古いcallbackが届いても無視する。
3. appLabelはpackageごとにキャッシュ。locale変更やpackage更新で恒久的に古いラベルを残さない。最低限、セッション集合更新/再startでキャッシュを破棄し、変更時の無効化方法を実装・報告する。Android receiverを増やす必要がある場合はこのファイル内でライフサイクルと登録解除を対にする。
4. キャッシュは現在のsession/稼働期間に限定し、metadataのartworkをcopy/recycleしない。停止・削除後のbitmap参照を残さない。UI向けNowPlayingの型/出力と既存lyricsIdentityを維持し、フレーム時計を変えない。通常経路への高頻度ログ追加なし。
5. コール数を数えられるテスト用境界を最小限設ける。3セッション＋100件の位置通知で、初期化後の無関係metadata/再生state/ラベル読取が増えないことを実測。metadata変更、null、favorite、pause/resume/seek、再生アプリ切替、同一pkg複数、破棄、古いcallback、stop/startを検証する。可能ならRobolectricのMediaSessionを用いた実配線テストを含める。新規依存や既存テスト変更は禁止。

## コマンド

- 状態を変えない読み取り。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.MediaWatcherTest' --tests 'com.gt8d.launcher.media.NowPlayingTest' --tests 'com.gt8d.launcher.media.LyricsIdentityTest' --tests 'com.gt8d.launcher.media.LyricsMetadataProbeTest'`

先に回帰/コール数テストを用意し、実装と対象検証。成功済みテストを再実行しない。ビルド/APK/ADB/Git更新は司令塔が別Taskで行う。結果・実コマンド・件数・決定・未確認を報告。
