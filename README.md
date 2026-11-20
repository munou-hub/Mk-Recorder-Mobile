# Mk Recorder Mobile (MRM)

Xperia 向けフィールドレコーダー。純 Java（外部ライブラリなし）、minSdk 26 / targetSdk 34。

## 機能
- 録音: WAV / FLAC 切替、44.1 / 48 / 96 kHz、16 / 24bit + 32bit float
- モノラル / ステレオ切替（ステレオ入力のモノラル化対応）
- リアルタイムレベルメーター: LUFS (Momentary, K-weighting) / RMS / PEAK 切替、L/R 2本、ピークホールド + クリップラッチ
- 入力ゲイン調整 (-48 〜 +24 dB)、入力ソース選択（UNPROCESSED 既定）
- プリロール（0 / 3 / 5 / 10 秒）、セーフティトラック（-12dB 複製を同時保存）
- 録音中マーカー、バックグラウンド録音（フォアグラウンドサービス）
- 試聴: 波形表示 + シーク、FFT (2048 / 4096、再生中のみ)、マーカージャンプ
- チェック済みファイルの連続再生、一斉リネーム（接頭辞+連番 / 日時+連番）、共有・削除
- エフェクト（再生・書き出し用、録音は原音）: 4バンド パラメトリック EQ + コンプレッサー（GR メーター付き）、再生中リアルタイム調整、FX 書き出し（_FX.wav）
- 起動時は「実際に音が来る設定」をプローブで確認してから採用（ソース自動フォールバック付き）

## 注意
- FLAC は Android のエンコーダ仕様により 16bit 固定
- 内蔵マイクや Android 側ドライバの上限により、実効レート/深度は端末依存（画面に実効値を表示）
- 保存先: アプリ専用領域 `Android/data/jp.mk.recorder/files/Music/MKRecorder`

## ビルド
- 通常: `gradle assembleDebug`
- 手動チェーン: `./build-manual.sh`（aapt2 → javac → d8 → zipalign → apksigner）
- リリースはタグ `v*` で GitHub Actions がビルドし、APK をリリースへ添付する

仕様の詳細は [SPEC.md](SPEC.md)。
