# C-2 设计：从文档创建世界书（AI 解析）

> 状态：**待确认**，本文不含实现代码。
> 依赖已核实：`DocxParser.parse(file: File): String`、`PdfParser.parserPdf(file: File): String`
> （`document` 模块，纯 Kotlin）；`ModelSelector(modelId, providers, type, onSelect)` / `ModelListSheet`
> （`ui/components/ai/ModelList.kt`）；`WorldbookTemplates.fillPrompt(template, premise)`（`:game`）。

---

## 1. 入口

列表页 `+` 底部弹窗第三项（当前只有两项）：

```
从文档创建（md / txt / docx / pdf）
需要先配置服务商
```

副标题常显，不做灰化——**没配服务商时点了也不会崩**，只是弹提示（见 §2）。

## 2. 前置检查与选择流程

```
点「从文档创建」
  ├─ settings.providers 里没有任何「带 CHAT 能力的模型」→ AlertDialog
  │    「此功能需要 AI 解析文档，请先配置服务商」
  │    按钮：[去配置] → navController.navigate(Screen.SettingProvider)   [取消]
  └─ 有 → 打开 ModalBottomSheet（三步同屏，不分页）：
       ① 服务商 + 模型：直接复用 ModelSelector(modelId, providers, ModelType.CHAT)
       ② 选文件：OpenDocument(*/*)，读文件头判断类型（复用 C-1.5 的 sniffFileKind）
       ③ 摘要行：字符数 N · 分 M 块 · 约 K 次请求
       底部按钮：[开始解析]（未选模型或未选文件时 disabled）
```

- 「带 CHAT 能力的模型」判定沿用上游：`ProviderSetting.models.any { ModelType.CHAT in it.type }`。
- 选中的模型只用于这一次解析，**不写回 settings**（不污染用户默认模型）。

## 3. 文档抽取

| 类型 | 做法 |
|---|---|
| `.txt` / `.md` | `contentResolver.openInputStream(uri).bufferedReader().readText()` |
| `.docx`（ZIP 魔数） | 先落到 `cacheDir` 临时文件 → `DocxParser.parse(file)` |
| `.pdf`（`%PDF`） | 同上 → `PdfParser.parserPdf(file)` |
| 其它 ZIP（xlsx/pptx/epub） | 拒绝：「暂不支持该格式，请导出为 md/txt/docx/pdf」 |

> `sniffFileKind` 现在只有 PNG/ZIP/TEXT；C-2 需要补 `PDF`（魔数 `25 50 44 46`）与
> `DOCX` 的区分（ZIP 后读 `[Content_Types].xml` 或直接尝试 `DocxParser.parse`，失败即拒绝）。
> 这是 `:game` 的纯函数扩展，不碰已有四个解析器签名。

临时文件在 `finally` 里删除；解析中断不留垃圾。

## 4. 分块（本卡的核心决策）

**阈值**：正文 **≤ 6000 字符** 不分块；超过才切。

**切法（三级，逐级退化）**：

1. **按结构**：先按空行分段；识别标题行（`^#{1,6}\s`、`^第[一二三四五六七八九十百零〇\d]+[章节回卷]`、
   `^Chapter\s+\d+`）作为强边界。
2. **按句子打包**：结构切完若单块 > 4000 字符，用句末标点（`。！？；\n`）贪心装到 ≤ 4000。
3. **重叠**：相邻块之间重叠 **200 字符**（上一块尾部），避免跨块的情节被切断。

单块硬上限 4000 字符、下限不设（很短的最后一块照发）。

**一段式 / 两段式**：

| 情况 | 请求数 | 说明 |
|---|---|---|
| 正文 ≤ 6000 字符 | **1 次** | 单次请求同时返回全局字段 + 条目（一个 JSON 两个 key） |
| 正文 > 6000 字符 | **N + 1 次** | 先跑一次「全局段」，再逐块跑「条目段」 |

- **全局段（第 1 次）**：输入 = 标题结构 + 每块首 200 字拼成的"目录摘要"（≤ 6000 字符），
  输出 `doc`（premise / setting / rules / tone / opening）。**不输出条目。**
- **条目段（每块 1 次）**：输入 = 该块全文（含 200 字重叠），输出 `entries[]`。
- **合并**：`doc` 取全局段结果；`entries` 逐块收集后按 `content` 前 60 字符去重（跨块重叠会产生重复），
  再走 C-1.5 的 `normalizeImported`（500 条 / 8000 字护栏）。

> 为什么不一次把所有块塞进去：单次上下文有限，且模型对超长输入的条目抽取召回明显下降。
> 为什么全局段不逐块跑：premise/setting 属于全书概念，逐块会得到互相矛盾的版本。

## 5. 输出契约

Prompt 与 schema 常量放 `:game`（`WorldbookAiPrompt.kt`），复用 `WorldbookTemplates.fillPrompt` 的模板骨架。

```json
{
  "doc": { "title": "", "premise": "", "setting": "", "tone": "", "rules": [""], "opening": "" },
  "entries": [
    { "keys": ["触发词"], "content": "条目正文", "alwaysOn": false, "priority": 0 }
  ]
}
```

**解析容错**（`parseAiWorldbook(raw: String): AiWorldbookResult`，纯函数）：
1. 去掉 ```json / ``` 围栏；
2. 取第一个 `{` 到最后一个 `}` 的子串；
3. `JSONObject` 解析成功即用；失败返回 `ParseError(message, raw)`。

**修复轮（只一次）**：把 `ParseError` + 模型原输出回灌一条 user 消息
（"上一次输出无法解析：<错误>。请只输出合法 JSON，不要任何解释。"），
再失败就中止并报错「AI 输出无法解析，已停止（未写入任何内容）」。**不猜、不兜底成空世界书。**

## 6. 安全（最高优先级）

- 解析结果只进 **内存草稿**（`WorldbookDraft(title, docJson, entries)`），展示在详情页的"草稿模式"，
  顶部一条横幅「AI 生成，尚未保存」。
- **详情页保存按钮才落库**，和手工新建走同一个 `GameRepository.saveWorldDoc` / `insertImported`。
- 全过程可取消：取消即丢弃草稿，`cacheDir` 临时文件在 `finally` 删除，**数据库零写入**。
- 解析过程不发任何请求给第三方服务（只发用户自己配置的 provider）。

## 7. 成本提示

摘要行只显示三个确定量：

```
12,480 字符 · 分 4 块 · 约 5 次请求
```

不显示 token 估算（不同 provider 分词差异太大，假数字比没有更糟）。

---

## 需要你拍板的三个点

1. **阈值 6000 / 块 4000 / 重叠 200** 是否接受？（都是常量，改起来是一行）
2. **全局段只喂"目录摘要"** 而不是全文——是否可接受？好处是省一次长请求，风险是 opening 可能抽不准。
3. **草稿模式**是否复用现有详情页（加一个 `isDraft` 状态），还是单独一个只读预览页？
   我倾向复用详情页，少一套 UI。
