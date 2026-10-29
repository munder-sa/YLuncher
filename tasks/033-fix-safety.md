# Task 033: 修正: 幕と安全装置

最初に `tasks/RULES.md` を読み、従うこと。

レビュー `docs/reviews/2026-10-29-codex-review.md` の B1～B6・E2・E4 のうち、以下で明示した部分を修正する。
仕様 `docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md` の §4.3・§4.8(7)・§7.2、および計画の Task 13 に従う。

Task 17b (`tasks/032-link-browser.md`) がコミットされた後の状態から開始する。
他の3本の修正 Task と別の git worktree で同時実行する。
担当外のファイルを変更しない。git のコミット・リセット・ブランチ操作はしない。

パス接頭辞: `M = app/src/main/java/com/gt8d/launcher`、`T = app/src/test/java/com/gt8d/launcher`

## 作成・変更するファイル(これで全部。これ以外のファイルは作らない・変えない)

新規作成:
- `T/edge/BootReceiverTest.kt`
- `T/edge/EdgeStripServiceTest.kt`

変更:
- `M/edge/BootReceiver.kt`
- `M/edge/EdgeStripService.kt`
- `M/edge/CurtainWindow.kt`
- `T/edge/CurtainWindowTest.kt`
- `M/curtain/CurtainController.kt`
- `T/curtain/CurtainControllerTest.kt`(テストを足す。既存のテストは変えない)

## 使ってよいコマンド

読み取り・調査のコマンドは RULES の範囲で使用してよい。
ビルド・テストは次の2行だけ。全テスト `test` は実行しない。

- `/home/munde/tools/gradle-run.sh testDebugUnitTest --tests com.gt8d.launcher.edge.BootReceiverTest --tests com.gt8d.launcher.edge.EdgeStripServiceTest --tests com.gt8d.launcher.edge.CurtainWindowTest --tests com.gt8d.launcher.curtain.CurtainControllerTest --tests com.gt8d.launcher.curtain.CurtainContentTest --tests com.gt8d.launcher.edge.EdgeGestureTest --tests com.gt8d.launcher.safety.SafetyGuardTest`
- `/home/munde/tools/gradle-run.sh assembleDebug`

Gradle はバックグラウンドに回さず、終了まで待つ。

## 仕様

### 共通: 指摘を再現してから直す

各指摘について、まず現在の公開入口を通る回帰テストを追加し、
修正前の製品コードで失敗することを確認する。

テストが修正前から通った場合は、そのテストでは指摘を再現できていない。
テストの準備・失敗条件が指摘と一致するか読み直す。
一致しても通るなら、その再現条件で当該指摘は誤りとして扱い、
該当する修正をせず、報告して次の指摘へ進む。
別条件まで正しいと確認できた、とは報告しない。

コンパイルエラーは動作不具合の再現に数えない。
注入用の入口が必要な場合は、既存の動作を変えない入口だけを先に追加し、
コンパイルできる状態で期待したアサーションが失敗することを確かめる。

既存テストの名前・期待値・検証を変更しない。
この Task に、RULES の「テストを弱めない」の例外はない。

### B1 の Controller 側: 表示の失敗で幕を終わらせ、例外は外へ出さない(司令塔の決定)

`CurtainController.show` の `view.show(target)` を次のようにする。

1. `isShowing = true` にしてから `view.show(target)` を `try` で呼ぶ。
2. `Exception` を受けたら、
   - `DiagLog.d("Curtain", "show failed: $e")` で記録する。
   - `isShowing = false` にする(タイマーはまだ登録していないので、登録しない)。
   - `runCatching { view.hide(HideReason.Failed) }`(窓側の後始末が済んでいれば何もしない)。
   - `onHidden?.invoke(HideReason.Failed)` を1回だけ呼ぶ。
   - 例外は**再送出しない**で return する(呼び出し元の SetRunner は、幕なしで分割の手順を続ける。`onHidden` で開いている最中の印が消えるので、待ちで止まらない)。
3. 正常に戻ったときは、既存どおりタイマーを登録する。

理由: 再送出すると、セット切り替えの呼び出し元(Activity・帯のジェスチャー)が落ちる。通知しないと SetRunner が開いている最中のまま止まる。
オーバーレイの許可が無いと窓の追加が例外になるので、許可なしのときも、この経路で「幕なしで続ける」になる(B4 の幕の扱いはこれで足りる。SetRunner は変えない)。

### B2: BootReceiver の退避判定

