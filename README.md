# 24hRecoder

Android 16 / Pixel 10aを初期基準端末とした24時間録音アプリです。

## 現在の実装

- microphone Foreground Serviceによるバックグラウンド録音
- 録音Serviceを`:recorder`専用プロセスに分離
- AudioRecord: mono / 16 kHz / PCM 16bit
- MediaCodec: AAC-LC / 32 kbps
- MediaMuxer: M4A
- 5分セグメント
- `START_STICKY`によるOSプロセス再生成時の復旧
- 起動意思・状態・heartbeatのファイル永続化
- 600 MB音声上限と古い音声のローリング削除
- `AudioRecordingCallback`による入力silenced検出ログ
- 再起動後の自動録音開始は行わず、再開通知のみ表示
- JSON Lines形式のイベントログ
- `whisper.cpp v1.9.1`をAndroid NDKで組み込んだ完全ローカル文字起こし
- 多言語Whisper `base`モデルを使用
- 5分M4AをAndroid MediaCodecでPCMへ復号し、端末内で推論
- WorkManagerで文字起こしを録音処理から分離し、低バッテリー時は延期
- 文字起こし結果はtemp書き込み・fsync・renameで永続保存
- 文字起こし成功後も最近のM4Aを保持し、記録詳細から再生可能
- 容量圧迫時は古い文字起こし済み音声を優先して削除
- Jetpack Compose + Material 3によるUI
- 「ホーム / 記録 / 設定」の3画面構成
- 録音履歴と文字起こし結果を録音時刻順で一覧表示
- 文字起こし本文・segment ID・ファイル名の検索
- 「文字起こし済み / 音声あり / 要確認」のフィルタ
- セグメント詳細で文字起こし全文表示・コピー
- 保持中M4Aの端末内再生
- 録音停止前の確認ダイアログ
- Dynamic Color、edge-to-edge、コンパクト画面のBottom Navigationと広幅画面のNavigation Rail

## 記録閲覧

「記録」画面は`files/metadata/segments.jsonl`、`files/transcripts/*.json`、現在残っている音声ファイルをsegmentIdで統合して表示します。表示順は文字起こし処理日時ではなく録音開始時刻を優先します。

文字起こし成功後もM4Aは即時削除せず、最近の録音を詳細画面から再生できるよう保持します。音声領域が600MBを超える、作業データ上限に近づく、または端末空き容量が不足した場合は、文字起こし結果が永続保存済みの古いM4Aから優先して削除します。それでも不足する場合に限り、最古の未文字起こし音声を削除してデータ欠損ログを残します。

録音プロセスがjournal末尾へ追記している最中でもUIが落ちないよう、不完全な最終JSONL行は読み飛ばします。履歴読み込みはUIスレッドや`:recorder`プロセスでは行いません。

## ローカルWhisperモデル

初回のみアプリ画面から`ggml-base.bin`をダウンロードします。取得元はwhisper.cppが案内しているHugging Faceの`ggerganov/whisper.cpp`モデル配布先です。ダウンロード後はSHA-1を検証します。

モデルは`noBackupFilesDir/whisper/`へ保存し、24hRecoderの「作業データ1GB」論理上限には含めません。ただし端末の実空き容量監視からは除外しません。

文字起こし時に録音音声をOpenAI等の外部文字起こしAPIへ送信しません。モデル取得完了後は文字起こし自体にネットワーク接続は不要です。

## ビルド

Android SDK API 36、JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.3.21、Compose BOM 2026.06.00、NDK 27.0.12077973、CMake 3.22.1を使用します。ネイティブビルド時に`whisper.cpp v1.9.1`のソースを取得してarm64-v8a向けにビルドします。

```bash
gradle :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## 現在のバージョン

- versionName: `0.4.0-debug`
- versionCode: `4`
- minSdk: `29`
- targetSdk: `36`
- ABI: `arm64-v8a`

## 注意

Pixel 10a実機での5分音声の推論時間、24時間録音との同時運用時の電池消費・温度・メモリ使用量は実測が必要です。ローカル文字起こしが失敗しても録音処理を停止させず、元音声は保持します。

履歴画面は現段階ではJSONLとファイルを読み合わせる実装です。長期間運用して履歴件数が大きくなった場合は、起動・検索性能を実測し、必要ならインデックス付きDBへ移行します。
