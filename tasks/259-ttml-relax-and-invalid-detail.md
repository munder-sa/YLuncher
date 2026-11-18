最初に tasks/RULES.md を読み、従う。

# Task 259: TtmlParser の重複・境界・時刻表記の緩和と AppleLyricsClient の INVALID 理由コード追加

## 背景(実機ログで確認済み、2026-11-18)
こっちのけんと「はいよろこんで」や Wakeys & こっちのけんと「それもいいね」など、Apple Music 本体で単語同期歌詞が出る曲で `[Lyrics] apple result accepted=false reason=INVALID` となり、LRCLIB の行単位歌詞や Missing にフォールバックしている。
現在の `TtmlParser` は `LyricLine` / `LyricWord` に保存しない `end` 時刻や親子境界に対して、Apple Music / AMLL の実データ仕様で正規に存在する「前後行の重なり・同時開始」「0ms 要素(`end == begin`)」「親子 `div`/`p`/`span` の丸め誤差はみ出し」「隣接単語の同一開始時刻」「秒単位 `durationSec` 切り捨てによる曲末数秒のはみ出し」「1桁時間・4桁以上の小数秒」をすべて `Invalid` として曲ごと破棄している。また `AppleLyricsClient` が `INVALID` 時に `detail` を出さないため、どの条件で落ちたかがログから見えない。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/TtmlParser.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsClient.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/TtmlParserTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsClientTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/AmllClientTest.kt`

## 仕様

### 1. `TtmlParser.kt`: 実データで正規に現れる時刻パターンの許容と `Invalid` 理由コード化
- `InvalidData(val reason: String)` に固定の短い英小文字ハイフン理由コードを持たせ、`catch (e: InvalidData)` で `diagnostic("Invalid ${e.reason}")` を通知してから `TtmlParseResult.Invalid` を返す。その他の箇所も以下の固定コードを使う:
  - `"doctype"`: `<!DOCTYPE` 事前検出または `XmlPullParser.DOCDECL`
  - `"xml"`: `XmlPullParserException`、`!rootSeen`、または `parser.text == null`
  - `"limit"`: `parser.depth > 64`、`lineCount > 2000`、`wordCount > 20000`、または1行テキスト長 `> 16384`
  - `"time-format"`: `time(value)` での形式不一致、`minutes > 59`、`secs > 59`、または `Long` オーバーフロー
  - `"element-range"`: 同一要素単体で `finish != null && finish < start`（厳密な終了時刻の逆転のみ）
  - `"word-order"`: 同一行内の非空白単語で `previous != null && word.start < previous`（厳密な単語開始時刻の逆転のみ）
  - `"beyond-duration"`: 許容幅込みの曲長超過
- **時刻表記(`time(value)`)の緩和**:
  - `seconds` を `Regex("([0-9]+)(?:\\.([0-9]+))?s?")` に変更し、小数部が4桁以上ある場合は先頭3桁を切り出してミリ秒化する(`groupValues[2].take(3).padEnd(3, '0')`。`1.` のような末尾ドットのみは不一致のまま `"time-format"` で `Invalid`)。
  - `clock` を `Regex("(?:([0-9]+):)?([0-9]{1,2}):([0-9]{2})(?:\\.([0-9]+))?")` に変更し、1桁時間(`0:01:23.456` 等)と4桁以上の小数秒(`take(3).padEnd(3, '0')`)を受理する(`minutes > 59`・`secs > 59`・オーバーフローは従来どおり `"time-format"` で `Invalid`)。
- **0ms 要素(`finish == start`)と親子境界の緩和**:
  - `div` / `p` / `span` 自身の `begin`・`end` 検証は、`finish < start` のみ `throw InvalidData("element-range")` とし、`finish == start`(0ms 要素)は許容する。
  - 親要素(`parent.start` / `parent.end`)との包含チェック(`start < parent.start`、`start >= parent.end`、`finish > parent.end`)は廃止する(`Frame` に `start` / `end` を持たせる必要もない)。
- **空白のみ `span` と行内単語・行間重複・`durationMs` の緩和**:
  - `p` 内の時刻付き `span`(`!excluded && isSpan && begin != null`)は、`wordCount` 上限チェック(`++wordCount > 20000` → `"limit"`)は `START_TAG` で維持しつつ、`Frame` に単語開始時刻と開始文字オフセット `current.text.length` を記録し、`END_TAG` で当該 `span` が追加した部分文字列 `current.text.substring(wordOffset)` が `isNotBlank()` のときだけ `current.words` に追加する(これにより `<span begin="00:00.000" end="00:00.000"> </span>` のような空白専用 `span` は文字オフセットだけ進め、ダミー時刻 `0` で単語順序を壊さない)。
  - 同一行内の単語順序検証は `word.start < previous` のときのみ `throw InvalidData("word-order")` とし、同一開始時刻(`word.start == previous`)は許容する。また `bound`(`line.end ?: next`)による `word.start >= bound` / `word.end > bound` チェックは廃止する。
  - 行内の先頭単語 `firstWord = line.words.firstOrNull()` が丸め誤差等で `firstWord.start < line.start` となっている場合は、その行の実効開始時刻を `minOf(line.start, firstWord.start)` として扱う(これにより全単語で `word.start >= line.timeMs` が保証される)。
  - 行のソートは実効開始時刻による安定ソート(`sortedBy`)とし、前後の行の重なり(`line.end > next`)および同一開始時刻(`line.start == next`)はどちらも許容する(以前の167行目の拒否を削除)。
  - `durationMs` チェックには秒単位切り捨てとアウトロ誤差を吸収する `5_000L`(5秒)の許容幅を持たせ、`durationMs != null && durationMs >= 0` のとき、実効開始時刻が `if (durationMs > Long.MAX_VALUE - 5_000L) Long.MAX_VALUE else durationMs + 5_000L` を超える場合のみ `throw InvalidData("beyond-duration")` とする。

### 2. `AppleLyricsClient.kt`: `INVALID` 時の非機密 `detail` 付与
- `DETAIL_CODE = Regex("[a-zA-Z-]{1,32}")`(既存の `UNSUPPORTED_DETAIL` を共用・改名してよい)を満たす固定コードのみを `detail` に入れ、歌詞本文・レスポンス断片・トークン・例外メッセージは一切含めない(`AppleLyricsResult.toString()` も変えない)。
- `failed(reason: AppleLyricsReason, retry: Long? = null, detail: String? = null)` に `detail` 引数を足す(`connect` 側は変更不要)。
- `fetch` → `authenticatedRequest` / `parseLyrics` / `bounded` で `AppleLyricsReason.INVALID` を返す経路に以下の固定 `detail` を付ける:
  - 応答サイズ超過(`tooLarge`): `"response-too-large"`
  - `parseObject` が `null`: `"json"`
  - `"errors"` が `JsonArray` でない、または非空: `"api-errors"`
  - `"data"` が `JsonArray` でない、または要素数が 1 以外(`data.isEmpty()` の `NOT_FOUND` はそのまま維持し、2件以上は): `"data-shape"`
  - `"attributes"` / `"ttml"` 欠落・非文字列・空白のみ・`MAX_TTML_BYTES` 超過: `"ttml-missing-or-large"`
  - `!hasTtmlRoot(ttml)`: `"ttml-root"`
  - `TtmlParser.parse` の `diagnostic` が `"Invalid "` で始まる場合: プレフィックスを除いた文字列が `DETAIL_CODE` に一致すれば `detail` として `failed(AppleLyricsReason.INVALID, detail = detail)` に渡す(`"Unsupported "` の既存処理はそのまま維持)。
  - `bounded` の `catch (_: Exception)`: `failure` に渡すか、`parseObject` の `SerializationException` / `IllegalArgumentException` は `parseObject` 側で `null`(`"json"`)にするなどし、残りの予期しない例外による `INVALID` には `"exception"` を付ける(ただし `connect` 側の `AppleConnectResult.Unavailable` シグネチャは変えない)。

### 変えないこと
- `LyricsRepository.kt`、`AppleLyricsCache.kt`、`AmllClient.kt`、`LyricsPane.kt` のコード
- セキュリティ・上限チェック(DOCTYPE/外部エンティティ拒否、XML破損拒否、ネスト64・2000行・20000単語・16384文字上限、負数や60分/60秒以上の不正時刻拒否、要素単体の `end < begin` 拒否、行内の非空白単語の時刻逆転 `word.start < previous` 拒否)
- `UNSUPPORTED` 判定の条件(`root`、`role`、`timeContainer`、`line-without-begin`、`span-end-without-begin`、`nested-timed-span`、`element-or-timing`、`no-timed-words`)と `allowLineSynced` の動作
- 歌詞やトークンを `toString()`・`DiagLog`・`ShadowLog`・例外へ一切出さない制約

## テスト
1. `TtmlParserTest.kt`:
   - 緩和した仕様に合わせて既存の重複・境界テストを更新する(厳密逆転 `end < begin` や単語逆順 `1.0` → `0.5`、`duration + 5000ms` 超過は引き続き `Invalid` であることを検証する)。
   - 次の新規・更新テストで実データパターンを網羅する:
     - 前後行の `end > next.start` 重複および同一 `begin` 行の同時開始が `WordSynced`(および `allowLineSynced = true` 時は `LineSynced`)として順序維持で通ること
     - 0ms 要素(`end == begin`)、親 `div` / `p` の `begin`・`end` からはみ出す `p` / `span`、隣接 `span` の同一開始時刻、空白のみ `<span begin="00:00.000" end="00:00.000"> </span>`、先頭単語が `p` の `begin` よりわずかに早い行が `WordSynced` として正しく文字オフセットを保って通ること
     - 1桁時間(`0:01:02.345`)と4桁以上の小数秒(`1.2349` → `1234L`、`00:42.1234` → `42123L`)が受理されること
     - `durationMs = 161_000L` に対して `165.999s`(`165_999L`)は通り、`166.001s`(`166_001L`)は `Invalid`(`Invalid beyond-duration`)になること
     - 各 `Invalid` ケースで `diagnostic` に `"Invalid doctype"` / `"Invalid xml"` / `"Invalid limit"` / `"Invalid time-format"` / `"Invalid element-range"` / `"Invalid word-order"` / `"Invalid beyond-duration"` が渡ること
2. `AppleLyricsClientTest.kt`(および必要なら `AmllClientTest.kt`):
   - `fetchesOnlyDirectWordLyrics` の `duration = 1` テストを `5_000ms` 許容幅に合わせて更新する(`begin="5.999"` は OK、`begin="6.001"` は `INVALID` かつ `detail == "beyond-duration"`)。
   - `INVALID` 時の `detail` 検証を更新・追加し、単語逆順(`"word-order"`)、不正 JSON(`"json"`)、`errors` 非空(`"api-errors"`)、`ttml` 欠落(`"ttml-missing-or-large"`)、非 `<tt>` ルート(`"ttml-root"`)等でそれぞれ対応する固定コードだけが `detail` に入り、`toString()` やログに機密・歌詞が一切漏れないことを確かめる。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.TtmlParserTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsClientTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsCacheTest' --tests 'com.gt8d.launcher.media.lyrics.AppleLyricsRepositoryTest' --tests 'com.gt8d.launcher.media.lyrics.AmllClientTest'`

