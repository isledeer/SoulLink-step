# 世界书对位：SoulLink WorldDoc ↔ RikkaHub Lorebook

> 基线：`rikkahub/rikkahub` tag 3.1.1，分支 `soullink`
> 本文只做对位与取舍，不含实现。结论在最后一节。

## 1. 上游能力（行号证据）

| 内容 | 位置 |
|---|---|
| 注入管线 | `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt` 全文 253 行 |
| `InjectionPosition`（5 值） | `data/model/Assistant.kt:133` BEFORE/AFTER_SYSTEM_PROMPT、TOP_OF_CHAT、BOTTOM_OF_CHAT、AT_DEPTH |
| `PromptInjection`（sealed） | `Assistant.kt:157`；`ModeInjection:172`、`RegexInjection:188` |
| `Lorebook` | `Assistant.kt:209`：`id/name/description/enabled/entries: List<RegexInjection>` |
| 触发判定 | `isTriggered():223`（enabled → constantActive → keywords；useRegex/caseSensitive） |
| 扫描上下文 | `extractContextForMatching():252`（`takeLast(scanDepth)` 拼接 `toText()`） |
| 存储形态 | `data/datastore/SettingsFields.kt:166-167` —— `json(LOREBOOKS, Settings::lorebooks)`，即 **DataStore 单个 key 里的 JSON 字符串**；`Settings.kt:77` |
| UI | `ui/pages/extensions/PromptPage.kt:586` `LorebookTab`；`PromptVM.kt` 仅 25 行，只是 `settingsStore.update`，无独立仓储 |
| 绑定关系 | `Assistant.lorebookIds: Set<Uuid>`（`Assistant.kt:50`）+ `Conversation.lorebookIds`（会话级覆盖） |
| 单测 | `PromptInjectionTransformerTest.kt` 38.7 KB / **37 个用例** |

**单测覆盖的边界**：5 种 position 各自行为、无 system 消息时新建、priority 降序、同 depth 合并、depth 大于消息数时插到最前、
lorebook 关键词命中/未命中/大小写/regex/constantActive、scanDepth 逐条独立、禁用世界书不触发、
mode 与 lorebook 同时生效、`findSafeInsertIndex` 不插在 `USER → ASSISTANT(含 tool)` 之间（含 BOTTOM/AT_DEPTH 两个专项）。

`applyInjections(messages, byPosition)` 的核心语义：
1. BEFORE/AFTER_SYSTEM_PROMPT **改文本**（合并进已有 system 消息），不加消息；
2. TOP_OF_CHAT 插在**第一条 USER 之前**；BOTTOM_OF_CHAT 插在**最后一条消息之前**；
3. AT_DEPTH 按 `size - depth` 定位，**同 depth 合并**，depth 从大到小处理；
4. 所有插入都过 `findSafeInsertIndex`（避免插进 USER→带工具 ASSISTANT 之间）；
5. 同 position 同 role 的注入**合并成一条** synthetic 消息（`\n` 连接）。

## 2. WorldDoc ↔ 上游对应物

| SoulLink `WorldDoc` 字段 | 上游对应物 | 结论 |
|---|---|---|
| `title` | `Lorebook.name` | 复用概念，存 `worldbooks.title` |
| `premise` / `setting` / `tone` | 无（应放 system prompt 固定段） | **自建**，走「世界规则段」 |
| `rules: List<String>` | 无 | 自建（同上） |
| `openingScene` / `openingSituation` | 无 | 自建（开场注入，一次性） |
| `npcs: List<WorldNpc>`（role/brief/initialRelation） | 无 | 自建；`initialRelation` 进 `initialStateJson`，`brief` 可编译成条目 |
| `items: List<WorldItem>` | 无 | 自建；`type/brief` 编译成条目，初始持有进 `initialStateJson` |
| 模板（`WorldbookTemplates`） | 无 | 自建 |

> 一句话：**只有"条目层"与上游 Lorebook 同构；"世界规则层"上游没有对应物**，必须自建。

## 3. `world_entries` ↔ `RegexInjection` 逐列

| world_entries 列 | RegexInjection 字段 | 差异 |
|---|---|---|
| `id` | `id: Uuid` | SoulLink 是 String，映射时生成/使用 UUID |
| `worldbookId` | （无，靠 `Lorebook.entries` 归属） | SoulLink 显式外键；映射时按 bookId 分组 |
| `keysJson` | `keywords: List<String>` | **缺** `useRegex` / `caseSensitive` → 只能做子串匹配 |
| `content` | `content` | 一致 |
| `alwaysOn` | `constantActive` | 一致 |
| `priority` | `priority` | 一致（上游按 priority **降序**） |
| — | `enabled` | **SoulLink 缺**（无单条开关，只有整本 `enabled`） |
| — | `scanDepth: Int = 4` | **SoulLink 缺**（`activate()` 用整段 `contextText`，无深度概念） |
| — | `position: InjectionPosition` | **SoulLink 缺**（硬编码"规则段 + 条目段"进 system） |
| — | `role: MessageRole` | **SoulLink 缺**（固定 USER） |
| — | `injectDepth` | **SoulLink 缺**（仅 AT_DEPTH 用） |
| — | `name` | SoulLink 用 `id` 当标识，无显示名 |
| `(无预算列)` | — | SoulLink 有 `budgetChars=1400` 的**字符预算**，上游无预算机制（靠 depth/priority） |

