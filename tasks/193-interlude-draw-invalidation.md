最初に tasks/RULES.md を読み、従う。

# Task 193 前奏・間奏の点灯数が変わらない間の再描画を抑制

あなたはcodex-runから起動する実装担当。司令塔ではない。ユーザーは `docs/superpowers/specs/2026-11-05-lyrics-render-cost-design.md` と計画の実行を承認した。このTaskは設計§4の候補Aのみ。Task196で対照APKを用意した後に実行する。

## 変更してよいファイル

- app/src/main/java/com/gt8d/launcher/ui/home/LyricsPane.kt
- app/src/test/java/com/gt8d/launcher/ui/home/LyricsPaneTest.kt

他のファイル、版、依存、既存テストの期待値/名前、.codex.conf、指示書/allowは変更しない。ADB、Git更新、ネットワーク、APK作成は司令塔担当。このTaskでは行わない。

## 実装の契約

現行InterludeDotsのCanvasはposition.valueを毎フレーム読み、interludeLitDotsの結果が同じでも再描画の依存になる。点灯数Intの変化を観測する状態へ分離する。初期案はderivedStateOfを用い、同じ出力では描画を無効化しない。Composeの状態更新契約をテストで確認し、推測で成功としない。

- interludeLitDots、wordFillPosition、前奏終了条件、200msの行判定、withFrameMillisによるposition/fill更新、LyricMotion、行送り/フェード/scaleの値を変更しない。
- currentでない点の行は保持した点灯数とemphasisだけを描き、共有positionの変化を描画依存にしない。currentから外れた時の最終表示を保持し、フェードは今まで通り更新する。
- now/track/行/position Stateインスタンスが変わっても古いStateを参照しない。停止/再開/シーク、行の再利用、前奏から最初の行への変化、通常可視窓と詳細semantics双方を扱う。
- Canvasが読む状態を減らす変更に限定し、ポーリング間隔変更や独自sleepスケジューラーを追加しない。必要な移動/フェード/サイズ変更の描画を止めない。
- 既存の描画observerに非snapshotの観測値を追加してよい。nullの通常経路で記録を保持せず、実歌詞/時刻のログや外部設定は追加しない。テスト専用の安定した観測のため、InterludeDotsの可視性をinternalに変える小変更は許可するが、公開API/semanticsの契約は変えない。

## 検証

既存49件を保持し、少なくとも次の新規ケースを追加する。実装のコピーではなく、状態変化で描画が必要になる契約を検証する。単にテスト内から強制drawを呼んだ回数を「不要描画がない」の証拠にしない。

1. currentの点灯数/フェードが不変の間、positionだけを進めても不要なdraw無効化が起きず、点灯境界では新しい個数が描かれる。境界直前/一致/直後、0〜3を確認。
2. position不変でもemphasis変化が描画へ反映され、旧現在行の点灯数を保持したままフェードする。非currentのposition更新だけでは無効化しない。
3. positionのState差し替え、停止/シーク、current切替で古い参照や保持値を誤使用しない。通常可視窓と詳細semantics経路の同等性を確認。
4. 既存の単語塗りフレーム更新、前奏終了、明暗/送りの時間、画素マスク、window/semanticsの試験を成功させる。

Robolectricの自動描画で直接観測できない場合、SnapshotStateObserverによる描画読取依存の検証と実際のCanvasの描画結果を組み合わせてよい。その場合、どこまでをユニットテストで証明し、どこを実機で確認するか明記する。テストをIgnore/削除/緩和して通さない。

## 実行できるコマンド

- 状態を変更しない読み取り。
- `/home/munde/tools/gradle-run.sh :app:testDebugUnitTest --tests 'com.gt8d.launcher.ui.home.LyricsPaneTest'`

新規ケースの失敗確認→実装→同じ対象の成功確認まで行う。成功後の理由のない再実行や全テストは行わない。必要な修正による再実行は回数/理由を報告する。

## 報告

変更点、実行コマンド、テスト件数/failure/error/skip、実装担当の判断一覧、描画依存の検証方法、実機未確認の点を日本語で報告。候補Aは前奏/間奏の改善であり、単語区間CPU20%を達成したとは報告しない。
