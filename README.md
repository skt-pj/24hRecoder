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
- Silero VAD v6.2.0で音声区間を抽出し、非発話区間のWhisperハルシネーションを抑制
- 5分M4AをAndroid MediaCodecでPCMへ復号し、端末内で推論
- WorkManagerで文字起こしを録音処理から分離し、低バッテリー時は延期
- ローカルWhisper推論は同時実行1件に制限し、待機中セグメントを「処理中」と誤表示しない
- debug APKでもwhisper.cpp/ggmlネイティブコードは`-O3`で最適化して推論する
- PCMのRMS、peak、clipping率をログへ残し、録音品質と認識品質を切り分け可能
- 文字起こし結果はtemp書き込み・fsync・renameで永続保存
- 旧エンジンの文字起こしは元M4Aが残る場合にVAD版で再処理し、成功時だけ結果を置換
- 処理済みの記録でも、元M4Aが残っていれば記録詳細から「この音声を再文字起こし」で強制再実行可能
- 手動再文字起こし中も既存の文字起こし結果を保持し、新結果が正常保存された時だけ置換
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

## 文字起こし品質

0.4.3-debug以降では、24時間録音で頻出する無音・生活音・長い非発話区間をそのままWhisperへ渡さず、Silero VAD v6.2.0が音声と判定した区間だけを推論対象にします。加えて`no_speech_thold`、`suppress_blank`、`suppress_nst`等のデコード制御を使用します。

VADモデルは`ggml-silero-v6.2.0.bin`を使用し、取得時に885,098 bytesとSHA-256 `2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987`を検証します。Whisper baseと同じく`noBackupFilesDir/whisper/`へ保存し、作業データ1GBの論理上限には含めません。

旧バージョンのローカルエンジンで文字起こし済みでも元M4Aが残っているセグメントは、VADモデル準備後に再文字起こし対象になります。既存JSONは新しい文字起こしのtemp書き込み・fsync・renameが成功するまで保持し、失敗時に過去の結果を失わないようにします。元M4Aが既に容量管理で削除済みの場合は再文字起こしできないため、旧結果をそのまま保持します。

0.4.4-debugでは自動移行とは別に、現在のエンジンですでに処理済みの記録をユーザー操作で強制再実行できます。記録詳細の「この音声を再文字起こし」を押すと確認ダイアログを表示し、同じsegmentIdの保存済みM4Aを現在のWhisper + VAD設定で再処理します。Workerは手動再実行フラグがある場合、現在のエンジンで処理済みでもスキップしません。再処理に失敗しても既存の文字起こしJSONは削除せず、新しい結果の永続保存に成功した時だけ置換します。

文字起こし完了ログには`audioRms`、`audioPeak`、`clippedFraction`を追加しています。保持中M4Aを実際に再生した音とこれらの値を合わせて確認し、録音自体が小さい・潰れている問題とWhisperモデル側の認識問題を分けて判断します。

## 文字起こしキュー

WorkManagerは複数の`TranscriptionWorker`を同時に開始する場合がありますが、whisper.cppの実推論は1件ずつ実行します。0.4.1以前は各WorkerがWhisperの排他ロックを取得する前に`TRANSCRIBING`を書き込んでいたため、実際には順番待ちのセグメントまで全件「文字起こし中」と表示されていました。

0.4.2-debug以降はWorkerが排他ロック待ちの間は内部状態`QUEUED`とし、UIでは通常の「待機中」として表示します。ロックを取得した1件だけを`TRANSCRIBING`へ遷移させます。ログには`LOCAL_TRANSCRIPTION_QUEUED`、`LOCAL_TRANSCRIPTION_STARTED`、`queueWaitMs`、`decodeMs`、`inferenceMs`を記録します。

手動再文字起こしは通常の自動処理とは別のunique work名で登録しますが、実Whisper推論は同じ排他区間を使うため同時実行数は1件のままです。手動再実行は`MANUAL_RETRANSCRIPTION_ENQUEUED`、`MANUAL_RETRANSCRIPTION_QUEUED`、`MANUAL_RETRANSCRIPTION_STARTED`、`MANUAL_RETRANSCRIPTION_SAVED`等のイベントで追跡できます。

