# codex による既存実装のレビュー(2026-10-29、対象 965ff94 = Task 17a まで)

5分野を並列にレビュー(codex-ask、読み取り専用)。各指摘は codex の静的レビューで、「司令塔で確認済み」と書いたもの以外は未検証。

## A: 分割を開く仕組み(sets・split・foreground・time)

**結論：正常系は指示書に沿っていますが、失敗時の処理と起動経路の合成には修正が必要です。** 特に、監視不能を分割失敗と数えて E を無効化する経路、再試行で判明した失敗を捨てる経路を重く見ます。  
以下は静的レビューによる8件です。指示書自体に由来する問題と、実機で確認すべき制約も区別しました。

1. **【高】イベント取得不能でも、3回の操作で E が無効化される**
   
   **問題・根拠：** [`UsageStatsForegroundEvents.kt:12`](app/src/main/java/com/gt8d/launcher/foreground/UsageStatsForegroundEvents.kt:12) の `available()` は権限だけで判断します。一方、問い合わせの例外・サービス不在は空リストになります（同ファイル27・47行）。[`SetRunner.kt:372`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:372) は、この状態でも `Timeout` を数えます。
   
   **再現・影響：** 権限は許可されているがイベント取得が継続して失敗する状態でセットを3回開く → 毎回3秒で再試行、4秒で失敗加算 → 実際の分割結果にかかわらず E が無効化されます。[仕様§5.5](docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md:282) の「イベントが届かない場合は時間で外し、呼び直さない」に対応できていません。
   
   **修正案：** 権限と監視の健全性を分離し、少なくとも取得エラーを「イベントなし」と区別する。監視不能時は時間解除へ切り替え、失敗加算・再試行を止める。権限あり＋取得失敗のテストを追加する。Task 20のイベント到達確認も必要です。
   
   **確度：確実。** GT8Dでこの条件が発生するかは要実機確認。

2. **【高】3秒後の再試行で返った `Failed` を捨てている**
   
   **問題・根拠：** 初回は [`SetRunner.kt:237`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:237) で失敗を処理しますが、再試行は [`同:306`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:306) で `launcher.open()` の戻り値を使っていません。
   
   **再現・影響：** 初回 `Started` → 3秒後に `Failed(EntryMissing)` または `Failed(Exception)`。通常経路では左だけ全画面への退避が行われず、後で `Timeout` として扱われます。起動経路では10秒で終了するだけなので、判明した入口消失・例外が失敗回数に反映されません。入口消失でも即時無効化されません。
   
   **修正案：** 初回・再試行で失敗処理を共通化し、同じ操作で二重加算しないよう待ちを終了する。既存の [`SetRunnerTest.kt:304`](app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt:304) は呼び出し回数だけで、再試行の戻り値変更を検証していません。
   
   **確度：確実。**

3. **【中】起動が片側だけ進んで時間切れになると、ホームへ戻らない**
   
   **問題・根拠：** [`SetRunner.kt:314`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:314) の起動タイムアウトは待ちを消すだけです。呼び出し側も [`BootSequence.kt:127`](app/src/main/java/com/gt8d/launcher/boot/BootSequence.kt:127) で、取り消していなければ記録と `Done` への変更だけを行います。
   
   **再現・影響：** 起動時に E が左アプリだけ前面化し、右が出ない → 10秒経過 → 左アプリの表示を取り消す処理がありません。[仕様§4.8](docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md:181) の「ホームのまま止まる」は保証できません。これは指示書の手順にも残る設計上の穴です。
   
   **修正案：** 起動タイムアウト時のホーム復帰を明示する。失敗加算は行わない。BootSequenceとSetRunnerを組み合わせ、片側だけ起動したケースを検証する。
   
   **確度：確実。** 実際に残る画面は E の挙動に依存。

4. **【中】保留すると `openForBoot` の経路情報が失われる**
   
   **問題・根拠：** [`SetRunner.kt:133`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:133) は実行中ならインデックスだけを保存し、[`同:386`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:386) は常に通常の `open(p)` で再開します。
   
   **再現・影響：** `openForBoot(2)` の待ち中に `openForBoot(3)` → 最初の処理が終了 → セット3は通常経路で起動されます。幕が表示され、4秒の時間切れが失敗に加算され得ます。「起動経路は幕なし・時間切れを数えない」という契約が保留によって変わります。保留したホーム復帰でも通常の幕が出ます。
   
   **修正案：** `pending` に行き先と起動種別を保持する。既存の [`SetRunnerTest.kt:406`](app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt:406) に、起動要求を保留したケースを追加する。
   
   **確度：確実。**

