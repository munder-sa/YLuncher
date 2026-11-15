# 調査: メーター内地図のブラックアウトとナビ連携(2026-11-15)

方策書 `code_artifact.md`(リポジトリ直下、未追跡)の「4. 事前調査手順」Step 1〜4 を、読み取りだけで実施した結果。端末の設定・アプリ・コードは変えていない(ブロードキャストの試し撃ちもしていない)。

方法: ワイヤレス adb(root シェル)で `getprop`・`ps`・`pm list`・起動スクリプト・logcat を取得。バイナリと APK は `exec-out cat` で取り出して SHA-256 の一致を確かめ、リポジトリ外で解析した(ネイティブは `readelf` + capstone の逆アセンブル、APK は SDK の `dexdump -d`)。実機は DHU(`gt8d_1080p60.ini`)へ投影中だった。

## 結論(判定ゲートに対して)

- **方策書の前提「変換デーモンが Navigation Focus を無条件に奪う」は、Android Auto モードでは成り立たない。** フォーカス要求を送る関数はあるが、どこからも呼ばれていない。したがって **Case A(抑止プロパティ)は該当なし**。
- 実車でのブラックアウトの原因は未確定。有力な仮説は「車がメーターの地図枠を投影側(クラスター表示)に明け渡すが、GT8D は何も送らない」。確かめるには、車(または DHU のクラスター有効設定)につないだときのサービス一覧の記録が要る。
- **純正画面への切り替え(Phase 2)は、root も新しい権限もなしで実現できる見込み**: ブロードキャスト `com.suding.main.ACTION_EXECUTE_AT_CMD`(extra `extra_at_cmd` = `AT#CB`)。AIDL の再現は不要。未試行。
- **Google マップの右左折の連携(Phase 3)は、純正(MainAiBox)に実装済みだが、効くのは有線 CarPlay モードだけ。** Android Auto モードのデーモンには受け口も送信処理も無い。

## Step 1: プロパティ・プロセス・パッケージ

| 対象 | 事実 |
|---|---|
| モードの決定 | `/system/bin/sd-reverse.sh` が `/sys/devices/platform/soc/soc:qpdata_driver/cp_aa` を読む。`2000` なら Android Auto(`sdAutoReverse -c sd_msm6350`、`persist.carplay.auto=2`、USB は `autoReverseTemp`)。それ以外は有線 CarPlay(`sdCarplaySvc_wire`、`persist.carplay.auto=1`)。`persist.sdcarplay.reverse.disable` が `2` なら無線(`sdCarplaySvc`)。実機は `cp_aa=2000`(Android Auto) |
| Android Auto のデーモン | `/system/bin/sdAutoReverse`(120KB)+ `/system/lib/libSdAutoReverse.so`(8.4MB、32bit ARM)。logcat のタグは `SdAAReverse` |
| CarPlay のデーモン | `/system/bin/sdCarplaySvc_wire` + `/system/sdCarplaySvc_wire/lib/`(`libSdBridge.so`・`libSdCarplay.so` など) |
| その他 | `sd_carplay -c xiaoqiang`(`com.suding.speedplay` 用、sd-link.sh)、`sdsdk816`(Bluetooth モジュール)、`sd_mdnsd` |
| 制御アプリ | `com.carsyso.main`(MainAiBox、uid 1000)。デーモンとはローカルソケット `com.suding.aibox.local.socket.server` で `AT#C?` コマンドをやりとりする(サーバーは MainAiBox の `MainSocketServer`) |
| ナビ・フォーカス系のプロパティ | **該当なし**。`getprop` 1742 件のうち、ナビのフォーカスやナビチャネルを切り替えるものは無い。`sys.suding.navi.audio`(音声)と `sys.launcher.widget.navi.display.id` だけ |

## Step 2・3: 静的解析

### Android Auto のデーモン(libSdAutoReverse.so)

Google の受信側ライブラリ(ヘッドユニット用の GAL receiver-lib)を、スマホ役に転用した作り(`sendFakeVersionResponse`・`sendFakeServiceDiscoveryRequest`・`Fake serviceId` など)。シンボルが残っている。

- `Controller::sendNavFocusNotifyCmd(int)`(0x201308): `MESSAGE_NAV_FOCUS_REQUEST`(13)を送る。引数 0 なら `NAV_FOCUS_NATIVE`(1)、それ以外は `NAV_FOCUS_PROJECTED`(2)。
  - **呼び出し元が無い**: `.text` 全体の BL/BLX を走査して 0 件、PLT の登録なし、関数アドレスを指すデータは動的シンボル表だけ。`sdAutoReverse` 本体が取り込むのは `auto_reverse_*` の C 関数だけで、これも含まない。
  - `Controller::setNavigationFocus(NavFocusType)`(通知 14 を送る側)も同じく未使用。`SourceController::navigationFocusCallback` は中身が `bx lr` だけ。
- `NavigationStatusEndpoint`: 車がナビ状態のサービスを申告すればチャネルを開く(`notifyDiscoveryInfo`)。ただし中身は受信側の解析コード(`handleNavigationNextTurnEvent` など)で、**右左折のイベントを作って送る処理は無い**(`NavigationNextTurnEvent` などの生成は `routeMessage` の受信解析だけ。`start()`・`stop()` も呼び出し元なし)。
- `auto_reverse_back_home()` → `VideoSink::setFakeVideoFocus(2, 0)`: `MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST` を NATIVE で送る(= 車の純正画面へ戻す)。`sdAutoReverse` の `AT#CB` の処理(`carplay_back_home:H264_start=%d iconVisible=%d`)から呼ばれる。
- `sdAutoReverse` が受ける AT コマンド: `AT#CA`(接続状態)・`CB`(純正画面へ)・`CC`(wifi_adb.sh)・`CD`(版)・`CE`(sync)・`CF`(通話状態)・`CG`・`CK`(ACC)・`CL`〜`CS`。**`AT#CW`・`AT#CX`(ルート案内)は無い**。

### MainAiBox(com.carsyso.main)

- `MainService$MainServiceReceiver.onReceive` が受けるブロードキャスト:
  - `com.suding.main.ACTION_EXECUTE_AT_CMD`(extra `extra_at_cmd`)→ `MPUCarPlayManager.sendATCommand` でデーモンへ。
  - `com.suding.main.ACTION_EXECUTE_SH`(extra `cmd`)→ uid 1000 で実行(`tools/gt8d-usb-toggle` が、通常アプリから届くことを実機で確認済みの受け口)。
