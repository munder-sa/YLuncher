最初に tasks/RULES.md を読み、従う。

# Task 245: ArtworkBackgroundHolder の画面離脱時の巻き戻りを直す

`ArtworkBackgroundHolder`(プロセス共有の `sharedArtworkBackground`)は、`remove(client)` で残った画面があると `select(clients.values.last())` を呼ぶ。`linkedMapOf` は既存キーの更新で並び順が変わらないため、`last()` は「最後に更新した画面」ではなく「最後に追加した画面」になる。このため、パネルを閉じたときに、停止中のホームが持っている古い `NowPlaying`(例: `artwork = null`)で、抽出済みの色や進行中の抽出が捨てられる。これを直し、あわせて `rememberArtworkBackground` の条件付き早期 return をやめる。

## 作る/変えるファイル(これ以外は触らない)
- write: `app/src/main/java/com/gt8d/launcher/ui/home/ArtworkBackground.kt`
- write: `app/src/test/java/com/gt8d/launcher/ui/home/ArtworkBackgroundTest.kt`

## 仕様

### 1. 各画面の更新順を記録する
- `clients` の値を、`NowPlaying?` と更新順 `seq: Long` の組にする。`seq` は holder 内の単調増加カウンタで、`update(client, now)` のたびに振り直す。

### 2. `remove(client)`
- 削除した画面の `seq` を覚えてから削除する。
- 残りが空なら従来どおり: 進行中のジョブを取り消し、`request = null`、`colors.value = null`(`leavingScreenCancelsAndRejectsResult` を保つ)。
- 残りがある場合:
  - 残りの中で `seq` が最大の画面が、削除した画面より**新しい**(seq が大きい)ときだけ、その画面の `NowPlaying` で `select` する。
  - そうでなければ(残りがすべて削除した画面より古い)、`image` / `retained` / `completed` / `colors.value` / 進行中の `job` と `request` を**そのまま保つ**。進行中の抽出の結果が、この `remove` のせいで捨てられないようにすること(今の `remove` は先頭で `generation++` しているため、そのままだと結果の照合 `target.generation == generation` が外れる。この経路では `generation` を進めない、または `request.generation` を合わせるなどで対応する)。次の `update(...)` で通常どおり選び直される。

### 3. `rememberArtworkBackground(now)`
- `if (now?.artwork == null) return remember { mutableStateOf(null) }` をやめる。`val observed = holder.colors.collectAsState()` を常に呼び、`now?.artwork == null` のときは `null` を持つ `State<ArtworkColors?>` を返す形にする(フックの呼び出し順を、条件によらず同じにする)。返す値の意味は今と同じ。

### 変えないこと
- `ArtworkBackgroundTest` の既存テスト全件(特に `sameTrackNewBitmapInstanceDoesNotRestart`、`generationChangeReextracts`、`failureIsRetained`、`leavingScreenCancelsAndRejectsResult`、`simultaneousClientsSharePendingExtraction`)
- `ImageKey` の構成(`Bitmap.generationId` は入れない)
- `colorsFor` / `imageColorsFor` の判定、`sampleArtworkColors`、`artworkBackground` の描画

## テスト(`ArtworkBackgroundTest` に足す)
- `staleBackgroundClientDoesNotClearColorsOnPanelClose`: `home` が `song(null)` を渡したあと、`panel` が `song(bitmap())` を渡して抽出が完了(`red`)。`holder.remove(panel)` のあとも `holder.colors.value == red`。続けて `holder.remove(home)` で `null` になる。
- `staleBackgroundClientKeepsPendingExtractionOnPanelClose`: `home` が `song(null)`、`panel` が `song(bitmap())` を渡し、抽出が終わる前に `holder.remove(panel)`。そのあと抽出が完了すると `holder.colors.value == red` になり、抽出は1回だけ。
- `newerRemainingClientIsSelectedOnRemove`: `panel` が `song(bitmap())` で `red` まで抽出したあと、`home` が `song(null)` で更新(home の方が新しい)。`holder.remove(panel)` で `holder.colors.value` が `null` になる(新しい画面の状態は従来どおり反映される)。
- クライアントは `Any()` で作ってよい。既存テストのヘルパー(`song`、`bitmap`、`red`)を使う。

## 使ってよいコマンド(一字一句このまま)
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.ArtworkBackgroundTest'`

## 報告
- 変えたファイルと足したテスト名
- 実行したコマンドと結果(件数・失敗数)
- RULES.md の 1「決めたこと」