结论：字段是**子集**——上游多 6 个语义字段，SoulLink 多一个"预算"概念。

## 4. 两个方案

### 甲：编译成 `PromptInjection`，复用 `applyInjections`

`WorldbookInjectionTransformer` 把当前存档的 `world_entries` 编译成 `RegexInjection`，
与上游 `collectInjections()` 的结果**合并成同一个 `byPosition`**，再调用一次 `applyInjections()`。

- 代价：需要一层 entry→RegexInjection 映射（position/role/scanDepth 要定默认值：AFTER_SYSTEM_PROMPT / USER / 4）；
  需要让"世界规则段"（premise/rules/tone）走 AFTER_SYSTEM_PROMPT 的注入项；
  上游 `transformMessages` 与 `applyInjections` 都是 `internal`，同模块可直接调。
- 收益：**零重复实现**——关键词/大小写/scanDepth/priority/合并/safe insert 全部复用，
  37 个上游单测顺带保护我们；上游以后修 safe-insert 的坑我们免费拿。
- 风险：依赖上游 `internal` API 与执行顺序，merge upstream 时要跟着改（低，函数签名稳定）。

### 乙：`world_entries` 独立第二条注入链

自己实现匹配 + 自己算插入位置。

- 代价：匹配/预算/合并/safe-insert 全部重写（约 200 行 + 测试）；
  两套注入同时启用时，**顺序与深度会互相污染**（见下）。
- 收益：完全掌控顺序；不受上游 internal API 变化影响。

### 两条链同时启用时，AT_DEPTH / BOTTOM_OF_CHAT 如何互相作用

`inputTransformers` 是**有序列表**（ChatRequestFactory 里 TimeReminder → PromptInjection → … → WorkspaceReminder），
后跑的 transformer 看到的是**前一条链插完消息之后**的列表：

| 组合 | 实际结果 |
|---|---|
| A 先插 BOTTOM，B 后插 BOTTOM | B 的 `size-1` 已经指向 A 的那条消息 → **B 落在 A 之前**，两条都不在"最后一条用户消息之前"，语境位置整体前移 1 条 |
| A 先插 AT_DEPTH=4，B 再插 AT_DEPTH=4 | B 的 `size-4` 把 A 插入的 1 条算进历史 → B 比 A **再往前 1 条**；两者本应同深度，实际错位；且同 depth 合并只发生在**单次** `applyInjections` 内，跨链不会合并，模型会看到两条相邻的同 role 消息 |
| A 插 TOP_OF_CHAT，B 再插 TOP | B 的"第一条 USER"变成 A 的 synthetic USER → B 落在 A **之前**，顺序反转 |
| 任一链触发 `findSafeInsertIndex` 回退 | 回退会跨过另一条链刚插入的消息，进一步打乱顺序，且不可预测 |

**根因**：`applyInjections` 的定位是**相对当前列表长度**的，不是相对"原始对话"的。两条链各自计算，
等于对同一份历史做了两次相对定位，误差随插入条数线性累积。

**即使都用甲**，只要分成两次 `applyInjections` 调用，上面三条依然成立。所以甲的落地必须是
**单次调用、合并 byPosition**，不是"再加一个 transformer 各调一次"。

## 5. 世界书归属：每存档私有 还是 全局可复用？

**全局可复用（应用级资产），但"绑定"是存档级。**

- 内容留在 `worldbooks` / `world_entries` 表（v33 已建），**不并入 `Settings.lorebooks`**：
  ① `docJson` + 条目可能很大，而 AppSettings 是每次改动整份重写的 DataStore 单 key（`SettingsFields.kt:167`），
  大数据放进去会让每次设置变更都序列化整本世界书；
  ② 需要 `game_sessions.worldbookId` 外键语义与按 id 查询，DataStore 里只能全量扫描；
  ③ 将来要导出/导入/分享世界书（"卡带"），独立表天然可打包。
- "私有"只体现在**存档引用**：`game_sessions.worldbookId` 指向某本；同一本世界书可被多个存档引用。
  这与上游 `Assistant.lorebookIds` 的"资产全局、绑定局部"模型一致。

## 6. 推荐

**走甲（编译成 PromptInjection，且合并为单次 `applyInjections`）。**

一句话理由：条目层与上游 `RegexInjection` 天然同构，复用它能白拿 37 个单测和 safe-insert 语义，
而乙方案唯一的收益（顺序可控）恰好被"单次 applyInjections"以更低成本拿到。

> M2 落地时唯一需要碰的上游文件是 `ChatRequestFactory` 的 `inputTransformers` 列表：
> 把 `PromptInjectionTransformer` 换成 `SoulLinkInjectionTransformer`（无存档时直接委托回原实现）。
> 这一行不在本步改动范围内，等确认后再动。