- `MPUCarPlayManager.requestReverseCarPlayReturnNativeUI` = `AT#CB`。AIDL では `IMainSdkSmartBoxCarPlayServer.requestReverseCarPlayReturnNativeUI(String)`。
- `IMainSdkSystemServer.requestSwitchOriginalMpu` などの実体 `SystemBinder` は、この版では中身が空(呼び出しも文字列も無い)。**車の画面への切り替えは SystemManager ではなく `AT#CB` の経路。**
- `IMainSdkNaviServer`(`reportNaviActive`・`reportNaviTurningDirection`・`reportNextIntersectionDistance`・`reportNextRoadName` ほか)は存在するが、実体 `NaviBinder` も空。**AIDL でナビ情報を渡しても車へは流れない。**
- 純正のナビ連携(`com.carsyso.main.misc.manager.navi`):
  - `MapNotificationListenerService`(マニフェストに宣言済みの通知リスナー)が `com.google.android.apps.maps` の通知を `SimpleGMapParser` で解析し、`RouteGuidanceCW`(状態・残距離)と `RouteGuidanceCX`(曲がる方向・道路名)を作る。ほかに Baidu・Tmap・Vietmap・Waze・Yandex 用のクラスがある。
  - `NaviInfoManager.onReceiverNaviInfoJsonString` が JSON にして `AT#CW`・`AT#CX`・`AT#CY0/1` で送る。設定 `Settings.System` の `setting_key_send_navi_info_to_car` が有効なときだけ。
  - **実機では無効**: 設定は未設定(null)、`enabled_notification_listeners` に MapNotificationListenerService は入っていない。
  - 受け取るのは CarPlay のデーモンだけ: `sdCarplaySvc_wire` に `AT#CW`・`AT#CX`・`AT#CY` があり、`libSdBridge.so` が iAP2 のルート案内(`iap2_send_route_guidance_updates`・`iap2_send_route_guidance_maneuver_info`)として車へ送る。車が対応していると `suding.carplay.reverse.has.routeguidance` が立つ。

### CarPlay のデーモン(参考、実機は Android Auto モードのため未実測)

- `libSdBridge.so` に CarPlay のモード変更(`changeModes`、`appStates`、`turnbyturn`)の処理があり、「switch acc turnbyturn」「switch dev turnbyturn」「turnByTurn no application 0/1」、プロパティ `persist.suding.carplay.normal.routeguidance.start` を持つ。CarPlay モードでのブラックアウトは、この turn-by-turn の持ち主の扱いが候補になる(分岐の中身は未解析)。

## Step 4: DHU 接続時の記録

現在の DHU(クラスター無効)との接続を `logcat -s SdAAReverse` で確認した。

- 送受信したメッセージ: VERSION、AUTH_COMPLETE、SERVICE_DISCOVERY、CHANNEL_OPEN ×7、MEDIA_SETUP/CONFIG/START、AUDIO_FOCUS_REQUEST ×1、VIDEO_FOCUS_NOTIFICATION、KEY_BINDING。**`MESSAGE_NAV_FOCUS_REQUEST` は 0 件**(静的解析と一致)。
- DHU が申告したサービスは、センサー・映像(`DISPLAY_TYPE_MAIN`)・入力・音声 2 本・マイク・ベンダー拡張。ナビ状態のサービスは無く、デーモンは `NavigationStatusEndpoint ... service is not found, and stop` を記録。

## 実車の挙動についての外部情報(未検証)

- 60 系 RAV4 は、純正の Android Auto で 12.3 インチメーターに地図を全画面表示でき、CarPlay でもメーターに地図が出る(Apple のマップ固定)との報告がある。つまり**車はメーターの地図枠を投影側に渡す作り**。
- クラウン スポーツ + 他社の AI Box(CarPlay)で、メーター内の地図だけが真っ黒になり、AI Box の USB を抜くと戻るという報告がある。
- 以上から、ブラックアウトは「ナビのフォーカス」ではなく「メーター用の表示(Android Auto ならクラスター表示のサービス、CarPlay なら別画面のストリームや turn-by-turn の状態)を車が投影側に割り当て、Box が中身を送らない」ことで起きている可能性が高い(仮説)。

## 追加確認(同日、ユーザー承認「AT#CB の試し撃ち GO」)

### AT#CB の試し撃ち(DHU 接続中、1 回)

`am broadcast -a com.suding.main.ACTION_EXECUTE_AT_CMD -p com.carsyso.main --es extra_at_cmd AT#CB`(12:24:35)

| 時刻 | 記録 |
|---|---|
| .652 | MainService `EXECUTE_AT_CMD_ACTION atCmd=AT#CB` |
| .653 | デーモン `carplay_back_home:H264_start=1 iconVisible=1` → `MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST`(mode: `VIDEO_FOCUS_NATIVE`、reason: UNKNOWN)を送信 |
| .654 | DHU から `MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION`(focus: 2 = `VIDEO_FOCUS_NATIVE`) |
| .655 | デーモンが `MEDIA_MESSAGE_STOP`、エンコーダーを片付け(`OMX-VENC: Component Deinit`) |

- **切断なし**: 3 分後も `sys.suding.reverse.connect=1`、USB は `18D1:2D00` のまま、DHU のプロセスも応答あり。`sys.suding.rplay.ui` が `foreground` → `background` になった。ByeBye などの終了系メッセージは出ない(送るのは映像フォーカスの要求だけ)。
- **DHU の画面は車側の表示に切り替わった(ユーザーが目視で確認)**。コンソールの出力は未確認。DHU を投影に戻すのは DHU 側の `focus video on`。Box 側から投影へ戻す AT コマンドは無い(車側の操作で戻る)。
- 受け口は `MainService.onCreate` が 1 つの IntentFilter(`ACTION_EXECUTE_SH` と `ACTION_EXECUTE_AT_CMD`)で `registerReceiver(receiver, filter)` したもの。`ACTION_EXECUTE_SH` は通常アプリから届くことを `tools/gt8d-usb-toggle` で確認済みなので、`AT_CMD` も同じ条件で届く(今回の送信は adb の root シェルから。通常アプリからの送信は未実測)。

### `VendorBridge.switchToCarScreen()` の仕様(確定案)

- 送信: `Intent("com.suding.main.ACTION_EXECUTE_AT_CMD").setPackage("com.carsyso.main").putExtra("extra_at_cmd", "AT#CB")` を `sendBroadcast`。権限の追加なし。
- `carHomeAvailable`: `com.carsyso.main` が入っていること(受け口は実行時登録なので、マニフェストからは判定できない)。
- 戻り値: 送信できたら true(応答は返らない)。車に未接続のときはデーモン側で何も起きない(`auto_reverse_back_home` はセッションが無ければ即 return)。
- 動作: 車へ映像フォーカスを NATIVE で要求 → 車が純正画面に切り替え、投影の映像は止まる。接続・音声のチャネルは維持。投影へ戻るのは車側の操作。
- 対象は Android Auto モードで実測。CarPlay のデーモン(有線・無線)にも `AT#CB` と `carplay_back_home` があるが未実測。
- 実装前に残る確認: 通常アプリ(YLuncher)からの送信、実車での見え方。

