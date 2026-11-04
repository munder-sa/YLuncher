最初に `tasks/RULES.md` を読み、従う。

# Task 175 Apple Music 歌詞応答の構造診断

状態: 2026-11-04、実装と自己確認・司令塔レビュー・実通信の構造確認まで完了。実装担当は司令塔の権限を引き継がない。司令塔が現在のWSL環境で `python` がなく `/usr/bin/python3` があることを確認したため、以下のコマンドは `python3` に統一した。

## 目的

Task 170ではTTML取得と件数を確認したが、既存Kotlinパーサーへの入力に必要なJSONの経路とXML構造は記録していない。検証スクリプトへ本文を出さない構造診断を追加し、司令塔の後続実測でデコーダーの契約を確定できるようにする。

参照: `tasks/170-apple-lyrics-web-probe.md`、`docs/superpowers/specs/2026-11-04-apple-music-lyrics-design.md`。既存スクリプトはユーザーの別作業で作成された未追跡ファイル。全体を書き直さず、今回必要な診断と自己確認だけを追加する。

## 変更してよいファイル

- `scripts/apple-lyrics-probe.py` のみ。

アプリ・設定・指示書・allow・既存の認証方法・通信先・CLI引数は変更しない。追加パッケージ、adb、Git更新、実通信を行わない。

## 実装

1. 既存のHTTPステータス、サイズ、行/span数の出力と重複排除を維持し、各TTMLの要約に構造情報を加える。本文を保存しない。
2. JSONのTTMLまでの経路は、`data`、`attributes`、`relationships`、`lyrics`、`syllable-lyrics`、`ttml`、`included` だけを既知のキーとして出力する。配列は `[]`、その他のキーは値・文字数に関係なく `<other>`。未知キーの元の文字列をログに出さない。経路の最大深さ32、出力する異なる経路は最大16個とし、超過は固定フラグで示す。TTML経路の違いは本文が同じでも分かるようにする。
3. XMLは既存のサイズ・DTD/ENTITY拒否を維持する。構造診断で走査する全要素は最大25,000、深さ64。上限超過は固定の診断コードとし、生の例外を出さない。
4. namespaceの要約はTTML標準、TTML metadata、既知のitunes内部namespace、XML標準、その他の件数にする。未知URIを出力しない。
5. 属性は存在・件数だけを集計する。対象は `begin`、`end`、`dur`、`timeContainer`、metadataの `role` / `agent`、`xml:space`。`role`値は `x-bg`、`x-translation`、`x-roman` とその他だけを区別し、未知値を出力しない。`agent`値・ID・テキストを出力しない。
6. 時刻表記は「秒数（小数3桁以内、末尾s任意）」「分秒/時分秒（小数3桁以内）」「その他」に分類する。生の属性値を出さない。`p`のbegin欠落、時刻付きspanの入れ子、時刻付きdiv、主旋律pの同時開始/区間重なりを件数で出す。背景/翻訳/音訳/head配下は主旋律の集計から除く。解釈不能な時刻は判定不能件数へ回し、重なり無しと断定しない。
7. 診断の関数・内部名は実装担当が選んでよい。報告の「決めたこと」に列挙する。成功してもKotlinパーサーで採用できたとは報告しない。

## 自己確認

既存のassertを削除・変更せず、`--self-test`に以下を追加する。合成XML/JSONと偽通信のみ使う。テストケースは固定のキーで件数を出力してよい。

- 直接応答・relationships内・配列・同じ本文が複数経路にある場合の経路と重複を正しく分類する。
- 未知のJSONキー、XML namespace、role、agent、属性値、テキストに秘密値のダミーを埋め、全診断出力へ混入しないことを確認する。
- 行時刻欠落、秒/clock形式、背景除外、入れ子の時刻span、主旋律の重なり、判定不能時刻を合成データで確認する。
- 深さ・要素数・経路数の上限とその境界を確認する。既存のDTD拒否・リダイレクト拒否・トークン非出力を維持する。

## 実装担当が使ってよいコマンド

- 読み取りコマンド。
- `python3 scripts/apple-lyrics-probe.py --self-test`

自己確認は変更後に1回。失敗を直した場合だけ再実行する。Pythonが利用できなければコマンドを言い換えず報告する。

## 司令塔による後続実測

実装担当は実行しない。差分と自己確認をレビューした後、ユーザーがPC上の非表示ダイアログへ入力できる時に次の既存コマンドで確認する。今回の指示書を読むだけでは認証情報の探索や取得方法の拡大を許可しない。

```sh
python3 scripts/apple-lyrics-probe.py --interactive --song-id 1537460612 --storefront jp
```

