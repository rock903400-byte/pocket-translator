# 安全性政策（Security Policy）

## 支援版本

| 版本 | 支援狀態 |
|------|----------|
| main 最新版 | ✅ 提供安全性更新 |
| 歷史版本 | ❌ 不再維護，請升級到最新版 |

## 金鑰與隱私聲明

- Groq / Gemini 等 API 金鑰只存放在**本機 App 設定**，不會上傳到任何伺服器。
- 請勿把 `local.properties`、keystore 密碼或任何 API 金鑰 commit 到 repo（`.gitignore` 已排除）。
- 回報問題時請塗掉 Log 中的金鑰字串（`gsk_`、`AIza` 開頭）再貼上。

## 回報弱點

- 請來信 `rock90340@gmail.com`，主旨註明 `[Security] pocket-translator` 並附上重現步驟（含機型與 Android 版本）。
- 尚未修復的弱點請勿公開細節；收到回報後會評估並儘快處理。