### DHU のクラスターの申告(未実測)

- **DHU は起動し直されていなかった**: 確認時、DHU のプロセスは 11:40:47 起動の `-c config\gt8d_1080p60.ini` のままで、GT8D 側にも新しい接続の記録が無い(サービス一覧は 1 回だけ)。クラスター有効での実測は未実施。
- DHU 2.0 の実行ファイルから読んだ設定キー: `instrumentcluster = true` は `NavigationStatusService`(右左折のメタデータ)だけを足す。メーター用の第 2 映像シンクは別で、`[display]` セクションを追加して `name`(必須。無いと「Secondary display must specify a name, skipping」)と `displaytype = cluster`(ほかに `main`・`auxiliary`)を書く。ほかのキー: `resolution`・`dpi`・`realdpi`・`framerate`・`cropmargins`・`viewingdistance`・`uitheme`・`inputmode`・`widgettype`・`startupfocus`。
- `config/gt8d_cluster.ini` を、`instrumentcluster = true` + `[display] name = Cluster / displaytype = cluster / 800x480` に更新した。

### デーモンのクラスター映像シンクの扱い(静的解析)

- `VideoSink::notifyDiscoveryInfo`(0x2048b4)は、サービス一覧を順に見て、`media_sink_service` を持ち、**`MediaSinkService` の +0x34 の値が自分の `[+0x50]` と一致**し、コーデックが H.264 BP(3)または `video_configs` が 1 件以上のものだけを採用する。+0x34 は `display_id` と読める(`InputSource` が同じ形で `iss.display_id():0 mDisplayId:0` と記録している)。`display_type` は見ていない。
- 実行時の記録では `VideoSink` の応答は 1 回だけ(インスタンスは 1 つ、対象は display_id 0)。
- したがって、**車がクラスター用の映像シンク(display_id が 0 以外)を申告しても、デーモンはそのチャネルを開かず、映像フォーカスも要求しない**見込み。「開いたまま H.264 を送らない」形ではなく、「クラスターのシンクには一切応じない」形。+0x34 が display_id であることと、この見込みは、DHU のクラスター有効での実測で確定させる。
- 車側がこれをどう扱うか(応じないスマホに対して純正の地図を出し続けるか、黒くするか)は車の実装しだいで、実車の記録が要る。

### CarPlay モードの比較(静的解析のみ)

- 有線 CarPlay のブリッジ(`/system/sdCarplaySvc_wire/lib/libSdBridge.so`)は、車へ送る SETUP で自分を `iPhone12,1` と名乗り、機能の一覧に `uiContext`・`viewAreas`・`cornerMasks`・`focusTransfer` と **`altScreen`** を入れる(メーカーが「LG Electronics」のときだけ外す。記録は `add AltScreen` / `LG no AltScreen`)。`altScreenURLs` として `maps:/car/instrumentcluster`・`/map`・`/instructioncard` を持つ。
- つまり **CarPlay モードでは、Box が「メーターの地図に対応している iPhone」と申告する**。車がメーターの枠を CarPlay 側に渡せば、中身を描く Apple のマップが無いので黒くなりうる。CarPlay モードのほうがブラックアウトを避けやすい構造ではない(むしろ直接の原因になりうる。実測なし)。
- 無線 CarPlay のブリッジ(`/system/lib/libSdBridge.so`、別ビルド)にも `altScreen`・`altScreenURLs`・`maps:/car/instrumentcluster*` がある(`LG` の分岐の記録文字列は無い)。
- 右左折の連携は**有線だけ**: `AT#CW`・`AT#CX` と `iap2_send_route_guidance_*` は有線のデーモンとブリッジにだけある。無線のデーモン(`sdCarplaySvc`)の AT コマンドは `CA`〜`CV`・`CY` で、`CW`・`CX` が無い。
- 有線の送信は、車が iAP2 のメッセージ 0x5201/0x5202 に対応しているときだけ(非対応なら「msg id : 0x5201 is not supported by dev usb」で送らない)。`persist.suding.carplay.normal.routeguidance.start` も見ている。非案内時は `RouteGuidanceState` が 0 のとき `AT#CY0` を送る。
- turn-by-turn の持ち主の切り替え(`switch dev turnbyturn` / `switch acc turnbyturn`)は、車から来たモード変更に応じる処理で、分岐の条件は未解析。

## DHU クラスター設定での実測(2026-11-15 15:06 と 15:15、`gt8d_cluster.ini`)

- 15:06 の接続(ini は `[display]` + `name`)と 15:15 の接続(`[display:Cluster]` + `displaytype = cluster`、DHU のウィンドウは 2 枚)で、**ServiceDiscoveryResponse の中身は同じ**だった。サービスは 1 センサー、2 映像(`display_id: 0` / `DISPLAY_TYPE_MAIN`、1920x1080)、3 入力、4・5 音声、6 `navigation_status_service`(IMAGE、100x100)、7 電話状態、8 ベンダー拡張、9 マイク。**`DISPLAY_TYPE_CLUSTER` の `media_sink_service` は無い**。
- GT8D は ch 6(NavigationStatus)を開き、DHU が成功を返した。ただし開いたあとにナビ情報や `NAV_FOCUS` を送る記録は無い。映像は ch 2(メイン)だけで、`VIDEO_FOCUS_PROJECTED` → `MEDIA_MESSAGE_START`。クラスター用のチャネルを開く処理も、クラスター用のフォーカス要求も出ていない。
- 結論: 静的解析(`VideoSink` は `display_id` が自分と同じサービスだけ受ける)と実測が一致。**GT8D は、車がクラスター用の映像を申告しても、開かない・要求しない見込み**。ただし、この DHU はクラスター用の映像を申告していないため、「申告されたときの挙動」は未実測。
- DHU の ini キー(exe から復元): `[display:名前]` の節に `displaytype`(main/auxiliary/cluster)・`resolution`・`dpi`・`realdpi`・`framerate`・`decoderdepth`・`dhudecoderdepth`・`startupfocus`・`viewingdistance`・`gridspacing`・`uitheme`・`navigation`・`widgettype`・`videoconfig`・`inputmode`。2 枚目のウィンドウが何かは未確認(クラスターのナビ表示用か、追加ディスプレイか)。
- 注意: 15:15 の接続の記録は、端末の時計が 10-21 のまま(再起動直後で時刻同期前)なので、ログ上の日付は `10-21 21:26` になっている。

