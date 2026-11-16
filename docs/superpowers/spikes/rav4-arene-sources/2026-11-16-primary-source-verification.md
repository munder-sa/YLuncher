# 一次ソース検証レポート：Android Auto Focus数値・CarPlay `type 110/111`・Reddit待機時タイル証言

作成日: 2026-11-16
目的: `carprobe` 送信値・CarPlayストリーム種別・2026年型RAV4クラスタ待機時挙動の一次情報（`.proto` / C実装 / Reddit原文）裏付け

## 検証サマリ

| 検証項目 | 判定 | 一次ソース確認結果の要点 | 
| ----- | ----- | ----- | 
| 1-A. `NAV_FOCUS_NATIVE=1` / `PROJECTED=2` | **実在（完全一致）** | [NavigationFocusRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/navigation/NavigationFocusRequestMessage.proto) にて `NAV_FOCUS_NATIVE = 1` / `NAV_FOCUS_PROJECTED = 2`（Control Channel msg `13`）を確認。Phone側からのフォーカス解放（release）用途もコメントに明記 | 
| 1-B. `Channel 11` と `DISPLAY_TYPE_CLUSTER` | **両方実在（別チャネル）** | `Channel 11`（`NAVIGATION_STATUS`）はTBTメタデータ用チャネル（[docs/channels/nav.md](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md)）。`DISPLAY_TYPE_CLUSTER`（`CLUSTER = 1`）は映像用 `AVChannel` の enum として [DisplayTypeEnum.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/video/DisplayTypeEnum.proto) に実在 | 
| 2\. `MHI2Q` / `OMKE` の `type 110` / `111` | **実在（C定数まで確認）** | [OMKE src/altscreen.c](https://github.com/OMKE/wireless-carplay-virtual-cockpit/blob/main/src/altscreen.c) で `#define ALT_SCREEN_TYPE 111`（`0x6f`）、[HOW_IT_WORKS.md](https://github.com/OMKE/wireless-carplay-virtual-cockpit/blob/main/docs/HOW_IT_WORKS.md) と [MHI2Q p1404_airplay.h](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/Toolbox/carplay_alt_screen/src/p1404_airplay.h) で `110`（Main）と `111`（AltScreen）を確認 | 
| 3\. Redditでの待機時（未案内時）タイル表示 | **CarPlayは真 / AAは未言及** | [Reddit r/rav4club](https://www.reddit.com/r/rav4club/comments/1sb1872/maps_in_the_digital_gauge_cluster_on_the_26/) にて目的地未入力時（`when you do not have a direction entered`）のクラスタ地図表示証言を確認。ただしこれはCarPlay（Apple Maps）についての記述であり、Android Autoの未案内時挙動まではスレッド内で言及なし | 

## 1. `mrmees/open-android-auto` における数値・チャネル定義の一次確認

### 1-1. `NavigationFocusType`（`1` / `2`）および Control Channel `msg 13 / 14`

* **対象ファイル1:** [oaa/navigation/NavigationFocusRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/navigation/NavigationFocusRequestMessage.proto)

* **対象ファイル2:** [oaa/navigation/NavigationFocusResponseMessage.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/navigation/NavigationFocusResponseMessage.proto)

* **対象ドキュメント:** [docs/channels/nav.md](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md)

`NavigationFocusRequestMessage.proto` の原文定義は以下の通りであり、APK 16.2（難読化クラス `vyn` および制御チャネル実装 `hzh.java`）から復元された値として `NAV_FOCUS_NATIVE = 1`、`NAV_FOCUS_PROJECTED = 2` が定義されている。またコメントに `sent by phone to request/release navigation focus` と明記されており、Phone → HU 方向の `msg 13` で `1`（`NAV_FOCUS_NATIVE`）を送ることがフォーカス解放（release）に相当する。

```
// NavigationFocusRequest — sent by phone to request/release navigation focus.
//
// IMPORTANT: This is a CONTROL CHANNEL message (GAL service type 1, hzh.java),
// NOT a navigation channel message. Message ID = 13 (not 0x8xxx).
//
// Enum class: vyn (16.2) - NavigationFocusType (NAV_FOCUS_NATIVE=1, NAV_FOCUS_PROJECTED=2)
enum NavigationFocusType {
    NAV_FOCUS_NATIVE = 1;
    NAV_FOCUS_PROJECTED = 2;
}

// Message ID: 13 on Control channel (GAL type 1)
// Direction: Phone->HU
message NavigationFocusRequest {
    optional NavigationFocusType type = 1; // confidence: silver [apk_static, cross_version]
}

```

```
// NavigationFocusResponse — HU confirms/denies navigation focus.
//
// IMPORTANT: This is a CONTROL CHANNEL message (GAL service type 1, hzh.java),
// NOT a navigation channel message. Message ID = 14.
// Direction: HU->Phone
message NavigationFocusResponse {
    optional NavigationFocusType type = 1; // confidence: silver [apk_static, cross_version]
}

```

### 1-2. `Channel 11`（`NAVIGATION_STATUS`）と `DISPLAY_TYPE_CLUSTER` の構造的区別

両方ともリポジトリ内に実在するが、プロトコル上は**異なるチャネル**に属する。

1. **`Channel 11`（`NAVIGATION_STATUS`）:** [docs/channels/nav.md](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/nav.md) で解説されているターンバイターン案内メタデータ用チャネル。`InstrumentClusterStart`（`0x8001`, HU → Phone）、`NavigationNotification`、`NavigationTurnEvent`（`0x8004`）、`NavigationNextTurnDistanceEvent`（`0x8007`）、`NavigationState` が流れる。

```
Channel 11 (NAVIGATION_STATUS) carries turn-by-turn navigation guidance from the phone to the head unit. When a user starts navigation in Google Maps, Waze, or another navigation app on the phone, this channel delivers real-time turn events, distance updates, and navigation state changes so the HU can display guidance without projecting the full map.
...
Navigation focus negotiation (NavigationFocusRequest/Response) happens on the control channel (msg IDs 13/14), not here.

```

* 補足: [docs/channels/display-routing.md](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md) 内の1箇所では例示として `NavigationNotification (ch10)` / `PhoneStatusUpdate (ch11)` と番号が1つずれた記述がある。実際のワイヤ上の `ChannelDescriptor.channel_id` は `ServiceDiscoveryResponse` で動的に採番されるトランスポートIDである点に留意する。

2. **`DISPLAY_TYPE_CLUSTER`（値 `1`）:** `Channel 11` 上のフィールドではなく、`ServiceDiscoveryResponse` 内で広告される映像ストリーム用 `AVChannel`（APKクラス `vye` の field 7 `display_type`）に設定される enum 値として、[oaa/video/DisplayTypeEnum.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/video/DisplayTypeEnum.proto) および [docs/channels/display-routing.md](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md) に定義されている。

```
// APK class: vxg (16.1) / vws (16.2) - 3 values confirmed via ied.java (vws.DISPLAY_TYPE_MAIN)
message DisplayType {
    enum Enum {
        MAIN = 0; // DISPLAY_TYPE_MAIN
        CLUSTER = 1; // DISPLAY_TYPE_CLUSTER
        AUXILIARY = 2; // DISPLAY_TYPE_AUXILIARY
    }
}

```

[docs/channels/display-routing.md](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/display-routing.md) における `iom.java` 由来の制約原文：

```
- Exactly ONE display must be DISPLAY_TYPE_MAIN
- At most ONE DISPLAY_TYPE_CLUSTER allowed
- Additional displays become DISPLAY_TYPE_AUXILIARY
- Primary display (ID 0) must be MAIN

```

## 2. `MHI2Q` / `OMKE` 両リポジトリにおける CarPlay `type 110` / `type 111` の一次確認

Apple公式SDKの公開クラス名ではなく、AirPlay / CarPlay RTSP `SETUP` リクエスト内の `streams` 辞書で指定されるストリーム種別整数値（`110` = メイン画面、`111` = `0x6f` = クラスタ用セカンダリ画面 `altScreen`）として、両リポジトリのドキュメントおよびCソースコードに実在する。

### 2-1. `OMKE/wireless-carplay-virtual-cockpit`（Suding製ドングル `sdDongle` 解析・フック実装）

* **技術解説ドキュメント:** [docs/HOW_IT_WORKS.md](https://github.com/OMKE/wireless-carplay-virtual-cockpit/blob/main/docs/HOW_IT_WORKS.md)

```
With a cable, the MIBSI mod on the MHI2Q adds a second display (altScreen, 1440×540, uuid b7e6c5a0-2222-4000-8000-000000000002) to the car's AirPlay /info. iOS sees it and sends a second H.264 stream (type 111) with the cluster map.

The dongle sits in the middle. It is an AirPlay receiver toward the iPhone and an AirPlay sender toward the car. The stock sdDongle dropped every display except the main one from the /info it gives the iPhone. It only knew how to forward stream type 110 (main screen). So the iPhone never rendered the cluster map.

```

* **Cソースコード定数・使用箇所:** [src/altscreen.c](https://github.com/OMKE/wireless-carplay-virtual-cockpit/blob/main/src/altscreen.c)（22行目、57行目、448行目、524行目）

```
#define ALT_SCREEN_TYPE 111
/* ... */
#define CLUSTER_STREAM_TYPE 0x6f
/* ... */
cf_dict_set_int64(entry, KEY_TYPE, ALT_SCREEN_TYPE);
/* ... */
cf_dict_set_int64(sd, K_TYPE, CLUSTER_STREAM_TYPE);

```

### 2-2. `yuedizhibo/MHI2Q-CarPlay-AltScreen`（車載機側 CarPlay AltScreen フック実装）

* **ドキュメント:** [README_EN.md](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/README_EN.md)

```
iPhone CarPlay -> private type111 secondary-display video stream -> Stock AirPlay / OMX decoding -> QNX Screen readback + linearization -> standard NV12 (/carplay111_decoded) -> Separate display process (carplay-alt111-mirror-display)
- The main CarPlay display (Main110) stays on the stock path and is not part of this display path.

```

* **Cヘッダ・実装コメント:** [Toolbox/carplay_alt_screen/src/p1404_airplay.h](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/Toolbox/carplay_alt_screen/src/p1404_airplay.h) および [Toolbox/carplay_alt_screen/src/altscreen_state.c](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/blob/main/Toolbox/carplay_alt_screen/src/altscreen_state.c)

```
* ScreenStreamCreate is multi-instance, while the stock receiver owns one Main
* ScreenSession slot. The second 111 ScreenSession belongs in the altscreen_state
* side table and must never replace stock 110.

```

## 3. Reddit `r/rav4club` スレッド全件確認と「待機時タイル表示」の真偽

* **対象URL:** [Maps in the digital gauge cluster on the 26 : r/rav4club](https://www.reddit.com/r/rav4club/comments/1sb1872/maps_in_the_digital_gauge_cluster_on_the_26/)

### 3-1. 該当コメントおよび主要返信の英語原文

* **投稿本文（OP: `mrfunkytown`）:**

```
I've seen that the new gauge cluster shows the map, which is awesome. However, does this only work with Toyotas navigation? I exclusively use Apple Maps with car play. Will Apple Maps still show up in the gauge cluster? Same with the HUD?

```

* **待機時（目的地未入力時）の挙動に言及したコメント（`InsideNecessary6094`）:**

```
Kinda, the Waze map does show up on the digital gauge cluster but the turn by turn directions do not show up in the gauge cluster when the compass widget is selected. Only apple maps has the map and turn by turn on the cluster. Funny enough google maps does have the turn by turn but not the map. and waze the map but no turn by turn. Sygic does have the map and turn by turn but the turn by turn is kind of bad since it does not tell you the turn by turn for the upcoming turn. Instead it tells you what street you are already on. Apple maps does it best but I hate that it does not do the 3d auto zoom that google maps does when you do not have a direction entered. it does the top-down view which in my opinion is inferior.

Im in a 2026 Woodland

```

* **CarPlay版 Google Maps の後日アップデート報告（`Waltercpd` / `productive_monkey`）:**

```
Waltercpd: Google Maps now works. Noticed it today after an app update
productive_monkey: I can confirm Google Maps displays on the gauge cluster, at least when doing CarPlay with an iPhone

```

* **Android Auto および HUD に関する他ユーザーのコメント原文（`sonos82` / `gloriousflight` / `sphrz` / `AP_dubs`）:**

```
sonos82: I works with both Toyota and Android auto. Not sure what happens when the subscription ends. guessing its blank or says give us money. I assume its the same with Apple Car play. Unless im missing a setting I don't see any navigation stuff on the HUD. I thought it did thought.

gloriousflight: Google maps and waze both work on Android auto, weird that only waze only works on carplay.

sphrz: It works with Android Auto! My girlfriend found out is the Toyota subscription one is the only one to my knowledge that integrates into the HUD. For example, if you have a turn coming up, the HUD will show the road and arrow youre turning on as well as the distance to the turn if you didnt want to look away from the road.

AP_dubs: Can confirm that it does not require a subscription. My 2026 Woodland does not have an active Toyota Navigation subscription and Apple maps integrates with the gauge cluster and HUD with no issues.

```

### 3-2. 厳密な評価と前回推論の補正点

1. **CarPlay（Apple Maps）での待機時タイル表示は事実:** `InsideNecessary6094`（2026年型 RAV4 Woodland）の `"when you do not have a direction entered. it does the top-down view"` という記述により、CarPlay接続時はルート案内を行っていない待機状態でもメーター内クラスタへApple Mapsのトップダウン地図が表示されることが裏付けられる。

2. **Android Autoでの待機時（未案内時）挙動はこのスレッドからは未確定（重要な補正）:** Android Autoに言及した3名はいずれもクラスタ地図表示が動作すること自体は肯定しているが、**「目的地未入力の待機状態（`NAV_FOCUS_PROJECTED` 未取得時）から既にクラスタ地図が出ているのか、案内開始後に出るのか」までは言及していない**。

3. **`carprobe` 実車検証への示唆:** Android for Carsの仕様（[品質ガイドライン NF-9](https://developer.android.com/training/cars/apps/navigation)）上は未案内時の地図タイル描画も可能だが、新型RAV4（Arene）側のHMIがAndroid Auto接続時に未案内状態からクラスタを切り替えるかどうかは実車未確認である。したがって、有線Android Auto接続では「そもそも未案内時は暗転しない」可能性と、暗転しても [NavigationFocusRequestMessage.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/navigation/NavigationFocusRequestMessage.proto) の仕様通り `NavigationFocusRequest(NAV_FOCUS_NATIVE = 1)` によるreleaseで純正地図へ復帰できる可能性の両方が有効な検証対象として残る。