記録はHTTPコード、固定化したJSON経路、構造/時刻の分類と件数。既知キー以外の経路や非対応形式があれば、仕様を確定する前に必要な調査を司令塔へ返す。他曲の実測とAndroidでの取得・パーサー採用・表示は後続の確認にする。

## 報告

変更箇所、実行コマンド、追加した自己確認の件数・失敗件数、既存自己確認の維持、本文・秘密値が出力されない根拠、未実行の実通信、決めたことを日本語で報告する。

## 実装と自己確認の結果

- `codex-run tasks/175-apple-lyrics-contract-probe.md`: 終了コード0、逸脱なし。ログと変更前のスクリプトは `.codex-bak/20261104-125632/`。
- `python3 scripts/apple-lyrics-probe.py --self-test`: 実装担当が1回実行、終了コード0。追加20ケース、失敗0。既存の自己確認も成功。司令塔はログとソースを照合し、同じテストを再実行していない。
- 司令塔のAST比較で既存self-testの12個のassertが同一であることを確認。認証・通信・CLIの8関数/クラスも変更なし。新規の診断とテストをレビューし、出力が固定キー・分類・件数だけで構成されることを確認した。
- 現時点でKotlinパーサーでの実データ採用確認・Android本体への実装は未実施。

### 決めたこと

- 現在の環境に合わせ、指示書とallowを `python3` に統一した。
- 診断関数名は `classify_time`、`ttml_structure`、追加自己確認は `contract_self_test`。
- JSON経路はトークン配列の `paths`、同じ本文の出現数は `occurrences`。匿名化後の異なる経路を応答全体で最大16個保持する。
- 深さはJSONルートを0、XMLルートを1として数える。JSON超過は固定フラグ、XML超過は `ttml_depth_limit` / `ttml_element_limit`。
- namespace件数は宣言/使用された異なるURI数。未使用宣言も含め、名前空間なしは数えない。
- 時刻の書式と数値範囲は既存Kotlinパーサーに合わせ、整数ミリ秒で比較する。
- 新設の時刻付きspan/div集計は `begin` / `end` / `dur` のいずれかの存在で判定する。
- 同時開始/重なりは行のペア数。終了と次の開始が一致する場合は重なりに含めない。
- 時刻欠落、不正値、逆転区間、親の時刻や `timeContainer` の解決が必要な場合は判定不能へ分類する。`dur`付き行の区間も推定しない。
- 指定された除外role以外は主旋律候補に残し、未知roleは `other` として集計する。

## 認証付き実測の結果

`python3 scripts/apple-lyrics-probe.py --interactive --song-id 1537460612 --storefront jp` は終了コード0。ユーザーがPCの非表示ダイアログへ入力したWeb用トークンを使い、本文やトークンは保存・出力していない。プロセスは終了した。

- 公開ページ: HTTP 200、2,485,490 bytes。JS候補3本のうち最初の1本(3,344,359 bytes)から公開Bearerを取得。
- `/v1/me/storefront`: HTTP 200。後続要求には応答から検証したstorefrontを使用した。地域コード自体は診断に出していない。
- `syllable-lyrics`: HTTP 200、JSON 19,331 bytes、TTML 17,649 bytes。取得経路は **`data[].attributes.ttml`**、文書1つ、`timing=Word`、40行・開始時刻付き40行、開始/終了時刻付きspan 277個。
- 単語版のXMLは337要素・最大深さ6。標準TTML、metadata、itunes、XML以外のnamespaceなし。role指定なし、agent属性40個、時刻付きdiv10個、時刻spanの入れ子なし、`timeContainer`なし。begin/end/durの表記はすべて既知の秒数またはclock形式。
- 親のdivに時刻があるため、診断では主旋律40行の時刻/区間が「判定不能」となる。重なり件数0は、重なり無しの実証ではない。既存Kotlinパーサーが絶対時刻として採用できるかは後続の実データ確認に残す。
- `lyrics`: HTTP 200、JSON 6,102 bytes、TTML 5,537 bytes、同じ `data[].attributes.ttml`、`timing=Line`、40行、時刻付きspanなし。
- 曲情報のinclude経路も200。`data[].relationships.lyrics.data[].attributes.ttml` と `data[].relationships.syllable-lyrics.data[].attributes.ttml` の2文書。初期クライアントは直接のsyllable-lyrics経路だけを使う。
- 文書合計4は経路間の重複を含み、確認曲数は1曲。JSON深さ/経路数の超過なし。HTTP取得と構造が確認できたのでTask 176の実装へ進める。