- **案内中の実測(15:20〜15:21)**: Google マップでルート案内を開始(マップの案内通知が出ている状態)。DHU の 2 枚目のウィンドウ(`instrumentcluster` のナビ表示)は**真っ黒のまま**。GT8D のデーモンは ch 6(NavigationStatus)に**1 通も送っていない**(`ch:6` のログ 0 件)。案内音声だけは `send navi audio` で ch 4 に流れた。つまり Android Auto モードでは、案内を始めても、車へ右左折やメーター表示は一切送られない。ベンダーのナビ連携(`MapNotificationListenerService` → `AT#CW`/`AT#CX`)は無効(設定 null)のままで、これは有線 CarPlay 用の経路。

## 実装(Task 239、2026-11-15)

- ~~小アプリ `:carback`~~(純正の「Car」と重複するため、同日に削除。経緯は下の「既存の Car アプリ」)と、`VendorBridge.switchToCarScreen()` / `carHomeAvailable` を実装。
- **通常アプリからの送信を実機で確認(15:33)**: debug APK を入れ、Activity を起動 → デーモンが `VIDEO_FOCUS_REQUEST`(NATIVE)→ `VIDEO_FOCUS_NOTIFICATION`(NATIVE)→ `MEDIA_MESSAGE_STOP`。root シェルでなくても届く。
- テスト(carback 削除前): carback 3 件、app の `VendorBridgeTest`・`AndroidActivityLauncherTest` 23 件、すべて成功。
- 未実施: ユーザーによるアイコン操作での見た目・操作感の確認、release の署名・版、YLuncher 内にボタンを付けるかの判断。

- **既存の「Car」アプリ(`com.ds.backcar/.MainActivity`、プリインストール)が同じ動作**(15:40 実測): 押すと MainService の `smart_box_carplay` バインダー経由で `AT#CB` → `VIDEO_FOCUS_REQUEST`(NATIVE)→ DHU が「Video focus is off.」。つまり `:carback` は純正の「Car」と機能が重複する(経路だけ違う: 純正はバインダー、こちらはブロードキャスト)。投影への復帰は DHU の `focus video on`(車側の操作)。

## クローズ記録(2026-11-15、ユーザー指示)

**方針(確定)**
- UI への独自ボタンは設置しない。普段の純正画面への復帰は、Dock に固定したプリインストールの「Car」(`com.ds.backcar`)を使う(`com.ds.backcar` の分割リダイレクト除外は対応済み)。
- Task 239 の `VendorBridge.switchToCarScreen()` / `carHomeAvailable` は revert せず、**内部 API として保持**する。将来、パネルを含まない 2 画面分割中(Google マップ + YouTube など)に、エッジスワイプ(カーテン)などから直接純正画面へ抜けたくなったときに使う。既存の `VendorBridgeTest`(3 件)でカバー済みで、追加実装は無い。呼び出し元(UI 配線)は未設置。
- 実車へ持ち込むときの基本方針は **有線の Android Auto モード(`cp_aa=2000`)**。

**`gt8d_cluster.ini` での DHU 実測の結論(2026-11-15 15:15、1 回)**
- ini は `[display:Cluster]` + `displaytype = cluster` が有効な書式(`[display]` + `name` では、追加ディスプレイの節として読まれなかった可能性が高い。DHU のコンソールにエラーは出なかった)。
- 接続は正常。`sdAutoReverse` はプライマリ(ch 2)だけで投影を始め、ハンドシェイクの異常やクラッシュは無かった(`VIDEO_FOCUS_PROJECTED` → `MEDIA_MESSAGE_START`、その後 `AT#CB` による NATIVE への切り替えと `focus video on` による復帰も正常)。
- ただし、DHU はクラスター用の映像サービスを**申告しなかった**(申告は `DISPLAY_TYPE_MAIN` の 1 つだけ)。そのため「申告されたクラスター用シンクを `sdAutoReverse` が無視する」ところは、**実測では確認できていない**。静的解析(`VideoSink` は `display_id` が自分と同じサービスだけを受ける)からの見込みにとどまる。
- 1 回目の起動(15:06 以前の別の接続)で、TLS 確立後に `Failed to read from transport - disconnect` で終了したことがあった。やり直しで接続できた。原因は未調査(DHU の初回起動時の取りこぼしと思われるが未確認)。
- 実車での確認事項(未実施): 接続直後の `SdAAReverse` のサービス一覧に `DISPLAY_TYPE_CLUSTER` が出るか、出た場合に GT8D が開くか。

## 解決策 A の掘り下げ: `sendNavFocusNotifyCmd` で NAV フォーカスを NATIVE に送る(2026-11-15、読み取り・静的解析のみ。実機の書き換えなし)

ユーザーが「UI 未設置でクローズ」を撤回し、ブラックアウトの根治を目指す方針に切り替えた。**この節は設計だけ**で、パッチ適用・`.so` の配置は、設計を見たユーザーの承認後。

### 1. `Controller::sendNavFocusNotifyCmd(int)` の解析(`libSdAutoReverse.so`、sha256 先頭 f2a665a8…)
- 場所: `0x201308`、サイズ 256。呼び出し元は**ゼロ**(未使用の関数)。
- シグネチャ: `void Controller::sendNavFocusNotifyCmd(Controller* this /*r0*/, int type /*r1*/)`。
- 中身(抜粋):
  ```
  00201332 ldr  r0,[sp,#0x2c] ; 00201334 cmp r6,#0 ; 0020133a orr r0,r0,#1 ; str r0,[sp,#0x2c]   ; has_bits に focus_type を立てる
  00201340 mov.w r0,#2 ; 00201348 it eq ; moveq r0,#1 ; 0020134c str r0,[sp,#0x34]       ; focus_type = (type==0) ? 1 : 2
  002013a2 ldr r0,[r4,#8] ; 002013a8 movs r1,#0xd ; 002013aa blx MessageRouter::marshallProto(ushort,Message const&,IoBuffer*)
  002013ae ldrh r1,[r4,#4] ; 002013b0 tst.w r1,#0xff ; 002013b4 beq → 送らずに片付け
  002013b6 ldr r0,[r4,#8] ; 002013b8 lsrs r1,r1,#8 ; ... ; 002013c6 ldr r6,[r5,#8] ; blx r6   ; ルーターの送信(仮想関数)
  ```
