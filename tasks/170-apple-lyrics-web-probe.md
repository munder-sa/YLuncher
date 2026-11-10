最初に tasks/RULES.md を読み、従う。

# Task 170: Apple Music Web 歌詞 API の読み取り検証

ユーザーの「すすめて」に基づき、司令塔として今回の検証範囲を定める。

## 目的
- Manzana が参照する Web API で、既知の Apple Music 曲 ID から TTML を取得できるかを調べる。
- 初回はユーザー認証なしで公開 Web 資産と API の応答を確認する。
- ユーザー認証を使う検証は、認証情報の取得・利用方法への回答を受けてから行う。
- ユーザーは Web 用トークンを PC 上で非表示入力する方式を選択済み。`--interactive` はローカルのマスク入力ダイアログを開く。端末設定の認証探索は行わない。
- 端末内の認証設定の探索は自動審査に拒否されたため、承認なしに再実行・迂回しない。

## 作るファイル
- `tasks/170-apple-lyrics-web-probe.md`
- `tasks/170-apple-lyrics-web-probe.allow`
- `scripts/apple-lyrics-probe.py`

## 検証プログラム
- Python 標準ライブラリだけを使う。パッケージの追加、アプリの変更、端末へのインストールは行わない。
- 検証曲は、既存の実機調査でカタログ ID を確認済みの Lemon (`1537460612`) とする。
- 公開 Web JavaScript の Bearer トークンはメモリ内だけに保持する。
- ユーザーのトークンは、明示選択した対話モードで非表示入力する。表示・保存・コマンドライン引数への指定を行わない。
- 通信先を Apple の既知のホストに限定し、認証ヘッダーを付けた要求のリダイレクトを拒否する。
- タイムアウトと応答サイズ制限を設ける。無限再試行しない。
- 出力は HTTP ステータス、TTML の構造・行数・時刻件数など。本文、HTTP ヘッダー、トークン、生レスポンス、認証情報を含みうる例外メッセージを出さない。
- TTML の原文や認証情報をファイルへ保存しない。
- 合成 XML と偽通信を用いた自己確認で、本文非出力と単語時刻の検出を確認する。

## 使ってよいコマンド
- 読み取りコマンド。
- `python scripts/apple-lyrics-probe.py --self-test`
- `python scripts/apple-lyrics-probe.py --public-only --song-id 1537460612 --storefront jp`
- 認証方法が承認された後だけ: `python scripts/apple-lyrics-probe.py --interactive --song-id 1537460612 --storefront jp`

## 報告
- 実際の取得成否、HTTP ステータス、取得できた TTML の行・単語時刻件数。
- 未確認事項と実行したコマンド、決めたこと。

## 実施結果（2026-11-04、JST）

対象は Lemon、Apple Music カタログ ID `1537460612`。PC 上の非表示入力ダイアログにユーザーが入力した Web 用トークンで検証した。Android 内の認証設定は調査していない。

実行した検証コマンド:
- `python scripts/apple-lyrics-probe.py --self-test`: 合成データと偽通信による自己確認に成功。
- `python scripts/apple-lyrics-probe.py --public-only --song-id 1537460612 --storefront jp`: ユーザー認証なし。曲情報 200、歌詞の2エンドポイントは404。TTMLは0件。
- `python scripts/apple-lyrics-probe.py --interactive --song-id 1537460612 --storefront jp`: ユーザー認証あり。終了コード0。

認証ありの実測:

| 要求 | HTTP | 取得内容 |
| --- | --- | --- |
| `/v1/me/storefront` | 200 | 認証成功。返された利用地域を後続要求に使用 |
| 曲情報に `include[songs]=albums,lyrics,syllable-lyrics` | 200 | 行同期・単語同期の2種類のTTML |
| `/v1/catalog/{storefront}/songs/1537460612/syllable-lyrics` | 200 | `itunes:timing=Word`、17,649 bytes、40行、開始・終了時刻付きspan 277個 |
| `/v1/catalog/{storefront}/songs/1537460612/lyrics` | 200 | `itunes:timing=Line`、5,537 bytes、40行、時刻付きspan 0個 |

どちらも40行すべてに開始時刻がある。各APIの取得件数を足したログ値は4文書だが、曲数は1曲、確認できた形式は2種類であり、経路間の重複を含む。277は時刻付きspanの件数であり、自然言語上の単語数を数えた値ではない。

公開ページ `/us/browse` は301だったため、ユーザー認証を含まない公開Web要求に限り、同じApple Webホストへのリダイレクトを最大3回許可した。認証付きAPI要求のリダイレクトは引き続き拒否する。

トークン・歌詞本文・生のTTMLはファイルや診断出力へ保存していない。入力トークンを保持した検証プロセスは正常終了した。Androidへのインストール、再生操作、YLuncher本体の変更、Gradleビルド・テストは行っていない。

未確認: 他曲・別地域での取得、トークン更新、非公開APIの継続性、実データを既存のKotlin TtmlParserと描画へ通した結果。今回確認したのは、ユーザーの認証でAppleから単語同期TTMLを取得できることまで。

### 決めたこと
- 成功判定はHTTP 200だけでなく、TTMLのXML解析と行・時刻付きspanの確認までとした。
- 今後のYLuncher統合候補では、このWeb API取得方式の優先度を上げる。アプリ内部フックや端末改変を導入せずに検証できたため。
- 実測値と未確認の統合動作を分け、認証情報や歌詞本文を調査記録に含めない。