5. **【中】両方の「過去のResume」を、現在の分割成立として扱う**
   
   **問題・根拠：** [`ForegroundWatcher.kt:15`](app/src/main/java/com/gt8d/launcher/foreground/ForegroundWatcher.kt:15) は開始後に出現したパッケージの集合だけを見ます。パネル側も [`SetRunner.kt:273`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:273) の真偽値が一度立つと、その後の離脱を反映しません。
   
   **再現・影響：** 次のポーリングまでに左Resume → 右Resume → 別アプリResume、となっても成功します。分割が崩れた後に幕を外し、失敗回数を0へ戻すことができます。実装は指示書どおりですが、判定方式の限界です。
   
   **修正案：** 少なくとも両者の後に無関係な前面化がある場合は成功を保留する。可能ならPause等やパネルの現在状態も使う。左右が順次全画面になった場合との区別は実機で確認する。
   
   **確度：確実。** GT8Dでの発生頻度は要実機確認。

6. **【中】既存の分割を切り替えたとき、維持された側の新規Resumeが必須になる**
   
   **問題・根拠：** [`SetRunner.kt:179`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:179) で毎回基準時刻を更新し、[`ForegroundWatcher.kt:18`](app/src/main/java/com/gt8d/launcher/foreground/ForegroundWatcher.kt:18) はそれ以前の前面化を捨てます。
   
   **再現・影響：** 「YouTube｜音楽」から「YouTube｜Maps」へ変更し、E が左のYouTubeを再開し直さず維持する場合、分割が正常でも左の合図がありません。再試行・失敗加算が発生し、繰り返すと E が停止します。パネルを維持する場合にも同様の確認が必要です。
   
   **修正案：** 冷えた起動だけでなく、共通する片側を維持した切り替えで両方の通知が来るか確認する。来なければ、現在の表示状態を継承する判定が必要です。
   
   **確度：要実機確認。** 通知が省略されること自体は未確認です。

7. **【中】分割非対応への退避テストは、本番実装の退避を保証しない**
   
   **問題・根拠：** [`ActivityLauncher.kt:49`](app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt:49) の `isResizeable()` は常に `true` です。[`SetRunnerTest.kt:272`](app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt:272) はFakeだけを `false` にしています。
   
   **再現・影響：** 分割非対応アプリを設定しても、本番では事前退避に入らず E を呼びます。結果次第ではアプリの制約を E の故障として数えます。これは [`tasks/018-vendor-bridge.md`](tasks/018-vendor-bridge.md) で明示された暫定実装であり、実装者の指示違反ではありません。
   
   **修正案：** Task 20で非対応アプリを使い、表示結果と失敗加算を確認する。必要な判別方法が決まるまで、仕様の退避動作が実装済みとは扱わない。
   
   **確度：確実。** 端末での影響は要実機確認。

8. **【低】使用状況が使えない環境では、他アプリが永続的に「冷えた」扱いになる**
   
   **問題・根拠：** 本番の `markForeground()` 呼び出しは [`SetRunner.kt:290`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:290) と [`同:338`](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:338) の監視成功時だけです。[仕様の記録対象](docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md:116) である、パネルからの起動等は記録されません。
   
   **再現・影響：** 使用状況権限なしで同じアプリセットを何度開いても、暖かい記録が増えず毎回3.5秒待ちます。既存の [`SetRunnerTest.kt:348`](app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt:348) は事前に手動で暖かい記録を入れるため、この不足を隠します。
   
   **修正案：** 起動成功として記録できる箇所を接続し、権限なしでも通常操作から記録が作られることを検証する。
   
   **確度：確実。**

SetNavigator、SplitModeSelector単体の失敗回数更新、timeの通常の予約・取り消しについては、追加の具体的不具合は見つかりませんでした。担当範囲でGlobal Constraintsへの明白な追加違反、確実なリーク・スレッド競合も確認していません。


