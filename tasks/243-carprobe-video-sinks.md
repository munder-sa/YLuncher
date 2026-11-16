最初に `tasks/RULES.md` を読み、従う。

# Task 243: carprobe の要約に「映像サービスの数」と「ナビ状態エンドポイントの記録」を足す

`tools/carprobe`(独立 APK `com.gt8d.carprobe`)の `ServiceSummary` は、`SdAAReverse` のログから車の ServiceDiscoveryResponse(サービス一覧)を要約し、`Cluster sink: YES/NO` などを表示する。実車テスト(新型 RAV4)で誤判定しないよう、次の 2 つを要約に足す。

1. **映像サービスの数と中身**: 今の `Cluster sink` は `display_type: DISPLAY_TYPE_CLUSTER` の文字列の有無だけを見ている。車が `display_type` を付けずに `display_id` だけ違う第 2 の映像サービスを申告すると `NO` になってしまう。映像サービス(`media_sink_service` のうち映像のもの)を数え、それぞれの `display_id`・`display_type`・解像度を表示する。
2. **ナビ状態エンドポイントの記録**: 車がメーター表示を始めると、ナビ状態サービスのチャネルにメッセージが来る見込み。デーモンの `NavigationStatusEndpoint` がそれを処理したログを数える。**`type:32769`(0x8001)の文字列で探してはいけない**: 入力チャネル(`channelId:3`)の `InputSource::routeMessage ... type:32769` が大量に出るため誤判定になる。

**YLuncher 本体(`app/`・`panelhost/`)と `GT8D-IPv6-Guard/` には一切触れない。** `tools/carprobe` の中だけで完結させる。

## 作る/変えるファイル(これ以外は触らない)
- **write** `tools/carprobe/src/main/java/com/gt8d/carprobe/ServiceSummary.java`
- **write** `tools/carprobe/src/test/java/com/gt8d/carprobe/ServiceSummaryTest.java`(既存 4 件はそのまま残し、期待値も変えない。新しいテストを足す)
- **create** `tools/carprobe/src/test/resources/sdr_dhu_20261115.log`(下の「実ログ」を一字一句そのまま写す)

`MainActivity.java` は変えない(`summary.toText()` をそのまま表示・保存しているので、`toText()` に行を足せば画面とログの両方に出る)。

## 実ログ(材料)

`tasks/243-sdr-dhu-20261115.log` は、GT8D が DHU(PC の Android Auto 疑似ヘッドユニット)に繋いだときの実際の `logcat -v threadtime` の抜粋(295 行)。これを `tools/carprobe/src/test/resources/sdr_dhu_20261115.log` に**一字一句そのまま**写し、テストで読み込む(`getClass().getResourceAsStream("/sdr_dhu_20261115.log")` を UTF-8 で読む)。

このログで分かっている形(すべて行頭は `MM-DD HH:MM:SS.mmm  PID  TID D SdAAReverse: ` の後に本文):

- 本文は 1 フィールド 1 行。字下げは半角空白 2 個単位。
- **サービス一覧は 2 回出る。1 回目は途中で切れる**(本文が `  input_source_serv` で途切れ、`virtual int Controller::routeMessage(...)` の行が割り込む)。2 回目が完全で、`id: 1`〜`id: 9` が全部ある。
- 1 つのサービスは本文 `services {`(字下げなし)で始まり、本文 `}`(字下げなし)で閉じる。直後の行に `  id: N`。
- 映像サービスの例(id 2):
  ```
  services {
    id: 2
    media_sink_service {
      video_configs {
        codec_resolution: VIDEO_1920x1080
        ...(入れ子の ui_config { margins { ... } } など)
      }
      display_id: 0
      display_type: DISPLAY_TYPE_MAIN
    }
  }
  ```
