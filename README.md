# TransAssistant · 翻译助手

> Android 屏幕翻译助手：圈选屏幕任意区域，多引擎 OCR 识别文字，AI 翻译后以悬浮字幕形式展示。
>
> An Android screen translation assistant: capture any on-screen text, recognize it with multiple OCR engines, translate with AI, and view the results as floating subtitles.
>
> 本质上是为了打无汉化补丁的文字游戏而诞生的产物。作者打文字游戏不想开电脑，但安卓上没有LunaTranslator，所以自己搞一个。功能仅满足屏幕翻译这一基本需求。

## 功能特性

- **圈选识别**：悬浮球 + 选区工具，自由框选屏幕上的任意文字区域
- **多 OCR 引擎**：百度 / 阿里云 / PaddleOCR / GLM-OCR 云端引擎，以及基于 ML Kit 的**离线本地识别**（中文 / 英文 / 日文，无需网络）
- **AI 翻译**：兼容 OpenAI 接口，支持 ChatGPT、DeepSeek、Kimi、智谱、通义、文心、Gemini 及自定义代理，可自定义 API 基址、模型、Prompt、温度等参数，支持连接测试与模型列表获取
- **悬浮字幕**：识别结果以悬浮窗展示，支持「跟随选区 / 自由调节」两种模式；字幕风格可定制（描边、字色、胶囊按钮、框线）
- **OCR 缓存**：基于感知哈希的识别结果缓存，相同画面不重复调用 API
- **历史记录**：自动保存识别与翻译结果，一键复制
- **手柄按键映射**：支持手柄触发识别，方便游戏场景使用
- **主题换肤**：6 套主题配色 + 深色 / 浅色模式
- **日志中心**：内置错误日志查看，方便排查问题

## 使用流程

1. 进入「使用」页，点击**启动识别**
2. 按提示授予**悬浮窗**与**屏幕录制**权限
3. 应用退到后台，屏幕出现悬浮球，拖动圈选要翻译的区域
4. 识别结果（可含 AI 译文）以悬浮窗 / 字幕形式展示

## OCR 引擎

| 引擎 | 凭据 | 说明 |
| --- | --- | --- |
| 百度 OCR | API Key + Secret Key | 通用文字识别 | 目前有点问题无法使用
| 阿里云 OCR | AccessKey ID + AccessKey Secret | 通用文字识别 |
| Paddle OCR | AI Studio Token | 百度飞桨版面解析 |
| GLM-OCR | 智谱 API Key | 版面解析（layout_parsing） |
| 本地识别（离线） | 无需凭据 | ML Kit，支持中 / 英 / 日，完全离线 |

## AI 翻译引擎

翻译通过 OpenAI 兼容接口（`/chat/completions`）完成，内置以下服务商预设，也可填任意兼容地址：

ChatGPT (OpenAI) · DeepSeek · Moonshot (Kimi) · 智谱清言 (GLM) · 通义千问 (Qwen) · 文心一言 (Yiyan) · Gemini (Google) · 自定义代理

支持自定义模型标识、源 / 目标语言、温度（Temp）、系统角色与用户模板 Prompt。作者本人只测试过deepseek，其他理论上能用但不保证。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 悬浮窗（SYSTEM_ALERT_WINDOW） | 悬浮球与结果浮窗 |
| 屏幕录制（MediaProjection） | 截取屏幕内容用于 OCR |
| 前台服务（FOREGROUND_SERVICE） | 保持截屏服务运行 |
| 网络（INTERNET） | 调用云端 OCR / AI 接口 |
| 振动（VIBRATE） | 操作反馈 |

## 隐私

- 所有 API 密钥保存在本机（SharedPreferences），仅用于调用你自行配置的服务商
- 截图内容仅发送到你选择的 OCR / AI 服务商，本应用不向其他服务器上传数据
- 使用「本地识别」引擎时全程离线