- 組み立てるメッセージ: `NavFocusRequestNotification`(`MESSAGE_NAV_FOCUS_REQUEST`、メッセージ ID **13**)。フィールドは `focus_type`(`NavFocusType`)1 つ。
- 引数の意味: **`type == 0` なら `NAV_FOCUS_NATIVE`(1)、0 以外なら `NAV_FOCUS_PROJECTED`(2)**。つまり NATIVE を送るには第 2 引数に **0** を渡す。
- 送信先チャネル: `Controller` の `[this+5]`(1 バイト。コンストラクタで 0 初期化、`handleVersionResponse` で設定。ログの `ch:%d` に出る。制御チャネル= 0)。**`[this+4]` の下位バイトが 0 のとき(バージョン交渉の前)は送らずに黙って戻る**。
- 前提条件: 制御チャネルの確立後(バージョン応答を処理済み)ならいつでも送れる。`ServiceDiscoveryResponse` でナビ関連のサービスが申告されたかどうかは**見ていない**(`sendAudioFocusRequest` と同じ作りで、同じ前提)。同じ前提で `sendAudioFocusRequest` が接続直後に実際に動いている(ログ `Controller::sendAudioFocusRequest finished`)。
- 関連: `Controller::setNavigationFocus(NavFocusType)`(`0x200b70`)は逆向きの `NavFocusNotification`(ID 14)を作る側で、`sendNavFocusNotifyCmd` の末尾と `sendVoiceSessionCmd` からだけ呼ばれる。受信側の `handleNavFocus…` は見つからず、**GT8D は車からの NAV フォーカス通知を処理していない**(接続中のログにも `NAV_FOCUS` は 0 件)。
- `Controller*` の取り方(ライブラリ内の確立した慣習): `auto_reverse_audio_start` などと同じく、グローバルハンドル(GOT 経由 `0x802d60`)を `g` として **`Controller* c = *(*(g+0xc)+0x9c) + 0xd30`**。他に `*(g+0xc)+0x3c` = `VideoSink`、`+0x74` = `MediaPlaybackStatusEndpoint`。
- 参考: `auto_reverse_back_home`(`0x1fa4d0`、28 バイト)は `g` が null でなければ `VideoSink::setFakeVideoFocus(sink, 2, 0)` を末尾呼び出しして戻る。

### 2. 外部トリガーとフックの比較
- **コマンド受信の経路**: `AT#CB` は `sdAutoReverse` の AT ディスパッチ(`0x549c` から、`sub_2368` が入力バッファ `0x1e061` と `AT#CA`・`AT#CB`・… を順に照合)→ `sub_7440`(`carplay_back_home:H264_start=%d iconVisible=%d` を記録、`H264_start == 1` のときだけ)→ `sub_145f8` → `auto_reverse_back_home`。AT コマンドで未使用なのは `AT#CH`・`CI`・`CJ`・`CT`〜`CX`・`CZ`(`CW`・`CX` は有線 CarPlay 用で AA のデーモンには無い)。新しい AT を足すには、`sdAutoReverse` の照合の連なりへの追加が要り、**ライブラリ内のフックより大がかり**。
- 起動の構造: `init.rc`(`/system/etc/init/hw/init.rc:1568`)の `service sd-reverse /system/bin/sd-reverse.sh`(`user root`、`seclabel u:r:init:s0`)が、スクリプトの最後で `$EXE_PATH/sdAutoReverse -c $CHANNEL` を起動する(`cp_aa == 2000` のとき)。`sdAutoReverse` は `libSdAutoReverse.so` を `NEEDED` で読み込む。SELinux は **Permissive**。`/system`・`/system_ext`・`/vendor` は **ro**(`ro.boot.verifiedbootstate=orange`、`ro.boot.vbmeta.device_state=unlocked`、`ro.boot.veritymode=enforcing`)。
- 実装の整合性検査: 両バイナリに自己検査の文字列は無い(MD5/SHA 等は BoringSSL のもの)。
- コードの空き: `.text` 内の 0 埋め・nop の連続は **65 バイト**(ファイルオフセット 6233472)と 20 バイトの 2 か所だけ。小さなスタブ(約 30〜40 バイト)なら入るが、余裕は少ない。
- 比較:
  | 方式 | 書き換え対象 | 利点 | 欠点・リスク |
  |---|---|---|---|
  | **A1: `LD_PRELOAD` のフック `.so`** | `sd-reverse.sh` の 1 行(`LD_PRELOAD=… sdAutoReverse`)。`.so` 自体は `/data` に置ける | ライブラリ本体は無傷。戻すのはスクリプト 1 行 | **`/system` の書き込みが要る**(init.rc も `sd-reverse.sh` も `/system`)。NDK でのビルドが要る。非公開のメンバ関数の `Controller*` は、`sendAudioFocusRequest` の PLT 呼び出しを横取りすれば `this` が取れる(接続直後に 1 回呼ばれる。`handleServiceDiscoveryResponse`)。 |
  | **A2: バイナリパッチ** | `libSdAutoReverse.so` の数バイト + 65 バイトの空きにスタブ | NDK 不要、追加ファイル無し | `/system` の書き込みが要る。8 MB のライブラリの差し替えで、**戻すには元のファイル(sha256 f2a665a8…)の控えが必須**。OTA で元に戻る。 |
  | **A0: 実行中のプロセスへ一時的に呼び出す(ptrace)** | 何も書き換えない(再起動で消える) | **ファイルを変えずに効果を確かめられる**。`sendNavFocusNotifyCmd(c, 0)` を 1 回呼ぶだけ | 小さなルートのヘルパーが要る(NDK ビルド)。これは**パッチではなく実験**なので、先にこれで効果を見るのが安全。 |
- 起動直後の自動送出(第二候補)のフック箇所: `Controller::handleServiceDiscoveryResponse`(`0x200378` から `sendAudioFocusRequest` を呼ぶ)の直後、または `VIDEO_FOCUS_PROJECTED` を受けた直後。A1 なら `sendAudioFocusRequest` の PLT 横取りが最も確実(`this` を持つ)。AT#CB への相乗り(`auto_reverse_back_home` の差し替え)は、「純正画面へ」の動作に NAV の NATIVE が追加されるだけなので、メーターの黒を直す目的とは別物になる。