- 音声も `media_sink_service`(id 4・5)。映像の項目は無く、`audio_type: AUDIO_STREAM_GUIDANCE` などを持つ。
- 入力サービス(id 3、`input_source_service`)も `    display_id: 0` を持つ。**これを映像の display_id と取り違えないこと**(`media_sink_service` の中の `display_id` だけを見る)。
- サービス一覧の後に、デーモン自身のログが続く。例: `NavigationStatusEndpoint::virtual bool NavigationStatusEndpoint::notifyDiscoveryInfo(ServiceDiscoveryResponse *):177 service is exist, and stop`(ナビ状態サービスが申告されたとき)。申告が無いときは同じ関数から `service is not found, and stop` が出る(spike の記録)。
- 入力チャネルのログ `virtual int InputSource::routeMessage(uint8_t, uint16_t, const shared_ptr<IoBuffer> &):237 ret:0 channelId:3 type:32769` が大量に出る(この抜粋には入っていないが、実ログでは数百行)。

## 仕様

### 対象範囲(今と同じ)
- `SdAAReverse: ` を含む行だけを見る。最後の `MESSAGE_SERVICE_DISCOVERY_REQUEST` より後だけを対象にし、新しい REQUEST が来たら新しい項目もすべて捨ててやり直す(今の `cluster`・`displays` などのリセットと同じ扱い)。

### 1. 映像サービス
- サービスの区切り: 本文がちょうど `services {` の行で開始。本文がちょうど `}` の行、または字下げの無い他の本文(`services {` を除く)が来たら、そのサービスを閉じる。途中で切れた 1 回目のサービスも、次の `services {` で閉じる。
- サービス ID: サービス内の最初の本文 `  id: N`(字下げ 2)。
- `media_sink_service` の中: 本文 `  media_sink_service {`(字下げ 2)から、本文 `  }`(字下げ 2)まで。この中に `codec_resolution:`・`video_configs {`・`display_type:` のどれかがあれば**映像**。`audio_type:` だけなら音声(数えない)。
- 映像サービスごとに記録する値: サービス ID、`display_id`(`media_sink_service` 直下の字下げ 4 の `    display_id: N`。無ければ「不明」)、`display_type`(同じく字下げ 4 の `    display_type: X`。無ければ「不明」)、解像度(`codec_resolution:` の値。出た順・重複なし)。
- **重複の除き方**: 同じサービス ID が 2 回出たら、後に出たもの(完全な方)で置き換える。サービス ID が無い映像サービスは、(display_id, display_type, 解像度の並び)が同じものを 1 つとみなす。並び順は、最初に出た順。
- 公開する値: `public final List<VideoSink> videoSinks`(変更不可のリスト)。`VideoSink` は `ServiceSummary` の中の `public static final class`。フィールドは `public final Integer serviceId`(無ければ null)、`public final Integer displayId`(無ければ null)、`public final String displayType`(無ければ null、値は `DISPLAY_TYPE_MAIN` などそのまま)、`public final List<String> resolutions`。
- `public final boolean hasSecondaryVideo`: 映像サービスが 2 つ以上、または `displayId` が 0 以外(null は除く)のものがある、または `displayType` が `DISPLAY_TYPE_MAIN` 以外(null は除く)のものがあるとき true。

### 2. ナビ状態エンドポイント
- `public final String navStatusEndpoint`: 本文に `NavigationStatusEndpoint` と `notifyDiscoveryInfo` を含む行のうち、`service is exist` を含めば `"exist"`、`service is not found` を含めば `"not found"`。どちらも無ければ `"?"`。複数あれば後のものを採る。
- `public final int navStatusEvents`: 本文に `NavigationStatusEndpoint` を含み、かつ `routeMessage` または `handleNavigation` を含む行の数(`notifyDiscoveryInfo`・`addDiscoveryInfo` の行は数えない)。
- `public final List<String> navStatusSamples`: 上で数えた行の本文を、出た順に最大 3 行(変更不可のリスト)。
- `InputSource` の行は、`type:32769` を含んでいても**どちらにも数えない**(`NavigationStatusEndpoint` を含まないので自然に外れるはず。テストで確かめる)。

