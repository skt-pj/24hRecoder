# 0.7.92 Gem録音停止候補 比較試験
公式HCI解析: 03→03ff と DATA CCCD OFF/ON → 01 00 00 → 音声 →02 00→03→05(filename) は観測されるが、CCCD変更がSTOPかは未確定。
STOPテストは1候補だけ実行。各回、Gem赤LED消灯を確認し明示チェック後、標準接続→03録音開始→5秒→停止候補→3秒観測→BLE終了。自動停止復旧/ダウンロード/ファイル完了ACK 0x05は実行しない。BLE成功と録音停止を混同せず、LED・振動を人間が報告する。
既存ケース STOP_A/B/C/AB/AC/BC/ABC/OFFICIAL_TIMING に加え、OFF_WAIT_ON、ON_OFF、LIST_DELAY、03_REPEAT、DISCONNECT、OFF_DISCONNECT。
公式アプリのファイル転送や録音の制御が同じ0x03を使うため、03ffで停止成功と判定しない。赤LED点灯の場合、Gem本体ボタンで直ちに停止。重要な未転送音声がある場合、ファイル転送テストは行わない。