### 3. DHU の事前実測(`gt8d_cluster.ini`、無改造のベースライン)
- クラスター用のウィンドウ(「Instrument cluster」)は**開き、黒いまま**。Google マップで案内を開始しても黒のまま(GT8D から ch 6 への送信が無いため)。
- DHU のコンソールに、`focus audio` / `focus nav` / `focus video`(`focus video on` は復帰で確認済み)に当たる文字列がある(`exe` の文字列に `focus au`・`focus na`・`focus vi` の断片、`focus video toggle`、`Ignoring nav focus request while locked.`)。**`focus nav` の使い方と動作は、コンソールでの実測がまだ**(`help` の出力が必要。ユーザーの手元の DHU コンソールでの確認待ち)。
- 接続中の GT8D のログに `NAV_FOCUS` は 0 件。GT8D は NAV フォーカスを一度も要求していない。

### 判断と注意点(重要)
- **この方策が効く保証はない**。ブラックアウトの原因が「車が NAV フォーカスを投影に渡している」ことだという証拠は、まだ無い。GT8D は NAV フォーカスを一度も要求しておらず、車の既定は NATIVE と考えられるため、**NATIVE を送っても何も変わらない可能性が高い**。メーター内の地図枠を投影側に渡している仮説(クラスター用の表示)は、NAV フォーカスとは別の仕組み。
- 先に実車でサービス一覧(`DISPLAY_TYPE_CLUSTER`・`NavigationStatusService` の有無)と、接続直後に車が NAV フォーカスの通知を送ってくるかを記録するのが、最も安い判定材料。
- 実機を書き換える前に、**A0(実行中のプロセスへの一時的な呼び出し)で効果を確かめる**ことを勧める。パッチは、効果が確認できてから。
- どの方式も、戻すための控え(`/system/lib/libSdAutoReverse.so`、`/system/bin/sd-reverse.sh`、`/system/bin/sdAutoReverse` の sha256 と実体)を先に `adb pull` しておく。控えは `pull/` に既にある(同期日 2026-11-15)。

## A0 ツールの実証(DHU、2026-11-16 前日の 2026-11-15 16:01)と実車テスト手順

### A0 ツール `tools/a0call/a0call.c`
- 中身: root から、実行中の `sdAutoReverse` の主スレッドを `ptrace` で止め、レジスタを差し替えて `Controller::sendNavFocusNotifyCmd(int)`(`0x201308`)を 1 回呼び、元のレジスタへ戻して detach する。**ファイルシステム(`/system` を含む)は一切書き換えない**。デーモンを再起動すれば痕跡は残らない(`TracerPid` は 0 に戻る)。
- 安全装置: 関数の先頭 8 バイトが想定(`2de9f04303af8fb0`)と違えば何もしない(別版のライブラリ)。`g`・`Controller*` が null(未接続)、`[Controller+4]` の準備フラグが 0 のときも中止。システムコール中に止めた場合は、戻すときに同じ呼び出しが再開するよう調整(`restart_syscall` 中だけは中止)。
- モード: `status`(読み取りだけ)/ `native`(`sendNavFocusNotifyCmd(0)` = `NAV_FOCUS_NATIVE` を 1 回)/ `toggle [待機ms]`(`PROJECTED`(1)→ 待機(既定 500ms)→ `NATIVE`(0))。
- ビルド(zig、追加インストール不要): `zig cc -target arm-linux-musleabihf -static -Os tools/a0call/a0call.c -o a0call`。実機へは `adb push a0call /data/local/tmp/a0call` + `chmod 755`(`/data` に置くだけ。2026-11-15 に配置済み)。

### DHU での実証結果(16:01〜16:02)
- `status`: `pid=1843 base=e751a000 g=ebbc9eb0 Controller=e6884c00 ready=1 channel=0`。
- `native`: ログに `number=13 name=MESSAGE_NAV_FOCUS_REQUEST` / `focus_type: NAV_FOCUS_NATIVE`(`SdAAReverse`、ch:0)。**ID 13 の送出を GT8D 側のログで確認**。`sdAutoReverse` は落ちず(pid 変化なし)、`sd_screen: count:5 frame/s` の映像送出も続いた。
- `toggle 800`: `PROJECTED` → 約 0.8 秒後 → `NATIVE` の順に 2 通を送出(16:02:04.19 / 16:02:04.997)。クラッシュなし、投影を維持。
- 実行後も `status` は同じ値(`ready=1`)で、デーモンの状態は変わらない。
- **未確認**: DHU 側が ID 13 を受け取ってどう反応したか(DHU のコンソールの出力、「Instrument cluster」ウィンドウの変化)。GT8D 側には車からの NAV_FOCUS 通知を処理する処理もログも無いため、GT8D のログでは見えない。ユーザーの手元の DHU コンソールでの確認待ち。

### A0 の再実験(DHU を再接続して、16:13 と 16:18)と、実車手順への注意
- `native`(16:13:25)と `toggle 800`(16:18:31.895 `PROJECTED` → 16:18:32.701 `NATIVE`)を送出。いずれも GT8D のログで `number=13` / `focus_type` を確認、デーモン(pid 1781)は落ちず、`TracerPid: 0`、投影も維持。
- **DHU 側の観測(ユーザー)**: DHU のコンソールには、nav focus に関する行は何も出なかった(接続時の行だけ)。「Instrument cluster」ウィンドウは**変化なし(黒いまま)**。DHU は NAV フォーカスの受信を既定の出力に出さず、クラスターのウィンドウは `NavigationStatus`(右左折の情報)を描くものなので、NAV フォーカスの送出では変わらないのが自然。**DHU では「送出できること」までが実証で、「車がどう反応するか」は実車でしか確かめられない**。
- GT8D のログには、DHU からの返信(ID 14)の記録も無い(GT8D に受信処理が無いため)。
- **実車手順への注意(重要)**: 端末で `com.estrongs.android.pop`(ES ファイルエクスプローラー)が、ネットワークが無いときに 1 秒間に数千行のエラーを出し続け(30 秒で約 7 万行)、**logcat のリングバッファが数十秒で入れ替わる**。接続直後のサービス一覧(`SdAAReverse`)が、ADB をつないだ時点で既に消えている恐れがある。実車では、①接続前に `adb shell am force-stop com.estrongs.android.pop`、または ②Box を車につなぐ前から `adb logcat -v threadtime -s SdAAReverse:V > car_aa.txt` を流し続ける(車内 Wi-Fi のテザリングが先に立ち上がっている場合)、のどちらかを推奨。サービス一覧が欠けたら、車との接続を一度やり直して(USB を抜き挿し)再取得する。テザリング中はネットワークがあるので、エラーの連発は止まる見込み(未確認)。
- **対応済み(ユーザー承認、2026-11-15 16:23)**: `pm uninstall --user 0 com.estrongs.android.pop`(ユーザー 0 から削除。実体は `/vendor/priv-app/ESFileExplorer/` にあり、`/vendor` は触っていない)。削除後、ログの連発は止まった。上の①②は不要になった(念のためサービス一覧は接続直後に取る)。戻すときは `adb shell cmd package install-existing --user 0 com.estrongs.android.pop`(うまくいかなければ工場出荷時の復元か OTA)。

