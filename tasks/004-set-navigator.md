# Task 3: セット巡回

計画 `docs/superpowers/plans/2026-10-28-gt8d-launcher.md` の Task 3 に従う。既存ファイルは変更しない。

## 作るファイル(これで全部。許可外の書込みは拒否され、そこで停止する)

- `app/src/main/java/com/gt8d/launcher/sets/SetNavigator.kt`
- `app/src/test/java/com/gt8d/launcher/sets/SetNavigatorTest.kt`

## 仕様

パッケージ `com.gt8d.launcher.sets`。純粋な Kotlin(`android.*` を import しない)。

```kotlin
object SetNavigator {
    const val HOME = 0
    fun next(current: Int, setCount: Int): Int
    fun prev(current: Int, setCount: Int): Int
    fun clamp(saved: Int, setCount: Int): Int
}
```

index 0＝ホーム、1..setCount＝セット。ホームを含めて `setCount + 1` 個を輪にして巡回する。
- `next`: 次へ。最後のセットの次はホーム(0)
- `prev`: 前へ。ホームの前は最後のセット
- `setCount == 0`: `next`/`prev` は常に `HOME`
- `clamp`: `saved` が `0..setCount` の範囲ならそのまま、範囲外(負数を含む)は `HOME`

## 手順(TDD)

1. テストを書く。必須のテスト名:
   - `next_wrapsToHome`: `next(3,3)==0`、`next(0,3)==1`
   - `prev_wrapsToLast`: `prev(0,3)==3`、`prev(1,3)==0`
   - `next_withNoSets_staysHome`: `next(0,0)==0`、`prev(0,0)==0`
   - `clamp_outOfRange_goesHome`: `clamp(5,3)==0`。あわせて `clamp(-1,3)==0`、`clamp(2,3)==2`
2. `/home/munde/tools/gradle-run.sh test` を実行し、新しいテストが失敗することを確認する
3. 実装する
4. 同じコマンドで全テストが通ることを確認する(最大3回直す。通らなければ、失敗したテスト名とエラーの先頭30行を報告して止まる)

## 守ること

- テストの期待値を、実装に合わせて弱めない
- 最後に、`test` の結果(実行件数と失敗件数)と、作ったファイル名を報告する。確認していないことを「確認した」と書かない
