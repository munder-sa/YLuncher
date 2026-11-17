最初に tasks/RULES.md を読み、従う。

# Task 254: Apple Music の行単位(単語の時刻なし)の歌詞も使う

## 背景(実機ログで確認済み、2026-11-17)
DYGL「All Tomorrows」などで、Apple Music 本体には歌詞が出るのに YLuncher は「歌詞なし」になる。ログでは `apple result accepted=false reason=UNSUPPORTED` → AMLL 404 → LRCLIB の Missing(キャッシュ)で終わっていた。Apple から TTML は返っているが、`TtmlParser` は単語の時刻(`<span begin>`)が1つも無い TTML を `Unsupported` にし、Apple の結果は単語単位のときしか採用していない(設計書 `docs/superpowers/specs/2026-11-04-apple-music-lyrics-design.md` で「Apple 行同期は後続」としていたもの)。

新しい優先順位は **Apple の単語単位 → AMLL の単語単位 → Apple の行単位 → LRCLIB(既存のまま)**。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/TtmlParser.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/AmllClient.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsClient.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/AppleLyricsCache.kt`
- write: `app/src/main/java/com/gt8d/launcher/media/lyrics/LyricsRepository.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/TtmlParserTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsClientTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsCacheTest.kt`
- write: `app/src/test/java/com/gt8d/launcher/media/lyrics/AppleLyricsRepositoryTest.kt`

## 仕様

### 1. `TtmlParser`: 行単位の結果を、呼び出し側が望んだときだけ返す
- `TtmlParseResult` に `data class LineSynced(val lines: List<LyricLine>) : TtmlParseResult` を足す(各 `LyricLine` の `words` は空)。
- `parse(...)` に引数 `allowLineSynced: Boolean = false` を足す。既定値 `false` のときの動作は今と一字一句同じにする(既存の `TtmlParserTest`・AMLL の動作を変えない)。
- `allowLineSynced = true` で、次をすべて満たすときだけ `LineSynced` を返す。それ以外は今と同じ結果。
  - 解析中に `unsupported(...)` が1度も呼ばれていない(今の `unsupported` フラグが false)
  - 行(`<p begin>` で始まり文字がある行)が1行以上ある
  - どの行にも単語が無い(今 `"no-timed-words"` で `Unsupported` になる場合)
- 行の検証(開始時刻の順・重なり・`durationMs` を超えない・`InvalidData` の扱い)は今の処理をそのまま通す。行の `start` と `text` は WordSynced と同じ作り方にする。

### 2. `AmllClient`
- `TtmlParseResult` の `when` に `LineSynced` を足し、`Unsupported` と同じ扱いにする(AMLL は `allowLineSynced` を渡さないので実際には来ない。網羅のため)。

### 3. `AppleLyricsClient`
- `AppleLyricsResult` に4番目の引数 `val lineSynced: List<LyricLine>? = null` と5番目の引数 `val detail: String? = null` を足す。`toString()` は変えない(歌詞の文字をログ・文字列に出さない)。
- `detail` は「非対応」の理由コード。`TtmlParser.parse` の既存の `diagnostic` コールバックで `"Unsupported <理由>"` を受け取ったときだけ、`<理由>` の部分を拾う。拾うのは正規表現 `[a-zA-Z-]{1,32}` に完全一致する文字列だけ(今の理由は `root` / `role` / `timeContainer` / `line-without-begin` / `nested-timed-span` / `span-end-without-begin` / `element-or-timing` / `no-timed-words` の固定文字列)。それ以外の診断文字列(`WordSynced lines=…`、`Invalid doctype` など)は捨てる。`TtmlParseResult.Unsupported` の型は変えない。
- `parseLyrics` で `TtmlParser.parse(ttml, durationMs, allowLineSynced = true)` を呼ぶ。`LineSynced` のときは `AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.UNSUPPORTED, lineSynced = parsed.lines)` を返す。`Unsupported` のときは `AppleLyricsResult(LyricsState.Missing, AppleLyricsReason.UNSUPPORTED, detail = 拾った理由)` を返す(`state` と `reason` は今の行単位のときと同じ。既存テストの `state`・`reason` の期待を変えないため)。他の結果は今のまま。
- `DiagLog` は足さない(`AppleLyricsClientTest` が DiagLog が空であることを確かめている)。

### 4. `AppleLyricsCache`
- `put` で `reason == UNSUPPORTED`、`state == Missing`、`!lineSynced.isNullOrEmpty()` のものは、有効期限を **30分**(単語単位の成功と同じ)にする。それ以外の `Missing` + `NOT_FOUND` / `UNSUPPORTED`(`lineSynced.isNullOrEmpty()`、空リストも含む)は今のまま 60秒で、`lineSynced = null` として保存する。
- `detail` は保存した結果にもそのまま残す(固定の理由コードだけなので上限計算には含めない)。
- 保存時、`lineSynced` の文字のバイト数も `MAX_TEXT_BYTES` の上限に数える(超えたら保存しない)。保存するときは `state` と同じく変更できない写しにする(`Collections.unmodifiableList`、各行は `copy`)。`get` で返す結果にも `lineSynced` を含める。
- 他の組み合わせの保存可否は変えない(`AppleLyricsCacheTest` の既存の表を守る)。

### 5. `LyricsRepository`
- 補助関数 `private fun AppleLyricsResult.lineState(): LyricsState.Synced?` を足す: `reason == UNSUPPORTED && state == LyricsState.Missing && !lineSynced.isNullOrEmpty()` なら `LyricsState.Synced(lineSynced)`、それ以外は null。`isWordSuccess()` は変えない(OK + 単語なし Synced は今どおり不採用)。
- 曲の処理の中で `var appleLine: LyricsState.Synced? = null` を持つ。
  - 最初の暫定表示: `appleCached?.lineState()` があり、AMLL のキャッシュに単語が無いときは、LRCLIB のキャッシュより先にそれを表示する(`mutableState.value = appleLine`)。`cache hit` / `cache miss` のログは今のまま出す。
  - 600ms 後の Apple の取得(またはキャッシュ `suppressed`)の結果に `lineState()` があれば `appleLine` をそれで更新する(`apple.lineState()?.let { appleLine = it }`)。結果が一時失敗(`Failed` / `Offline`)や行単位なしのときは、暫定表示で入れた `appleLine` を消さない(600ms の間にキャッシュが期限切れになり通信に失敗しても、表示中の歌詞を失わないため)。
  - AMLL の繰り返し処理の中で、`amll.state.hasWords()` の判定の**直後**、LRCLIB のキャッシュ(`cached is Synced || Plain`)の判定の**前**に、`appleLine != null` なら `mutableState.value = appleLine`、`logResult(appleLine, "APPLE_LINE")` として `return@collectLatest`(AMLL が通信失敗でも再試行せず Apple の行単位を使う)。
- `logApple` の行の末尾に ` line=${lineSynced?.size ?: 0}` と、`detail` があれば ` detail=$detail` を足す(行数と理由コードだけ。既存の文字列はそのまま前に残す)。
- Apple の行単位の結果は LRCLIB / AMLL のディスクキャッシュに書かない(既存方針)。

### 変えないこと
- 既存テストの名前とアサーション全件(特に `TtmlParserTest` の `noWordsIsUnsupported`・`emptyLeadIsUnsupported`、`AppleLyricsClientTest.classifiesMissingUnsupportedAndInvalid`、`AppleLyricsCacheTest` の保存しない組み合わせ、`AppleLyricsRepositoryTest.appleFailuresFallBackWithoutPoisoningCaches`)
- 表示側(行単位の Synced は LRCLIB と同じ描画で出る)

## テスト(足すだけ)
- `TtmlParserTest`
  - `lineOnlyTtmlIsLineSyncedOnlyWhenAllowed`: `<p begin>` だけの2行の TTML が、既定では `Unsupported`、`allowLineSynced = true` では2行の `LineSynced`(開始時刻と文字が正しく、`words` は空)。単語が1つでもあれば `allowLineSynced = true` でも `WordSynced`。
  - `lineSyncedStillRejectsUnsupportedStructureAndInvalidTiming`: `allowLineSynced = true` でも、`role` などで `unsupported(...)` に当たる TTML は `Unsupported`、行の時刻が逆順・重なりなどは `Invalid`、行が0の TTML は `Unsupported`。
- `AppleLyricsClientTest`
  - `lineOnlyTtmlKeepsMissingUnsupportedButCarriesLines`: 行だけの TTML で `state == Missing`、`reason == UNSUPPORTED`、`lineSynced` が行の内容と一致。`toString()` に歌詞の文字が含まれない。
  - `unsupportedTtmlCarriesOnlyReasonCode`: `<p>` に `begin` が無い TTML で `detail == "line-without-begin"`、`lineSynced == null`。単語単位の成功では `detail == null`。`DiagLog.dump()` は空のまま。
- `AppleLyricsCacheTest`
  - `lineSyncedUnsupportedIsKeptThirtyMinutesAndBounded`: `lineSynced` 付きは30分ちょうどの前まで取れて期限で消える。`lineSynced` なしの UNSUPPORTED は今どおり60秒。文字数が上限を超えると保存しない。返ってきた `lineSynced` を外から書き換えられない。`lineSynced = emptyList()` の UNSUPPORTED は60秒で保存され、取り出すと `lineSynced == null`。`detail` は保存後も同じ。
- `AppleLyricsRepositoryTest`
  - `appleLineSyncedIsUsedAfterAmllWordsAndBeforeLrclib`: (a) Apple が行単位・AMLL が単語単位 → AMLL を表示。(b) Apple が行単位・AMLL が見つからない・LRCLIB に同期歌詞のキャッシュあり → Apple の行単位を表示し、ログに `source=APPLE_LINE`。LRCLIB の取得は呼ばれない。(c) Apple が行単位・AMLL が通信失敗 → Apple の行単位を表示し、AMLL の再試行をしない。(d) Apple の行単位はディスクの LRCLIB / AMLL キャッシュに書かれない。
  - `cachedAppleLineSyncedIsShownImmediately`: Apple のキャッシュに行単位があれば、600ms を待たずに最初からそれを表示する(AMLL のキャッシュに単語があればそちらが先)。
  - `appleLineSurvivesExpiryAndTransientFailureDuringDebounce`: 暫定表示で行単位を出したあと、600ms の間に Apple キャッシュが期限切れになり、取得が `Failed`(`NETWORK`)を返しても、表示は行単位のまま。
  - ログ: Apple の結果が非対応のとき `apple result` の行に `detail=<理由>` が含まれる(上の (b) などで確認してよい)。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.media.lyrics.*'`

## 報告
- 変えたファイルと足したテスト名
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」