### 実車テストの手順(接続後 5 分で白黒をつける)
前提: 実車の USB に Box をつなぎ、車内 Wi-Fi のテザリング経由でワイヤレス ADB(`adb connect <IP>:<ポート>`)。**`logcat -c` は実行しない**(接続直後のサービス一覧が消える)。端末の時計が再起動直後はずれていることがあるので、日付で絞らない。

1. **記録を丸ごと保存(最初に 1 回)**: `adb logcat -d -v threadtime -b all > car_log.txt`
2. **車が申告したサービス一覧の抽出**(`SdAAReverse` の最後の `ServiceDiscoveryResponse` から): 
   ```
   adb logcat -d -v threadtime -s SdAAReverse:V | awk '/MESSAGE_SERVICE_DISCOVERY_REQUEST/{buf=""} {buf=buf $0 "\n"} END{printf "%s",buf}' | grep -E "services \{|  id: |media_sink_service|navigation_status|display_type|display_id|codec_resolution|frame_rate|VIDEO_FOCUS|NAV_FOCUS"
   ```
   見るところ: ① `media_sink_service` が `DISPLAY_TYPE_CLUSTER` を持つか(= 車がクラスター用の映像を申告している)、② `navigation_status_service`(IMAGE / 画像サイズ)の有無、③ メイン映像の解像度とフレームレート、④ 接続中に車から `NAV_FOCUS` の通知が来ているか。ログは 1 行の長さの上限で途中が切れることがあるので、同じ内容が 2 回出る(2 回目が完全)。
3. **メーターが黒い場合に叩く A0 コマンド**(`a0call` は `/data/local/tmp/` に配置済み。なければ `adb push` + `chmod 755`):
   - `adb shell /data/local/tmp/a0call status`(読み取りだけ。`ready=1` を確認)
   - `adb shell /data/local/tmp/a0call native` → メーターが戻るか目視。
   - 戻らなければ `adb shell /data/local/tmp/a0call toggle 800`(`PROJECTED` → 0.8 秒 → `NATIVE`)。さらに待機を長くして `toggle 3000` も。
   - 各回の直後に `adb logcat -d -v threadtime -s SdAAReverse:V | tail -20` で、ID 13 の送出(`MESSAGE_NAV_FOCUS_REQUEST`)を確認。
   - 効いたら、どのコマンドで、何秒後に戻ったかを記録(A1/A2 のパッチを設計する材料)。効かなければ、NAV フォーカスは原因ではない(ログの 4 でクラスター用の申告を確認)。
4. **終了後に通常の車載モードへ戻す**: A0 は何も書き換えていないので、元に戻す作業は無い(`adb shell rm /data/local/tmp/a0call` は任意)。`adb disconnect` し、設定でワイヤレスデバッグ(と USB デバッグ)を **OFF**。**デバッグ OFF で遅延なしの状態に戻る**(adb が遅延の原因だったため)。念のためデーモンの状態確認は `adb shell pidof sdAutoReverse` と `TracerPid: 0`(`/proc/<pid>/status`)。デーモンを再起動したい場合は、Box の再起動だけで足りる。

## 残っている確認

1. **DHU をクラスター有効で起動し直す**(DHU を終了 → USB を抜き挿し → `desktop-head-unit.exe --usb -c config\gt8d_cluster.ini`)。DHU が申告するサービスと、デーモンがどのチャネルを開くかを `SdAAReverse` の記録で読む。
2. `AT#CB` は実施済み(上の追加確認)。DHU が車側の表示になることをユーザーが確認。
3. **実車での記録**: 接続直後の `SdAAReverse` のサービス一覧(車が何を申告するか。クラスター表示・ナビ状態・解像度)と、ブラックアウト時のメッセージ。車内のテザリング経由のワイヤレスデバッグで取る(ユーザーの方針。端末内に保存する仕組みは作らない)。

## 実装の見通し(判定)

| 方策書の案 | 判定 |
|---|---|
| Phase 1: プロパティでフォーカス奪取を抑止 | **不可(対象が存在しない)**。Android Auto モードではフォーカス要求を送っていない |
| Phase 2: 純正画面へワンタップ | **DHU で動作を確認**。`ACTION_EXECUTE_AT_CMD` + `AT#CB`。仕様は上の確定案 |
| Phase 2: フォーカス解放の能動送信 | **不可**。送る API が外に出ていない(関数は未使用のまま) |
| Phase 3: 右左折の連携 | Android Auto モードでは**不可**(デーモンに送信処理なし)。有線 CarPlay モードなら、純正の仕組みを有効にするだけで動く可能性がある(設定 + 通知リスナーの許可。車側の対応も必要)。YLuncher で作り直す必要は薄い |
| ブラックアウトの根治 | 原因の確定待ち(上の 1・3)。クラスター表示が原因なら、Box 側でそのサービスに応じない・または中身を送る改修が要り、非 root のアプリからは届かない見込み |

## carprobe(DHU 画面用の検証アプリ)— loopback ADB 方式(Task 241、2026-11-15)

実車で、GT8D の画面だけ(PC・外部 ADB なし)でログ保存と `a0call` 実行を行う独立 APK `tools/carprobe`(`com.gt8d.carprobe`)。YLuncher 本体には触れない。

### 方式の変更(なぜ loopback ADB か)
- 当初(Task 240)は `/system/xbin/sdsu_no 0 sh -c …` で root シェルを取る方式。実機で、通常アプリ UID からの `sdsu_no`/`su` は **SELinux の su ドメインで拒否**され使えないと確定。
- 代わりに、この開発機の開発者 ADB(`ro.adb.secure=0`・`getenforce=Permissive`・adbd は root)に対し、**端末内 loopback `127.0.0.1:5555` へ TCP 接続し、標準 ADB プロトコルで認証なしにシェルを開く最小クライアント `AdbClient`** に差し替えた。`CNXN`(認証なしなので AUTH を要求されず CNXN が返る)→ `OPEN "shell:<cmd>\0"` → `WRTE`/`OKAY` で出力受領 → `CLSE`。
- ADB コマンド定数はリトルエンディアンの u32(例 `OKAY` はバイト列 `4f 4b 41 59` なので `0x59414b4f`)。`shell:` v1 は終了コードを返さないので、コマンド末尾に `echo __CARPROBE_EXIT__$?` を付けて最後のマーカー行から回収する。大きな出力(`logcat -b all`)はシェル側で `>> /sdcard/Download/…` にリダイレクトし、ソケットにはマーカーしか流さない。
- `INTERNET` 権限が必要(loopback でも TCP ソケットには要る)。それ以外の権限は付けない。`RootShell.java` は削除。