## B: 幕・端の帯・安全装置(curtain・edge・safety・diag)

結論：担当範囲には、安全装置として修正すべき問題があります。特に幕の表示例外に対する後始末と、退避中にも動くサービス起動経路は優先対応が必要です。
以下は静的レビューの指摘6件です。ファイル変更・ビルド・テスト・実機確認は行っていません。

1. **高：幕の表示で例外が出ると、安全タイマーが登録されない**
   
   **問題・根拠：** [CurtainController.kt:56](app/src/main/java/com/gt8d/launcher/curtain/CurtainController.kt#L56) は `isShowing=true` → `view.show()` → 安全タイマー登録の順です。[CurtainWindow.kt:83](app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt#L83) も、窓追加からアニメーション・帯の再追加までを後始末付きで囲んでいません。
   
   **再現・影響：** `view.show()` が例外を投げると、表示中フラグだけが残り、タイマーも `onHidden` もありません。窓追加後の例外なら削除が保証されず、呼び出し側が例外を捕捉して生存すると操作を遮り続ける経路になります。例外を捕捉しなければクラッシュします。
   
   **修正案：** 表示失敗時には追加済みの窓を即時削除し、Controller の状態・タイマー・完了通知も整合させる。`show` で例外を投げるテストと、窓追加後に失敗するテストを追加する。
   
   **確度：確実**（例外発生後の制御経路。実機での発生頻度は未確認）。

2. **高：SafetyGuard の退避中でも、起動時の自動処理が動く**
   
   **問題・根拠：** [BootReceiver.kt:10](app/src/main/java/com/gt8d/launcher/edge/BootReceiver.kt#L10) は退避状態を確認せず、帯サービスと方式の自動復帰処理を実行します。[EdgeStripService.kt:58](app/src/main/java/com/gt8d/launcher/edge/EdgeStripService.kt#L58) にも退避判定がありません。仕様 §7.2 の「次回起動時は何もせず」「方式の自動切り替え等を停止」に反します。
   
   **再現・影響：** 3回連続クラッシュで退避 → 端末再起動 → `BOOT_COMPLETED` で帯と方式判定が動きます。サービス起因の障害も、退避によって止められません。
   
   **修正案：** Receiver と Service の入口で退避状態を確認し、自動起動・方式変更を止める。退避時のブート受信と Service 再生成をテストする。
   
   **確度：確実。**

3. **中：削除した幕の View を無制限に保持する**
   
   **問題・根拠：** [CurtainWindow.kt:40](app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt#L40) の `removedViews` は強参照の集合です。[同:136](app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt#L136) で削除するたびに追加し、集合から外す処理がありません。
   
   **再現・影響：** セット切り替えを繰り返すたび、新しい `ComposeView` とそこから参照されるオブジェクトが Service 存続中ずっと残ります。常駐ランチャーとしてメモリ使用量が増え続けます。
   
   **修正案：** 二重削除防止は窓ごとの状態で管理し、削除完了後に過去の View を保持しない構造にする。
   
   **確度：確実。**

4. **中：オーバーレイ権限を後から許可しても、帯が復旧しない**
   
   **問題・根拠：** [EdgeStripService.kt:79](app/src/main/java/com/gt8d/launcher/edge/EdgeStripService.kt#L79) は帯の追加失敗を握りつぶします。一方、[同:83](app/src/main/java/com/gt8d/launcher/edge/EdgeStripService.kt#L83) の `onStartCommand` は何も再試行しません。
   
   **再現・影響：** 権限不足等で最初の追加に失敗 → 権限を付与 → Home／Panel に戻って `ensureRunning()` → 同じ Service の `onStartCommand` が呼ばれても帯は追加されません。アプリ同士の分割では、仕様上の戻り操作を失います。
   
   **修正案：** 帯の追加成功状態を管理し、再開要求時に権限と未追加の窓を確認して再追加する。権限不足時は幕も安全に省略する。
   
   **確度：確実**（初回追加に失敗し、Service が生存している条件）。

5. **中：4秒の安全期限を過ぎても、幕が150ms残る**
   
   **問題・根拠：** [CurtainController.kt:59](app/src/main/java/com/gt8d/launcher/curtain/CurtainController.kt#L59) は4000ms時点で `hide(Safety)` を呼びますが、[CurtainWindow.kt:107](app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt#L107) はそこから150ms後に削除します。その間もタッチを遮る窓です。
   
   **再現・影響：** 前面化の合図が来ない場合、最初の呼び出しから約4150msまで窓が残ります。仕様 §4.3・§7.2 と Global Constraints の「合計最大4秒」を満たしません。
   
   **修正案：** `Safety` は即時削除するか、期限までに削除が完了するようフェード開始を前倒しする。Controller の通知時刻だけでなく、実際の窓の削除時刻を検証する。
   
   **確度：確実。**

6. **中：幕のテストが、最重要の例外・接続・ライフサイクル経路を検証していない**
   
   **問題・根拠：** [CurtainWindowTest.kt:31](app/src/test/java/com/gt8d/launcher/edge/CurtainWindowTest.kt#L31) の偽 WindowManager は呼び出しを記録するだけで、View を接続しません。[同:93](app/src/test/java/com/gt8d/launcher/edge/CurtainWindowTest.kt#L93) も削除例外を外へ出さないことの確認で、計画 Task 13 の「窓追加後、描画で例外 → 削除」の検証ではありません。
   
   **影響：** 実際の Compose 初期化、自前 LifecycleOwner、表示途中の例外、Service 停止時の削除が壊れても、このテストは通ります。上記1・4・5を見逃す構成です。
   
   **修正案：** 例外注入テストに加え、実際に接続する窓で表示・削除・再表示・Service 停止を検証する。GT8Dでは幕がタッチを遮り、帯が幕より上で操作できることを確認する。
   
   **確度：確実**（テスト範囲の不足）。Android／GT8D上の実動作は**要実機確認**。

`DiagLog` の500行制限と排他制御、`SafetyGuard` の通常の連続クラッシュ判定には、今回の静的確認で追加の問題は見つかりませんでした。


## C: 画面(ui・apps・media)

部品の基本動作は整理されていますが、**分割開始中の誤転送と、設定読込前に前回セットを復元する競合は修正が必要**です。  
単体テストはありますが、Activity・外部状態・Compose をつないだ検証が薄く、現状のテストだけでは実機運用の安定性を判断できません。

1. **【高】転送タイマーが、後から始まった分割処理を無視する**
   
   **問題・根拠：** [PanelForwarder.kt:55](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/PanelForwarder.kt:55) は予約時だけ `opening()` を確認し、発火時は確認しません。仕様 §5.2 の「印があるあいだは転送しない」に違反します。
   
   **再現：** `opening=null` で全画面表示 → 1.5秒タイマーを予約 → 途中でセット切替が始まり `opening!=null` → 分割外のままタイマー発火、でホームへ転送します。Activity の一時停止が先に来れば回避されますが、それに依存する判定です。
   
   **修正案：** 発火時にも `opening()` を確認し、非nullなら完了通知を待つ。既存テストに「予約後に opening が立つ」ケースを追加する。
   
   **確度：確実。** 実機で発生するイベント順序は要確認。

2. **【高】設定読込前の復元で、別のセットを開く／前回番号を0に上書きする**
   
   **問題・根拠：** [AppGraph.kt:153](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/AppGraph.kt:153) の設定読込は非同期ですが、[HomeActivity.kt:150](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt:150) は完了を待たず起動処理を始めます。Runner は未読込時に既定設定を使い（[AppGraph.kt:115](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/AppGraph.kt:115)）、存在しない番号なら保存番号も0にします（[SetRunner.kt:156](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:156)）。
   
   **再現：** カスタム設定と前回番号を保存 → 再起動後、設定読込が遅延 → 読込完了前に「今すぐ開く」を押す、または3秒経過。既定設定の同番号が開くか、番号が範囲外ならホームに戻り、前回番号が失われます。
   
   **修正案：** 設定読込完了後に復元対象を確定してカウントダウンを始める。読込遅延を制御した Activity 接続テストを追加する。
   
   **確度：確実（競合経路）。** 発生頻度は未測定。指示書側にも読込待ちの指定が欠けています。

3. **【中】アプリ一覧で選んだ Activity と、実際に開く Activity が一致しない**
   
   **問題・根拠：** [AppCatalog.kt:28](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/apps/AppCatalog.kt:28) は起動入口ごとに項目を作りますが、[PanelScreen.kt:56](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt:56) は `cls` を捨てます。最終的に [ActivityLauncher.kt:32](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/split/ActivityLauncher.kt:32) がパッケージの代表入口を取得し直します。
   
   **再現・影響：** 同一パッケージに異なる起動入口A・Bがあるアプリで、一覧のBを押しても代表入口Aが開きます。
   
   **修正案：** 一覧からの起動は選択した `ComponentName(pkg, cls)` を使い、分割用フラグも付与する。指示書もパッケージだけを渡す指定なので、合わせて訂正する。
   
   **確度：確実（複数入口を持つアプリの場合）。**

4. **【中】権限・Wi-Fi・Bluetooth の変更が画面へ反映されない**
   
   **問題・根拠：** [PanelScreen.kt:87](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt:87)〜111 は再コンポーズ時の読み取りだけで、状態変更の購読や復帰時更新がありません。
   
   **再現：** 再生セッションのないホームを表示 → システム設定でWi-Fiを切る、または使用状況アクセスを許可 → 同じホームへ戻る。別の Compose 状態が変わらなければ、アイコンや「許可が必要」が以前の状態のまま残ります。時計の更新は子 Composable 内なので、この更新契機にはできません。
   
   **修正案：** 通信状態の通知を購読し、権限は `ON_RESUME` で再確認して Compose の状態へ反映する。
   
   **確度：確実（更新契機の欠落）。**

5. **【中】インストール状態のキャッシュが失効せず、再導入したアプリを起動できない**
   
   **問題・根拠：** [HomeActivity.kt:74](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/home/HomeActivity.kt:74)、[PanelScreen.kt:82](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/PanelScreen.kt:82) はインストール一覧を `remember(config)` で固定します。パッケージ変更では設定自体は更新されません。
   
   **再現・影響：** 設定済みアプリが未導入の状態でホーム表示 → アプリを再導入 → 同じホームへ戻る。「アプリが見つかりません」が残り、[IconGrid.kt:102](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/IconGrid.kt:102) によりタップしても起動しません。削除した場合は逆に古いボタンが残ります。
   
   **修正案：** パッケージ追加・削除・変更または画面復帰で一覧を更新する。アイコンキャッシュも対象パッケージについて失効させる。
   
   **確度：確実。**

6. **【中】パネルから起動したアプリが暖かい起動として記録されない**
   
   **問題・根拠：** [PanelActivity.kt:58](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/PanelActivity.kt:58) は起動するだけです。`markForeground` の呼出箇所は [SetRunner.kt:290](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt:290)・338 に限られ、仕様 §4.3 が求めるパネルからの起動記録がありません。
   
   **再現・影響：** 使用状況アクセスなし → パネルからアプリAを起動 → Aとパネルのセットへ切替。Aを既に使っていても冷えた起動扱いとなり、時間で外す幕が0.8秒ではなく3.5秒になります。
   
   **修正案：** 起動成功時に共通の記録処理を通す。使用状況アクセスが使えない条件で、この経路をテストする。
   
   **確度：確実。**

7. **【中】ジェスチャー・シーク・最小幅の重要な操作がテストされていない**
   
   **根拠：** [TwoFingerSwipeTest.kt:9](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/ui/gesture/TwoFingerSwipeTest.kt:9) 以降は分類関数だけを試し、実際のポインター列を試していません。[MediaCardTest.kt:141](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/ui/panel/MediaCardTest.kt:141) などの `onSeek` はすべて空です。[PanelContentTest.kt:151](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/ui/panel/PanelContentTest.kt:151) は☆なしで、寸法確認対象にもシークバーがありません。
   
   **影響：** 指の追加・離脱、子のスクロールとの共存、シーク確定値、☆付き最小幅での操作領域が壊れても検出できません。`app/src/androidTest` も存在しません。
   
   **修正案：** 実ポインター入力によるジェスチャーとシークのテスト、516dp幅・☆あり・短い画面でスクロールして全操作へ到達するテストを追加する。
   
   **確度：確実（テスト欠落）。** 各動作が実際に壊れていると断定するものではありません。

メディア監視の通常の登録解除経路には、確定的なリークを見つけませんでした。[MediaWatcher.kt:227](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/media/MediaWatcher.kt:227) の停止処理は、Android 15では基底クラスの破棄時からも呼ばれます。根拠は [AOSP の実装](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/service/notification/NotificationListenerService.java#L1210)です。

ダーク固定・配色・走行状態による分岐禁止について、担当範囲では違反を見つけませんでした。


## D: 設定・起動・配線(config・boot・link・AppGraph・Manifest)

結論：担当範囲には、起動時の復旧と設定読み込みの連携に修正すべき問題があります。特に、時間切れでもホームへ戻らない経路と、保存設定の読み込み前に既定セットを開く競合は、単体テストでは見逃されています。Task 17b 以降の未実装部分は指摘対象から除外しました。

1. **【高】起動が時間切れになっても、ホームへ戻していない**
   
   **問題・根拠：** [BootSequence.kt:127](app/src/main/java/com/gt8d/launcher/boot/BootSequence.kt#L127) は、取り消し操作がない場合、ログと `Done` への変更だけで終了します。SetRunner 側も [SetRunner.kt:314](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt#L314) で待ちを終了するだけです。仕様 §4.8 の「過ぎたらホームのまま止まる」を保証していません。
   
   **再現・影響：** E 呼び出し後、左アプリだけが前面化し、右が現れないまま時間切れになると、その左アプリが残ります。利用状況へのアクセスがなく成立を検知できない場合も、タイムアウト時に画面をホームへ戻す処理はありません。
   
   **修正案：** 時間切れでは起動待ちを終了し、ホームへ明示的に戻す。既存の [BootSequenceTest.kt:114](app/src/test/java/com/gt8d/launcher/boot/BootSequenceTest.kt#L114) は `homed == 0` を期待しているため、指示書と仕様の不整合を解消したうえで、片側のみ前面化する結合テストを追加する。
   
   **確度：確実。** 実際に残る画面は E の進行状況に依存します。

2. **【高】設定読み込み前の操作で、保存されたセットと違うアプリを起動する**
   
   **問題・根拠：** 設定は [AppGraph.kt:152](app/src/main/java/com/gt8d/launcher/AppGraph.kt#L152) で非同期に読み込みますが、runner は未読込時に [AppGraph.kt:115](app/src/main/java/com/gt8d/launcher/AppGraph.kt#L115) の既定設定を使います。起動処理は読み込み完了を待たず、保存された番号だけを [AppGraph.kt:173](app/src/main/java/com/gt8d/launcher/AppGraph.kt#L173) で渡します。
   
   **再現・影響：** 保存設定のセット1を YouTube 以外に変更して終了。次回起動で読み込みが遅れている間に「今すぐ開く」を押すと、既定セット1の YouTube が開きます。保存番号が4以上なら既定セットに存在しないため、[SetRunner.kt:157](app/src/main/java/com/gt8d/launcher/sets/SetRunner.kt#L157) の経路で最終セット番号が0に上書きされます。
   
   **修正案：** 設定を「読み込み中・利用可能・失敗」に分け、起動やセット切り替えは利用可能になるまで保留する。設定読み込みを意図的に遅延させた結合テストを追加する。
   
   **確度：確実。** 発生頻度は読み込みと操作のタイミング次第です。

3. **【中】起動画面の上限10秒が、通常経路では13秒になる**
   
   **問題・根拠：** [BootSequence.kt:60](app/src/main/java/com/gt8d/launcher/boot/BootSequence.kt#L60) の3秒カウントダウン後、[BootSequence.kt:110](app/src/main/java/com/gt8d/launcher/boot/BootSequence.kt#L110) で改めて10秒タイマーを開始します。仕様 §4.8 の「起動画面の上限は10秒」、計画の Global Constraints と食い違います。
   
   **再現・影響：** 自動カウントダウンで開始し、成立を検知できない場合、起動画面は開始から約13秒残ります。「ホームのまま使う」による取り消しも、開始から最大13秒まで待たされます。
   
   **修正案：** `start()` 時に期限を固定し、カウントダウン後は残り時間だけ待つ。Task 16 指示書自体が「開くから10秒」と指定しているため、実装だけでなく指示書・テストも整合させる。
   
   **確度：確実。**

4. **【中】設定の読み込み・退避に失敗すると、アプリ全体のクラッシュにつながる**
   
   **問題・根拠：** [ConfigRepository.kt:82](app/src/main/java/com/gt8d/launcher/config/ConfigRepository.kt#L82) のバックアップコピーには例外処理がありません。[AppGraph.kt:153](app/src/main/java/com/gt8d/launcher/AppGraph.kt#L153) の設定購読にも `catch` や例外ハンドラーがなく、`SupervisorJob` だけでは、この `launch` 内の例外は処理されません。
   
   **再現・影響：** 壊れた設定があり、空き容量不足などで `config.bak.json` を作成できないと、既定値への復旧前に例外になります。通常の設定読み込みで I/O エラーが発生した場合も同様です。「壊れた設定から復旧する」処理が起動クラッシュの入口になります。
   
   **修正案：** バックアップ失敗と設定利用の可否を分離し、購読側で I/O エラーを処理する。失敗を記録し、明示的な復旧状態に移す。バックアップ不能・読込失敗のテストを追加する。
   
   **確度：確実。** 発生条件はストレージ障害などに限定されます。

LinkRouter の判定、Manifest の宣言、Gradle 設定、v1移行・既定値については、Task 17a までの範囲で追加の具体的不具合は見つかりませんでした。Android API・端末依存については、今回独立した指摘にできる問題は確定していません。


## E: テストの質

主要な正常系と仮想時計による時間制御は押さえていますが、**安全性を保証するテストとしては不足があります**。特に、パネル転送の競合を見逃しており、幕の例外処理も計画が求めた条件を検証できていません。
指摘は7件です。読み取りのみでレビューし、ビルド・テストは実行していません。

1. **【高】転送待ちの途中で分割開始になる順序が未テスト**
   
   **問題・根拠:** [PanelForwarderTest.kt:41](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/ui/panel/PanelForwarderTest.kt:41) は、待機中に `opening` が変化するケースを検証していません。[実装:56](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/ui/panel/PanelForwarder.kt:56) のタイマーも、発火時に `opening()` を再確認しません。
   
   **再現・影響:** 分割外で表示→1.5秒の転送待ち開始→途中でセット切り替え開始→まだ分割外のままタイマー発火、という順序で、分割開始中にもホームへ転送されます。
   
   **修正案:** 待機中に `opening` を設定するテストを追加し、発火時の転送抑止と、開始状態が消えた後の転送を検証する。実装にも発火時の再確認を入れる。
   
   **確度:** 確実。

2. **【高】幕の「例外でも除去」を、別の例外で代用している**
   
   **問題・根拠:** [計画:280](/mnt/f/YLuncher-wt/review-965ff94/docs/superpowers/plans/2026-10-28-gt8d-launcher.md:280) は、追加後の描画例外でも `removeView` が呼ばれるテストを要求しています。一方、[CurtainWindowTest.kt:93](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/edge/CurtainWindowTest.kt:93) が起こすのは、除去処理そのものの例外です。
   
   **影響:** フェード開始・終了処理の `finally` が欠落しても、現在のテストは通ります。偽物の `addView` は記録するだけなので、実際のウィンドウへの接続や描画も行いません。
   
   **修正案:** 除去例外のテストを残し、追加後のアニメーション処理などに例外を注入して、同じViewの除去を必ず1回試みるテストを追加する。
   
   **確度:** 確実。なお、現在のテストは [指示書:89](/mnt/f/YLuncher-wt/review-965ff94/tasks/022-edge.md:89) には沿っており、計画から指示書への落とし込みにも問題があります。

3. **【中】起動画面の上限10秒に対し、13秒を正解にしている**
   
   **問題・根拠:** [BootSequenceTest.kt:114](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/boot/BootSequenceTest.kt:114) は、カウントダウン3秒の後、さらに10秒待つことを期待します。[仕様:181](/mnt/f/YLuncher-wt/review-965ff94/docs/superpowers/specs/2026-10-28-gt8d-launcher-design.md:181) は「起動画面の上限は10秒」です。
   
   **再現・影響:** 分割成立通知が来ない起動では、画面開始から10秒を過ぎても待ち続け、13秒で終了します。正しく合計10秒へ直すと、現在のテストは失敗します。
   
   **修正案:** 起動画面開始からの期限を検証し、「今すぐ開く」でも期限が延びないケースを追加する。
   
   **確度:** 確実。ただし [指示書:51](/mnt/f/YLuncher-wt/review-965ff94/tasks/029-boot.md:51) 自体が「開く」から10秒と指定しています。実装者の独断による期待値変更ではなく、仕様と指示書の不整合です。

4. **【中】幕を繰り返し表示した場合の解放が無テストで、Viewの保持漏れを見逃している**
   
   **問題・根拠:** [CurtainWindowTest.kt:75](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/edge/CurtainWindowTest.kt:75) 以降は単発の表示・非表示だけです。[実装:40](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt:40) の `removedViews` は、[136行](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/edge/CurtainWindow.kt:136) で除去済みViewを追加し続け、削除しません。
   
   **再現・影響:** 同じサービスでセット切り替えを繰り返すと、過去の `ComposeView` がすべて強参照で残ります。長時間稼働で保持オブジェクトが増え続けます。
   
   **修正案:** 二重除去防止を各表示の状態で管理し、表示・非表示の反復と、フェード中の再表示について、古いViewと所有者が解放されることを検証する。
   
   **確度:** 確実。

5. **【中】v1移行テストが「設定が残る」を十分に検証していない**
   
   **問題・根拠:** [ConfigCodecTest.kt:46](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/config/ConfigCodecTest.kt:46) の入力は `panelApps`・`dockApps`・`chromeDomains` が空で、`gesture` も既定値です。検証もセット・ホーム一覧・リンクのモードなどに限定されています。
   
   **影響:** v1移行時だけドメイン例外やジェスチャー設定、パネル・ドック一覧を初期化する回帰が入っても通ります。v2の往復テストでは移行分岐の不具合を検出できません。
   
   **修正案:** 各項目に既定値と異なる値を持つ固定のv1入力を追加し、移行後の設定全体を比較する。Repository経由でも保存後の再読込を確認する。
   
   **確度:** 確実。

6. **【中】実アプリの起動処理を外した後、その安全装置の接続が無テスト**
   
   **問題・根拠:** [robolectric.properties:1](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/resources/robolectric.properties:1) は全体を `TestApp` に置き換えます。[SafetyGuardTest.kt:31](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/safety/SafetyGuardTest.kt:31) は `markStable` を直接呼びますが、[LauncherApp.kt:12](/mnt/f/YLuncher-wt/review-965ff94/app/src/main/java/com/gt8d/launcher/LauncherApp.kt:12) の開始記録・10秒後の安定化・例外ハンドラー接続を検証するテストはありません。
   
   **影響:** これらの呼び出しが削除されたり、例外を既存ハンドラーへ渡さなくなったりしても、現テスト群は検出できません。
   
   **修正案:** Composeテストの隔離は維持し、実アプリ起動処理を対象とする専用テストを追加する。時計を進めて安定化を確認し、例外ハンドラーはテスト終了時に復元する。
   
   **確度:** 確実。置換自体は指示書で許可された変更です。

7. **【低】未来の前面化イベントで成功判定する、不自然なテストデータ**
   
   **問題・根拠:** [SetRunnerTest.kt:428](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt:428) は時刻250・280msのイベントを先に投入し、時計を200msまで進めただけで成功を期待します。[偽物:93](/mnt/f/YLuncher-wt/review-965ff94/app/src/test/java/com/gt8d/launcher/sets/SetRunnerTest.kt:93) は現在時刻に関係なく全イベントを返します。
   
   **影響:** 現実にはまだ取得できないイベントによって成功します。ポーリングとイベント発生順序の検証にならず、時間に忠実な偽物へ改善すると失敗します。
   
   **修正案:** 時計をイベント発生時刻まで進めてから投入するか、偽物が現在時刻以前のイベントだけを返すようにする。左右が揃う前には成功しないことも検証する。
   
   **確度:** 確実。

`@Ignore`・実時間の `sleep`・無条件成功assertによる製品テストの弱体化は見つかりませんでした。`runCatching { controller.ready() }` は、例外の再送出を許す指示と、直後のコールバック検証があるため、握りつぶしによる不正な成功とは判断しません。

`app/src/androidTest` は存在しません。Android API・実機上の描画・ライフサイクルの成立は今回確認していません。Task 17b以降の未実装部分は指摘対象から除外しました。