### toText()
今の 5 行(と見つからないときの 1 行目)は**文言も順番もそのまま**残し、最後の `NAV_FOCUS: ...` の後に次を足す:

```
Video sinks: <数>
  - id=<serviceId または ?> display_id=<displayId または ?> type=<displayType または ?> <resolutions>
  ...(映像サービスごとに 1 行)
Secondary video: YES/NO
Nav status endpoint: <exist / not found / ?>
Nav status events: <数>
  > <サンプル行>(最大 3 行。0 件なら行なし)
```

`<resolutions>` は `List.toString()` の形(例 `[VIDEO_1920x1080]`)。

### 変えないこと
- 既存の公開フィールド(`hasCluster`・`hasNavStatus`・`displayTypes`・`resolutions`・`navFocusSeen`・`found`)の意味と値。既存のテスト 4 件は無修正で通ること。
- `parse(String)` の呼び方。Java 8 で書ける範囲の API だけを使う(今のファイルと同じ)。

## テスト(ServiceSummaryTest に足す。名前はこのとおり)

1. `dhuRealLogCountsOneVideoSink`: リソースの実ログを `parse` して、`found` が true、`videoSinks.size() == 1`、その 1 件が `serviceId == 2`・`displayId == 0`・`displayType == "DISPLAY_TYPE_MAIN"`・`resolutions == [VIDEO_1920x1080]`、`hasSecondaryVideo` が false、`hasCluster` が false、`hasNavStatus` が true、`navStatusEndpoint == "exist"`、`navStatusEvents == 0`。`toText()` が `Video sinks: 1` と `Secondary video: NO` と `Nav status endpoint: exist` を含む。(1 回目の途中で切れた一覧と、入力サービスの `display_id: 0`、音声の `media_sink_service` があっても 1 件になることの確認)
2. `secondaryVideoWithoutDisplayType`: 実ログの形に倣った短いログ(テスト内で組み立てる)で、id 2 の映像(`display_id: 0`、`DISPLAY_TYPE_MAIN`、`VIDEO_1920x1080`)と、id 10 の映像(`display_id: 1`、`display_type` なし、`VIDEO_800x480`)を申告。`videoSinks.size() == 2`、2 件目が `serviceId == 10`・`displayId == 1`・`displayType == null`、`hasSecondaryVideo` が true、`hasCluster` が false(今の判定は変えない)。`toText()` が `Secondary video: YES` と `type=?` を含む。
3. `clusterDisplayTypeIsSecondary`: id 11 の映像を `display_id: 0`・`display_type: DISPLAY_TYPE_CLUSTER` 1 件だけ申告したとき、`hasSecondaryVideo` が true、`hasCluster` が true。
4. `navStatusEventsIgnoreInputType32769`: 実ログの後ろに、`InputSource::routeMessage ... channelId:3 type:32769` の行を 5 行と、`NavigationStatusEndpoint::virtual int NavigationStatusEndpoint::routeMessage(uint8_t, uint16_t, const shared_ptr<IoBuffer> &):100 channelId:6 type:32769` の形の行を 4 行足して `parse`。`navStatusEvents == 4`、`navStatusSamples.size() == 3`(最初の 3 行)、`toText()` が `Nav status events: 4` を含む。
5. `newDiscoveryResetsVideoSinks`: 1 回目の REQUEST の後に `display_id: 1` の映像を申告し、2 回目の REQUEST の後に `display_id: 0` の MAIN だけを申告。`videoSinks.size() == 1`、`hasSecondaryVideo` が false。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh --offline -p tools/carprobe -Pandroid.useAndroidX=true testDebugUnitTest`

`assembleDebug` と実機へのインストールは司令塔が行うので、流さない。

## 報告
- 変えたファイルと、足したテストの名前。
- 実行したコマンドと結果(テスト件数・失敗件数)。
- 「決めたこと」(RULES.md の 1)。