### 実機で分かった必須条件(本番条件)
- **`adb_wifi_enabled=1`(ワイヤレスデバッグ=TLS)の間、adbd は TLS ポートだけを開き `persist.adb.tcp.port 5555` を無視する**。アプリが使う 5555 は開かない。
- 5555 を開くには **クラシック ADB モード**にする(1 回のシェルで): `settings put global adb_wifi_enabled 0; setprop service.adb.tcp.port 5555`(`persist.adb.tcp.port` も `5555`)`; setprop ctl.restart adbd`。実車(povo 5G 単体+ホットスポット親機、ワイヤレスデバッグ不可)でもこれが本番条件。
- この端末の classic adbd は **5555 を loopback 限定(`127.0.0.1:5555`)でバインド**する。mDNS は `_adb._tcp` を 5555 で広告するが、**ホスト(PC)からは 5555 へ繋がらない**(ネットワークに晒されない=安全)。端末内 `127.0.0.1:5555` は開なのでアプリは動く。クラシック 5555 に切り替えると、ホストのリモート adb(ネットワーク/USB)は全て不通になり、以後は DHU 画面での操作になる。ホスト adb を戻すには端末側でワイヤレスデバッグを再有効化する。

### 検証の記録(2026-11-15)
- 単体テスト `testDebugUnitTest` 12件成功(`AdbMessageTest` 8:エンコードのバイト列・crc・往復・bad magic 等 + `ServiceSummaryTest` 4)。`assembleDebug` 成功。APK を実機へ `install -r`、`INTERNET` 付与確認。コミット `6e38286`(未 push)。
- **負の経路(確認済み)**: 5555 未開放の状態でアプリを起動すると、ステータス `adb: NO (… port 5555 … ECONNREFUSED)`、結果枠 `id: FAILED (exit=-1, via 127.0.0.1:5555)`、全ボタン無効。UI(横向き・大ボタン・ステータス行・結果枠)は正常描画。画面 `/tmp/.../carprobe-241-loopback-closed.png`。
- **正の経路(DHU 画面での確認待ち)**: ユーザー承認でクラシック 5555(loopback 限定)へ切り替え済み。ホストから届かないため、DHU を直接タップして確認する: ステータスが `adb: OK (uid=0 @127.0.0.1:5555)` になること、`Save Boot Log & Services`・`Run a0call status`・`native`・`toggle 800/3000ms` が通ること。`Finish & Disable ADB` は実車テスト完了時にユーザーが押す(今は 5555 を開いたまま)。
- 待ち受け状態: `adb_wifi_enabled=0`、`persist.adb.tcp.port=5555`、classic adbd が `127.0.0.1:5555` で稼働(mDNS `_adb._tcp` 5555 で確認)、carprobe 0.1.0 導入済み、`a0call` は `/data/local/tmp/a0call`。

### carprobe — 診断結果(2026-11-15 夜):loopback ADB はこの HW では不成立

DHU で carprobe を開くと `127.0.0.1:5555` 自体が `ECONNREFUSED`。原因を実機で決定的に切り分けた。

**(1) この端末の adbd は素の TCP `:5555` を LISTEN しない(構造的)。**
- `com.carsyso.main` の `ACTION_EXECUTE_SH`(system、adbd とは別 cgroup)から、`settings put global adb_wifi_enabled 0; setprop persist/service.adb.tcp.port 5555; setprop ctl.restart adbd; sleep; ss -ltnp` を実行(adbd 再起動に耐える方法)。結果: **`adbd=running port=5555` なのに `:5555` の LISTEN は 0 件**(0.0.0.0 にも loopback にも無し)。多数のベンダーポート(50xxx 等)は開くが 5555 は開かない。
- `adb_wifi=1`(ワイヤレスデバッグ)では TLS の一時ポートだけを LISTEN(`ss` で確認)。車載 USB モード(`sys.usb.config=none`/`autoMtp`)では adbd は USB FFS の `FUNCTIONFS_BIND` 待ちを延々リトライ。
- つまり「`stop` でシェルが切れて再起動しなかっただけ」ではない。adbd は走っていても legacy の TCP ポート(`service.adb.tcp.port`)を開かないビルド/構成。carprobe の loopback ADB は繋ぐ先が存在しない。

**(2) `ACTION_EXECUTE_SH` は root ではない。**
- ブロードキャストで `id` を実行 → **`uid=1000(system)`、`u:r:system_app:s0`、SELinux Permissive**。
- a0call は root の sdAutoReverse を ptrace するため、system(1000)では ptrace 不可(より上位の権限のプロセスは追跡できない)。
- 昇格も不可: `/system/xbin` は `drwxr-x--- root shell`(0750)。system は group shell(2000)に属さずディレクトリに入れないため、setuid root の `/system/xbin/sdsu_no` に到達すらできない(`inaccessible`)。
- usb-toggle が system で成立するのは、やることが setprop/settings/am/reboot など system 権限で足りる操作だから。a0call(root 必須)はこの枠に入らない。

**結論**: この端末で「アプリから root」を得る手段は、host 駆動の adb(USB か ワイヤレス TLS、どちらも PC が要る)だけ。**車内(host 無し)では a0call を起動できない。** loopback 版 carprobe(`6e38286`)は実装は正しいが、この HW では無効。

**有用な副産物**: `ACTION_EXECUTE_SH`(system)は adbd と別 cgroup なので、そこから `adb_wifi` の 0/1 切替+`ctl.restart adbd`+`ss` 採取を安全に実行でき、`adb_wifi=1` への自己復帰も効く(mDNS の tls-connect 再広告に数十秒)。adbd を触る実験はこの経路が安全。

**car 対応の選択肢(a0call は root 必須・車内に host 無し)**:
- A: 起動時に走る root サービス/デーモン(init か `/system`/`/vendor` の .rc = 既出の A1/A2 相当、要ユーザー承認・verified boot の remount)で a0call を自律実行、またはトリガーファイルを監視。carprobe はトリガー UI に縮小。
- B: 車内に小さな host(予備 Android+adb、SBC 等)を持ち込み a0call を駆動。
- C: ベンチ(DHU)確認のみに留める(ただし DHU はブラックアウトを再現しない)。
- 追加調査の余地: root(uid=0)で動くベンダーのサービスで、外部からコマンド実行を受け付けるものが他に無いか(あれば A の代替になりうる)。
