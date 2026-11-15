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

## 残っている確認

1. **DHU のクラスターを有効にして再接続**(`config/gt8d_cluster.ini` を用意済み。`instrumentcluster = true`)。DHU が申告するサービスと、デーモンがどのチャネルを開くかを `SdAAReverse` の記録で読む。
2. **`AT#CB` の試し撃ち**(DHU 接続中に `am broadcast -a com.suding.main.ACTION_EXECUTE_AT_CMD -p com.carsyso.main --es extra_at_cmd AT#CB`)。DHU が純正側の画面に切り替わるかを見る。状態を変える操作なので、ユーザーの了承後に行う。
3. **実車での記録**: 接続直後の `SdAAReverse` のサービス一覧(車が何を申告するか。クラスター表示・ナビ状態・解像度)と、ブラックアウト時のメッセージ。実車では adb が使えない前提なので、端末の中で logcat をファイルに残す方法を用意する。

## 実装の見通し(判定)

| 方策書の案 | 判定 |
|---|---|
| Phase 1: プロパティでフォーカス奪取を抑止 | **不可(対象が存在しない)**。Android Auto モードではフォーカス要求を送っていない |
| Phase 2: 純正画面へワンタップ | **有望**。`ACTION_EXECUTE_AT_CMD` + `AT#CB`。`VendorBridge.switchToCarScreen()` の実装先にできる。要試し撃ち |
| Phase 2: フォーカス解放の能動送信 | **不可**。送る API が外に出ていない(関数は未使用のまま) |
| Phase 3: 右左折の連携 | Android Auto モードでは**不可**(デーモンに送信処理なし)。有線 CarPlay モードなら、純正の仕組みを有効にするだけで動く可能性がある(設定 + 通知リスナーの許可。車側の対応も必要)。YLuncher で作り直す必要は薄い |
| ブラックアウトの根治 | 原因の確定待ち(上の 1・3)。クラスター表示が原因なら、Box 側でそのサービスに応じない・または中身を送る改修が要り、非 root のアプリからは届かない見込み |