`onReceive` は次の順に処理する。

1. action が `Intent.ACTION_BOOT_COMPLETED` 以外なら return。
2. `SafetyGuard.isFallbackActive(context)` が true なら return。
3. `EdgeStripService.ensureRunning(context)`。
4. 既存どおり `runCatching` 内で
   `AppGraph.selector.onBoot(AppGraph.bridge.splitAvailable())`。

退避中は AppGraph のプロパティを評価しない。

テストで方式再判定の呼び出し有無を確認するため、
既存の4の処理を `internal var bootModeCheck: () -> Unit` に格納する。
初期値は既存の `runCatching` を含む処理。
`onReceive` の最後からこれを1回呼ぶ。
テストは Receiver インスタンスのこの関数をカウンターに差し替える。
この値は companion object に置かない。

### B2: EdgeStripService の退避判定

`ensureRunning(context)` は最初に退避状態を確認し、
true なら `startForegroundService` を呼ばずに return。

`onCreate`:
1. `super.onCreate()`。
2. 退避中なら `stopSelf()` して return。
3. 既存の通知・窓・帯の初期化。

`onStartCommand`:
1. 退避中なら、後述の `releaseOverlays()` を呼ぶ。
2. `stopForeground(STOP_FOREGROUND_REMOVE)`、`stopSelf()` の順に呼び、
   `START_NOT_STICKY` を返す。
3. 退避していなければ帯の再追加処理を呼び、`START_STICKY` を返す。

退避中に初めて生成された Service では、通知・WindowManager・幕・帯を作らない。
既に動いていた Service が退避中に再開要求を受けた場合は、既存の窓も外す。

### B4: 帯の追加成功状態と再試行

左右それぞれに `leftAttached`・`rightAttached` を持つ。初期値 false。

`internal var overlayAllowed: () -> Boolean` を追加する。
初期値は `{ Settings.canDrawOverlays(this) }`。
`internal var windowManagerFactory: () -> WindowManagerFacade` を追加する。
初期値は既存の WindowManager 取得と AndroidWindowManagerFacade の生成。
テストでは Service の create 前に差し替える。

`private fun ensureStripsAttached()` を追加する。

- overlayAllowed が false なら、帯の addView を呼ばない。
- true なら、左→右の順で未追加の帯だけ addView を試みる。
- addView が正常に戻った側だけ attached を true にする。
- 片側の追加失敗で、反対側の追加を中止しない。
- 追加例外は既存どおり外へ送出しない。
- onCreate の初期化末尾と、退避していない onStartCommand から呼ぶ。
- 成功済みの側を通常の再開要求で重複追加しない。

`readdStrips()` は退避中なら return。
それ以外は、追加済みの側だけ左→右の順で removeView を試みる。
各側の removeView が正常に戻った場合のみ attached を false にする。
除去失敗時は true を維持し、同じ View を重複追加しない。
その後 `ensureStripsAttached()` を呼ぶ。

`private fun releaseOverlays()` を追加し、onDestroy と退避中の
onStartCommand から共用する。

- 左右の追加済み帯の除去を、それぞれ独立した runCatching で試みる。
- 幕の removeImmediately を独立した runCatching で呼ぶ。
- 左右の参照と attached、幕と facade の参照をクリアする。
- companion の curtainView は、この Service が公開した窓と同一のときだけ null にする。
- 二度呼んでも窓の除去を重複して試みない。

権限がない場合に CurtainController へ何を通知するかは変更しない。
この項目は帯の復旧だけを対象にする。

### B1 の Window 側・B3/E4: 表示ごとの所有権と解放

`removedViews` を廃止する。
除去済み View の集合・履歴・キャッシュを追加しない。

CurtainWindow 内に private な `WindowSession` を設け、
表示ごとに次の値を所有させる。

- `view: ComposeView?`
- `owner: OverlayLifecycleOwner?`
- `removalAttempted: Boolean` (初期 false)
- `hideRunnable: Runnable?`

CurtainWindow は `currentSession: WindowSession?` だけを保持する。
遅延処理は、その表示の Session を捕捉する。

Session の除去は次の順で行う。

1. removalAttempted が true なら return。
2. removalAttempted を true にする。
3. その Session の hideRunnable を Handler から取り消し、null にする。
4. View のアニメーション取り消しを runCatching で試みる。
5. currentSession が同一 Session のときだけ null にする。
6. 同じ View の wm.removeView を1回だけ試みる。例外は外へ送出しない。
7. finally で disposeComposition と owner.onDestroy を、それぞれ
   独立した runCatching で試みる。