## 報告
- 変えたファイルと更新・追加したテスト名
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」

## 司令塔の補足(上の仕様より優先)
- **テスト期待値の更新の許可(RULES.md 4 の例外)**: 次の既存テストは、この Task の仕様変更として期待値を更新してよい。それ以外の既存テストは変えない。名前も変えない。
  - `TtmlParserTest`: `timedDivAcceptsAbsoluteTimesAndRejectsContradictions`、`rejectsInvalidAndOverflowTimes`(`"1.0001"` の箇所のみ)、`wordOrderAndDuplicateTimesRejected`、`lineStartAndNextLineBounds`、`overlappingLeadLinesRejected`、`endValidatedButNotStored`、`lineSyncedStillRejectsUnsupportedStructureAndInvalidTiming`(緩和対象の箇所のみ)
  - `AppleLyricsClientTest`: `fetchesOnlyDirectWordLyrics`(duration 超過の値)、`unsupportedTtmlCarriesOnlyReasonCode`(INVALID 時の `detail`)、および INVALID の `detail` を `assertNull` している箇所
  - `AmllClientTest`: 緩和で結果が変わる TTML 入力を使っている箇所があれば、その入力だけ(期待値を変える前に、なぜ変わるかを報告に書く)
  - 意味が変わって名前と合わなくなるテスト(例: `overlappingLeadLinesRejected`)は、名前を残したまま中身を「重なりは受理、厳密な逆転は拒否」に更新し、拒否側の検証を必ず1つ以上残す。
- `Invalid` で残す条件(要素単体の `end < begin`、非空白単語の `start` 逆転、`durationMs + 5000` 超過、時刻形式不正、DOCTYPE、XML 破損、各上限)は、それぞれ少なくとも1件のテストで `Invalid` と理由コードを検証する。
- `AppleLyricsClient` の `INVALID` に detail を付けるため、内部の `ApiResult.Unavailable` や `bounded` の `failure` に detail を渡す形へ変えてよい(`private` の範囲)。公開の `AppleLyricsResult`・`AppleConnectResult` の型と `toString()` は変えない。
- `TtmlParser` の `diagnostic` 文字列は `AmllClient` では使われていない。`AmllClient.kt` は変えない。
- 先頭単語による行の実効開始時刻の補正(`minOf`)は、`LyricLine.timeMs` にもその値を使う。
