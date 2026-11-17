# GT8D USB切替 v0.1.0

GT8D 5G / X21-SC162-24 A15 用の独立したAndroidアプリ。YLuncher本体は変更しない。

## 使い方

1. 端末のアプリ一覧から「GT8D USB切替」を開く。
2. 「純正サービスとの通信を確認しました」と表示されることを確認する。
3. 「USB ADBへ切り替え」または「車載モードへ戻す」を選ぶ。
4. 確認画面で「切り替えて再起動」を選ぶ。

変更前の設定をアプリ内に保存し、設定を書き換え、読み戻しが一致した場合だけ再起動を要求する。
「前回変更前の設定に戻す」でも、設定保存・確認・再起動の手順を通る。
USB ADBモードでは車への接続が使えず、車載モードではUSB ADB/scrcpyが切れる。
戻した後は車載画面かHDMI画面からこのアプリを操作する。アプリの削除ではモードは戻らない。

## 対象端末と仕組み

- 実機: `Lito for arm64` / Android 15 / `SC162-V06.000.002`。
- 対象確認: `ro.board.reverse.custom=zhiduojing-wireless`、SDK 35、ビルドID `SC162-` 始まり。
- 変更対象は `persist.sdcarplay.reverse.disable.wireless` だけ。`1`がUSB ADB、`0`が車載。
- `persist.sdcarplay.reverse.disable` は起動スクリプトが `/proc/cmdline` から再設定するため変更しない。
- `com.carsyso.main` の既存サービスへ、パッケージを限定した `com.suding.main.ACTION_EXECUTE_SH` を送る。
- 純正サービスはUID 1000で `/bin/sh -c` を実行する。新アプリ自体にroot・システム権限は与えない。
- 純正サービスの返信は、実行ごとの乱数と操作名が一致する場合だけ採用する。
- 外部から任意コマンドを受け付ける画面やAPI、ネット通信、常駐処理は追加しない。

**USB ADBから入るrootシェルと、通常アプリの権限は別物。** 今回は実機で純正サービスの呼び出しに成功したため、この経路を使っている。他機種や別ファームウェアでの動作は確認していない。

## 失敗時

- 純正サービスから応答がない、対象端末でない、値が0/1でない場合は操作を無効にする。
- 変更直前に端末条件と元の設定値を再確認し、値が変わっていたら書き込まない。
- 書き込みや読み戻しに失敗したら、変更前の値へ復元を試みる。復元の失敗も別に表示する。
- 応答が途切れた場合は、値が変わっている可能性がある。再起動を自動追加せず「状態を確認」で読み直す。
- アプリ終了などで設定変更と再起動の間に止まった場合も、次回起動で現状を読み直せる。
- USBモードは実行時の `sys.usb.config` と次回起動の設定を分けて表示する。未知の構成は未判定と表示する。

アプリが使えずUSB ADBが残っている場合の、実車で認識済みの復帰手順:

```powershell
adb.exe -d shell setprop persist.sdcarplay.reverse.disable.wireless 0
adb.exe -d shell getprop persist.sdcarplay.reverse.disable.wireless
# 0 を確認してから
adb.exe -d reboot
```

## ビルド

Windows、Android SDK platform 35 / build-tools 36.0.0、Android StudioのJBRを使う。
外部ライブラリ・Gradle・パッケージの追加インストールは不要。

```powershell
& .\tools\gt8d-usb-toggle\build.ps1
```

出力: `build/GT8D-USB-Toggle-0.1.0.apk`。
SDK位置は `-AndroidSdk`、開発署名鍵の保存先は `-SigningKeyDirectory` で指定できる。
この環境の既定の署名鍵は、リポジトリ外の書き込み可能なCodex用フォルダーに保存する。
更新インストールに必要なので鍵を保持する。開発用署名であり、配布サービスへの公開は行っていない。

## 検証記録 2026-11-08

ユーザーが、このアプリに限り通常の指示書・allowなしで新規作成・ビルド・実機導入する例外を承認。
実機のモード変更・再起動は別途確認する範囲とした。

- `build.ps1`: Java検査21件成功、APKビルド成功、v3署名検証成功。
- `adb install -r .../GT8D-USB-Toggle-0.1.0.apk`: Success。
- `adb shell am start -W -n com.gt8d.usbtoggle/.MainActivity`: Status ok。
- 一般アプリからの実通信: `op=probe uid=1000 code=0 mode=1 usb=diag,serial_cdev,rmnet,dpl,qdss,adb supported=true`。
- 新アプリ自体は通常UID `10209`。システムアプリ化・root化はしていない。
- `verify_device.py <adb.exe>`: 元のコマンド4種類のAndroidシェル構文検査に成功。
- 同検証: 設定変更・再起動を関数に置換し、Android実機のシェル上で11場面成功。書き込み失敗・元の値への復元失敗・元の値の食い違い・非対応端末を含む。実設定変更・実再起動は実行していない。
- `build/installed-screen.png`: 実機画面を確認。状態取得・ボタン表示に問題なし。
- **未確認:** アプリからの実設定書き込み、再起動、車載→USB ADB→車載の往復、実車での接続。
- 最終確認でも `.wireless=1`、`sys.usb.config=diag,serial_cdev,rmnet,dpl,qdss,adb` のまま。切り替え試験の可否をユーザーへ確認中。
- APK SHA-256: `A5B14646CEDFB542BF5245FC5B9E7D034B5FA037B460A11F0F10D3050F6DE2A0`。

初回ビルドでは環境がSDK位置を差し替えたため参照失敗し、SDK位置の指定を修正した。
模擬検証では安全検査が操作名の文字列にも反応したため、実行コマンドを検査する形へ修正した。
合格条件を変更せず再検証している。

## 決めたこと

- 既存YLuncherと分け、`tools/gt8d-usb-toggle` に独立アプリとして配置。パッケージは `com.gt8d.usbtoggle`、表示名は「GT8D USB切替」、開発版は0.1.0 / 1。
- Javaの標準Android画面で実装し、外部依存を追加しない。min/target SDKは実機に合わせ35。
- 上記の端末条件に限定。純正サービスのUIDが1000であることと設定値が0/1であることも確認する。
- `.wireless`だけを書き換え、USB構成全体や別のプロパティは変更しない。
- 初回と手動更新時は読み取りのみ。切り替えは確認ダイアログを通し、現在値保存→書き込み→読み戻し→再起動の順にする。
- 変更前の値はアプリの非公開SharedPreferencesへ同期保存。保存失敗時は操作を中止する。
- 元の値が変わっていたら中止。書き込み失敗時は復元を試み、復元の成否を分けて表示する。
- 純正サービスだけに宛てたブロードキャストを使い、返信は実行ごとのUUID乱数と操作名で照合する。応答待ちは15秒。
- 画面の状態取得から30秒を超えた操作は、まず状態を読み直す。
- アプリ自身のroot化・システムアプリ化・外部通信・常駐機能は行わない。
- 専用の開発署名鍵をリポジトリ外に保持。Git操作・公開はしない。