8. Session の view・owner を null にする。

新たな show の最初に、古い Session を即時除去する。
古い遅延処理が新しい Session を除去したり、そのタイマーを取り消したりしない。

contentFactory、所有者の設定、setContent、addView、フェード開始、
readdStrips を後始末付きで実行する。
途中の同期例外では、その表示の Session を即時除去し、元の例外を再送出する。
後始末の例外で元の例外を置き換えない。
addView 自体が例外になった場合も、作成済み View の removeView を1回試みる。

非同期の描画例外を捕捉できる、と扱わない。

### B5: Safety ではフェード待ちをしない

`hide(HideReason.Safety)` は、その場で Session を除去する。
150ms の遅延処理を登録しない。

Ready・Timed・Failed のフェードアウトは既存どおり150ms。
フェード開始中または遅延処理中の同期例外でも、
対象 Session の除去を必ず試みる。
removeImmediately は理由に関係なく即時除去する。

根拠は仕様 §4.3・§7.2 の「最初の呼び出しから合計4秒」。
Controller の通知時刻だけでなく、removeView の呼び出し時刻を検証する。

### B6/E2: 例外注入とテストの区別

既存の `hide_removesWindow_evenIfRemoveThrowsOnce` は残す。
これは除去処理の例外テストであり、追加後の表示処理例外の代用にしない。

追加後の同期例外は、既存の readdStrips コールバックに
`IllegalStateException("readd failed")` を投げさせて再現する。
同じ View の除去が1回試みられ、所有者が DESTROYED になり、
以後 removeImmediately を呼んでも除去回数が増えないことを検証する。

実際の接続・Compose 初期化については、Robolectric 上で
AndroidWindowManagerFacade を使う追加の結合テストを設ける。
偽物の addView の記録だけで接続確認済みとしない。
Robolectric の限界により検証できない場合は、別の偽物で代用せず、
確認できない API・失敗内容を報告する。

## 手順(TDD)

1. 次のテストを指摘単位で追加し、製品コード修正前の失敗を確認する。
   Robolectric は sdk=35。Looper の時間制御を使用し、実時間 sleep は使わない。
   SafetyGuard と Service の companion の状態は各テストの前後で復元する。

2. B2: `BootReceiverTest`
   - `bootCompleted_fallback_skipsServiceAndModeCheck`
     recordStart→recordCrash 3回で退避を作り、退避状態を先に assert。
     BOOT_COMPLETED を渡して、Service 起動0回・bootModeCheck 0回。
   - `bootCompleted_normal_startsServiceThenChecksMode`
     退避解除後、Service 起動の後で bootModeCheck が1回呼ばれる。
   - `otherAction_doesNothing`
     無関係の action で、双方0回。

3. B2: `EdgeStripServiceTest`
   - `ensureRunning_fallback_doesNotStartService`
     退避中の ensureRunning で Service 起動0回。
   - `create_fallback_stopsWithoutOverlayInitialization`
     退避中に create。factory 呼び出し0回、公開幕なし、stopSelf が要求される。
   - `startCommand_fallback_releasesOverlaysAndReturnsNotSticky`
     通常生成→退避状態にする→onStartCommand。
     左右帯と表示中の幕が各1回除去され、公開幕なし、
     START_NOT_STICKY、通知除去と stopSelf が要求される。
     続く destroy で二重除去しない。

4. B4: `EdgeStripServiceTest`
   - `startCommand_permissionGranted_attachesMissingStrips`
     overlayAllowed=false で生成し addView 0回。
     true にして onStartCommand。左右各1回追加。
     再度の onStartCommand では追加回数不変。
   - `startCommand_retriesOnlyFailedStrip`
     許可あり、左だけ初回 addView が例外、右は成功。
     再開要求で左だけ再試行し、左右とも追加済みになる。

5. B1 の Controller 側: `CurtainControllerTest`
   - `showThrows_clearsStateCancelsTimersAndNotifiesOnce`
     偽の CurtainView の show が例外を投げる → `show` は例外を外に出さない、`isShowing` は false、
     `onHidden` は `HideReason.Failed` でちょうど1回、時計を 5000ms 進めても `onHidden` は増えない(タイマーが残っていない)。
   - `showThrows_thenShowAgain_works`
     1回目の show が例外、2回目は成功 → 2回目は通常どおり表示され、4000ms で Safety が1回。
     既存の偽物に「次の show で例外を投げる」切り替えが無ければ、テストの中に偽物を足してよい(既存の偽物は変えない)。