配布成果物はdebug APKですが、ローカルWhisperは性能が必要な実処理です。CMakeのDebug構成でもwhisper.cpp/ggml/JNIには`-O3`を追加し、デバッグ可能性を残したまま推論コードを最適化します。5分音声の実際の推論時間はPixel 10a実機で測定して継続可否を判断します。

## 記録閲覧

「記録」画面は`files/metadata/segments.jsonl`、`files/transcripts/*.json`、現在残っている音声ファイルをsegmentIdで統合して表示します。表示順は文字起こし処理日時ではなく録音開始時刻を優先します。

文字起こし成功後もM4Aは即時削除せず、最近の録音を詳細画面から再生できるよう保持します。音声領域が600MBを超える、作業データ上限に近づく、または端末空き容量が不足した場合は、文字起こし結果が永続保存済みの古いM4Aから優先して削除します。それでも不足する場合に限り、最古の未文字起こし音声を削除してデータ欠損ログを残します。

録音プロセスがjournal末尾へ追記している最中でもUIが落ちないよう、不完全な最終JSONL行は読み飛ばします。履歴読み込みはUIスレッドや`:recorder`プロセスでは行いません。

## ローカルWhisperモデル

Whisper ASRには`ggml-base.bin`、発話検出には`ggml-silero-v6.2.0.bin`を使用します。既にbaseがある端末を0.4.3以降へ更新した場合は不足しているVADモデルだけを取得します。取得後は各モデルのサイズとハッシュを検証します。

モデルは`noBackupFilesDir/whisper/`へ保存し、24hRecoderの「作業データ1GB」論理上限には含めません。ただし端末の実空き容量監視からは除外しません。

文字起こし時に録音音声をOpenAI等の外部文字起こしAPIへ送信しません。モデル取得完了後は文字起こし自体にネットワーク接続は不要です。

## APK署名・バージョン管理

debug APKのapplicationIdは`com.sktpj.recorder24h.debug`で固定します。`versionCode`はリリースごとに必ず単調増加させます。

0.4.0以前はGitHub Actionsの一時debug keystoreに依存していたため、CI runごとに署名鍵が変わり、Android上で上書き更新できませんでした。0.4.1-debug以降はGradleからdebug自動署名を外し、GitHub ActionsでAOSPの固定testkeyを使用して署名します。これにより0.4.1-debug以降のCI成果物同士は同じ証明書で更新できます。

このtestkeyは公開された開発・テスト用鍵であり、production releaseには絶対に使用しません。正式配布時は専用の非公開release鍵へ切り替えます。

既に0.4.0以前のランダムdebug鍵でインストール済みのAPKは、対応する秘密鍵が残っていないため0.4.1-debugへ直接更新できません。一度だけアンインストールして0.4.1-debug以降を新規インストールする必要があります。そのアンインストールではアプリ内部データが削除されるため、必要な録音・文字起こしデータがある場合は事前退避が必要です。

## ビルド

Android SDK API 36、JDK 17、Gradle 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.3.21、Compose BOM 2026.06.00、NDK 27.0.12077973、CMake 3.22.1を使用します。ネイティブビルド時に`whisper.cpp v1.9.1`のソースを取得してarm64-v8a向けにビルドします。

```bash
gradle :app:assembleDebug
```

ローカルの`assembleDebug`出力は署名前APKです。配布用debug APKはGitHub Actionsで固定testkeyを適用し、`app/build/outputs/apk/debug/app-debug.apk`としてアップロードします。

## 現在のバージョン

- versionName: `0.4.4-debug`
- versionCode: `8`
- minSdk: `29`
- targetSdk: `36`
- ABI: `arm64-v8a`

## 注意

Pixel 10a実機でのVAD検出品質、5分音声の推論時間、24時間録音との同時運用時の電池消費・温度・メモリ使用量は実測が必要です。ローカル文字起こしが失敗しても録音処理を停止させず、元音声と既存の確定済み文字起こしは可能な限り保持します。

VAD導入後も、実際のM4Aを再生して会話が明瞭に聞こえるのにbaseモデルの文字起こし精度が不足する場合は、より大きいモデルへの変更をPixel 10aの処理時間・熱・バックログと合わせて評価します。

履歴画面は現段階ではJSONLとファイルを読み合わせる実装です。長期間運用して履歴件数が大きくなった場合は、起動・検索性能を実測し、必要ならインデックス付きDBへ移行します。