5b. B1 の Window 側・B6/E2: `CurtainWindowTest`
   - `show_readdThrows_removesSameViewOnceAndDestroysOwner`
     上記の readdStrips 例外を使う。例外の同一性も assert。
   - `show_addThrows_attemptsRemovalOnceAndDestroysOwner`
     addView が記録後に例外。対象 View の除去1回・所有者 DESTROYED。

6. B3/E4: `CurtainWindowTest`
   - `repeatedShowHide_releasesEveryViewAndOwner`
     show→Ready→150ms経過を100回。
     各 View の removeView は各1回、所有者はすべて DESTROYED。
     各反復後、CurtainWindow に古い View・Owner を保持する
     フィールドやコレクションが残っていないことを検証する。
     強参照の検証はテスト側のリフレクションで、
     Window 自身と Window が所有する Session・コレクションを調べる。
     Android のグローバル状態まで探索しない。
     GC 成功・WeakReference 消失を合否条件にしない。
   - `showDuringFade_releasesOldSessionWithoutRemovingNewView`
     A を show→Ready→149ms→B を show→1ms進める。
     A の除去1回・所有者 DESTROYED、B の除去0回。
     B を閉じて150ms後に B の除去1回・所有者 DESTROYED。
     A の Session の view・owner 参照も null であることを検証する。

7. B5: `CurtainWindowTest`
   - `safetyHide_removesSynchronously`
     show→hide(Safety)。Looper を進めずに同じ View の除去1回。
     150ms後も1回。
   - `controllerSafety_removesWindowAt4000ms`
     FakeScheduler と CurtainWindow を接続した Controller を使う。
     watcherAvailable=true で show。
     両方の時計を3999msまで進め、除去0回。
     次の1msで除去1回、Controller の isShowing=false。
     4150msまで進めても除去1回。
     Controller の安全タイマーを Window の時間進行より先に発火させ、
     Safety 後の遅延除去を見逃さない。

8. B6 の追加検証: `CurtainWindowTest` と `EdgeStripServiceTest`
   - `realWindow_showHideReshow_attachesAndDestroysOwners`
     実 WindowManager を使い、接続・Compose の配置・Ready 後の切断・
     所有者の破棄・再表示を検証する。
   - `destroy_removesAttachedCurtainAndStrips`
     実 WindowManager を使う Service を生成して幕を表示し destroy。
     幕と左右帯が切断され、公開幕が null、幕の所有者が DESTROYED。
   これらが修正前から通る場合は、当該動作の不具合修正を追加しない。
   不足していた確認を追加した結果として報告する。
   例外・リークの再現をこの正常系テストで代用しない。

9. 再現した指摘だけを仕様どおりに実装する。
10. 指定の testDebugUnitTest コマンドで全対象クラスが通るまで直す。
    修正後の再実行は最大3回。通らなければ失敗テスト名と
    エラー先頭30行を報告して停止する。
11. 指定の assembleDebug を実行する。

## この Task でしないこと

- SetRunner など、curtain・edge・safety の外の変更(B1・B4 は Controller 側の扱いで足りる)。
- B6 の要実機確認部分。
  GT8D でのタッチ遮断、帯の重なりと操作、実端末での描画・
  Service 再生成・権限変更は修正・確認済みと扱わない。
- 非同期の Compose 描画例外を全面的に捕捉する仕組みの追加。
- AppGraph、SetRunner、HomeActivity、PanelActivity、link 配下、
  Manifest、ビルド設定、テスト共通設定、既存指示書の変更。
- Task 17b のリンクフォールバックや HideReason の意味の変更。
- SafetyGuard の連続クラッシュ判定や保存形式の変更。
- 担当外の問題を発見しても変更しない。必要な変更先と理由を報告する。
- 全テスト、実機コマンド、git のコミット・リセット・ブランチ操作。

## 報告

日本語で、次を記載する。

- 指摘ごとに、再現テスト名・修正前の失敗内容・修正後の結果。
- 修正前から通ったテストと、修正を見送った指摘・再現条件。
- 実際に実行したコマンド全文。
- 各実行のテスト件数・失敗件数、assembleDebug の結果。
- 作成・変更したファイル名。
- Robolectric で確認した範囲と、確認できなかった実機依存部分。
- 担当外で必要になった変更先と理由。
- **決めたこと**。なければ「決めたこと: なし」。
