# RikkaHub 对接点地图（M0 产出）

> **基线锁定：`rikkahub/rikkahub` tag `3.1.1`（910 个 `.kt`，AGPL-3.0）**
> **不跟 `nightly`，不跟默认分支 `feat/background-shell`。** 所有移植、diff、迁移都以此为唯一基准；
> 上游任何新提交只作为"以后要不要 merge"的参考，不进入 M1–M5 的实现依据。
>
> 本文只回答"代码写在哪、挂在哪"，不含任何功能代码。
> 结论先行：RikkaHub 的架构比预期更适合旁挂——**分支消息是"新增"而不是"覆盖"**，
> 而且系统提示词和工具 schema 都集中在 `ChatRequestFactory` 一个文件里，游戏层几乎不需要改它的核心。

---

## Q1. 会话与消息的实体定义？有没有多变体/重新生成/分支？覆盖还是新增？

| 项 | 位置 | 说明 |
|---|---|---|
| `Conversation` | `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt` | `id / assistantId / title / messageNodes: List<MessageNode> / chatSuggestions / isPinned / customSystemPrompt / modeInjectionIds / lorebookIds / config: ConversationConfig? / workspaceCwd / folderId` |
| `MessageNode` | 同文件 | `id / messages: List<UIMessage> / selectIndex / isFavorite`；`currentMessage` = `messages[selectIndex]` |
| `UIMessage` | `ai/src/main/java/me/rerere/ai/ui/Message.kt` | 平台无关消息，`parts: List<UIMessagePart>`（text / image / document / reasoning / tool…），流式 chunk 通过合并更新 |
| 持久化 | `data/db/entity/ConversationEntity.kt` | **整棵消息树序列化成 `nodes` 一列 JSON**（没有 message_node 表）。DB 版本 **32** |

**有原生分支**：

- `Conversation.updateCurrentMessages()` / `upsertMessage(nodeId, message)` 维持"节点 + 多候选"结构。
- 重新生成：`ChatService.regenerateAtMessage(conversationId, messageId)` → `runTurn(session, regenerateNodeId)`。
  新回复是**新的 `UIMessage.id`**，追加到同一 `MessageNode.messages`，`selectIndex` 指向它；旧回复留作分支。
- 切分支：`ChatService.selectMessageNode(conversationId, nodeId, selectIndex)`。
- 分叉会话：`forkConversationAtMessage(conversationId, messageId)`。

> **对账本的意义**：`game_events.messageId` 直接挂 `UIMessage.id` 就是天然正确的——
> 重新生成产生新 id，旧事件自动"出局"（不在当前路径上），不需要任何清理逻辑。
> 这是整个方案成立的关键前提，RikkaHub 满足。

### ⚠️ gameSessionId 存在哪：结论 = **v33 + Migration**（"零迁移"不成立）

**依据（读过的文件 + 看到的结构）：**

1. `data/db/entity/ConversationEntity.kt` —— 列是**逐字段显式声明**的：
   `id / assistant_id / title / nodes / create_at / update_at / suggestions / is_pinned /
   custom_system_prompt / mode_injection_ids / lorebook_ids / workspace_cwd / folder_id / config`。
   **没有 `gameSessionId` 对应的列**。
2. `data/repository/ConversationRepository.kt` → `conversationToConversationEntity()`（L380）：
   逐字段映射，其中写死 `nodes = "[]"`，注释是"**nodes 现在存储在单独的表中**"。
3. 同文件 `loadMessageNodes()/saveMessageNodes()`（L495–548）：
   消息树走 `message_node` 表（一行一个节点，`messages` 列是 JSON），
   `ConversationEntity.nodes` 已是**遗留空列**。
4. 反向 `conversationEntityToConversation()`（L405+）同样是逐列读取。

**结论**：3.1.1 里 `Conversation` **不存在整对象序列化落库的路径**。
"给可序列化模型加个字段就能零迁移"的前提在这里不成立——加字段只会存在于内存，
重启即丢。

唯一能"零迁移"的歪路是把 `gameSessionId` 塞进 `config`（`ConversationConfig` 的 JSON 列）：
但 `config` 在会话**首次落库前为 null**（由 `ConversationStore.prepareWrite()` 里的 `bindConfig()` 才写），
语义上也不属于"会话配置快照"，而且要改上游模型——**否决**。

**因此**：`version 32 → 33`，新增可空列 `game_session_id TEXT DEFAULT NULL`，
写显式 `Migration(32, 33)`，并在 `app/schemas/…/33.json` 提交新 schema。

---

## Q2. 消息在哪个 VM/Repository 写入？流式结束后在哪"定稿"？

数据流是**单向**的，VM 不写库：

```
ChatPage → ChatVM（纯转发） → ChatService（唯一写入口） → ConversationSession（内存唯一真源） → ConversationRepository → Room
```

| 阶段 | 位置 | 代码 |
|---|---|---|
| 发送 | `ChatService.sendMessage()` (L379) | 入队 → `runTurn()` |
| 流式增量 | `ChatService.runTurn()` (L~633) | `session.update { it.upsertMessage(replyNodeId, event.message) }` |
| **定稿** | `ChatService.runTurn()` 的 `finally` | `session.finishGeneration()` |
| 落库 | `ConversationSession.finishGeneration()` (L~250) | `finishReasoning()` 后 `flush()` |
| 写库 | `ChatService.conversationStore.write()` (L~125) | `conversationRepo.insertConversation / updateConversation` |

`ConversationSession` 的注释写得很明确：**加载之后它就是唯一权威**，所有修改走 `session.update {}` + `flush()`，
不接受整份快照回写。所以游戏层**绝不能**直接 `updateConversation(整份 Conversation)`。

### 三个可用的"定稿钩子"（按侵入度排序）

| # | 钩子 | 侵入度 | 说明 |
|---|---|---|---|
| A | `ChatService.runTurn()` 的 `finally` 之后 | **1 处、约 5 行** | 此时 `session.state.value.currentMessages` 已定稿，能拿到 `replyNodeId` / 最后一条助手消息 id / `finishReason`。用 `conversation.gameSessionId != null` 门控 + try/catch |
| B | `OutputMessageTransformer.onGenerationFinish()` | **0 处改动业务代码**（只在 `ChatRequestFactory.outputTransformers` 列表里加一项） | 但要写个 transformer 类。注意它是 **per-step** 调用（`AgentHooks.finishStep`），多步工具调用会触发多次，需要幂等 |
| C | `ChatService.generationDoneFlow: SharedFlow<Uuid>` | **0 侵入**，外部订阅 | 只告诉"哪条会话这一轮结束了"，没有 messageId，需要自己 diff 会话状态；适合做兜底/补偿，不适合做主链路 |

> 建议：**主链路用 A，兜底用 C**。B 只适合做"对输出文本做变换"这类事。

---

## Q3. system prompt 在哪拼？读了 Assistant 哪些字段？工具 schema 在哪注入？

全部集中在 **`app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt`**（302 行）。

```
createContext(settings, conversation)      // 解析 assistant / model / provider / memories / tools
   └─ buildStep(turn, messages)            // 拼 system + 裁上下文 + 输入转换
        ├─ effectiveSystemPrompt = conversation.customSystemPrompt (若 allowConversationSystemPrompt)
        │                          ?: assistant.systemPrompt
        ├─ + buildMemoryPrompt(memories)   （assistant.enableMemory）
        ├─ + tools.forEach { tool.systemPrompt(model, messages) }   ← ★ 工具自带的提示词
        ├─ messages.limitContext(assistant.contextMessageLimit)
        └─ .transforms(inputTransformers…)  // TimeReminder / PromptInjection / Placeholder /
                                            // DocumentAsPrompt / Ocr / Template / WorkspaceReminder
createAgent(turn, history, status) → (AgentRequest, AgentHooks)
```

**被读取的 `Assistant` 字段**（`data/model/Assistant.kt`）：
`systemPrompt、allowConversationSystemPrompt、contextMessageLimit、contextMessageSize、temperature、topP、
maxTokens、reasoningLevel、streamOutput、enableMemory、localTools、mcpServers、customHeaders、customBodies、
regexes、modeInjectionIds、lorebookIds、workspaceId`。

**工具注入**：`ChatToolFactory.createTools(settings, assistant, model, workspaceCwd)`（`data/ai/tools/ChatToolFactory.kt`）
返回 `List<Tool>`，同一份列表既进 `AgentRequest.tools`，也进 `TextGenerationParams.tools`。

> **游戏层的两个理想注入点**：
> 1. **注册一个 `GameStateTool`**：`Tool.systemPrompt()` 返回"世界规则 + 状态快照 + 输出契约"，
>    `execute()` 处理 `update_game_state` 调用。**完全不用改 ChatRequestFactory**——只需要让
>    `ChatToolFactory` 在 `conversation.gameSessionId != null` 时把这个工具加进去（一处 if）。
> 2. 兜底 `<game>` 尾块：用 `InputMessageTransformer` 在最后一条 user 消息尾部追加指令，
>    或用 `PromptInjectionTransformer` 的机制。注意总纲里的历史教训（会被角色卡指令压过）——
>    所以放 **user 消息尾部** 比放 system 更可靠。

---

## Q4. 主聊天页叫什么？导航图在哪？会话列表怎么渲染？

| 项 | 位置 |
|---|---|
| 主聊天页 | `ui/pages/chat/ChatPage.kt` → `@Composable fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid?)`；内部 `ChatPageContent()`，消息列表调 `ChatList(...)` |
| 消息列表 | `ui/pages/chat/ChatList.kt` |
| 抽屉/会话列表 | `ui/pages/chat/ChatDrawer.kt`、`ConversationList.kt` |
| **导航图** | **`RouteActivity.kt`**：用 **Navigation 3**（`androidx.navigation3.*`，`NavDisplay` + `entryProvider`），不是 navigation-compose |
| 路由定义 | 同文件末尾 `sealed interface Screen : NavKey`（约 90 个 `@Serializable` data object/class） |
| 路由注册 | 同文件 `entryProvider { entry<Screen.X> { ... } }`（约 L354–600） |

新增一条顶级路由 = **改 3 处**：`Screen` 加 `data object WorldBooks`；`entryProvider` 加 `entry<Screen.WorldBooks>`；
在 `ChatDrawer`（抽屉）或 `SettingPage` 里加一个跳转项。

---

## Q5. 设置根叫什么？新增二级设置页要改哪几处？

| 项 | 位置 |
|---|---|
| 设置总览 | `ui/pages/setting/SettingPage.kt`，`@Composable fun SettingPage(vm: SettingVM = koinViewModel())` |
| 组件规范 | `ui/components/ui/CardGroup.kt` 的 `CardGroup` / `CardGroupItem`（**AGENTS.md 强制**：表单页用 CardGroup，不用分隔线卡片） |
| 二级页入口 | `onClick = { navController.navigate(Screen.SettingX) }` |
| 扩展类功能聚合页 | `ui/pages/extensions/ExtensionsPage.kt`（提示词 / 快捷短语 / 技能 / 工作区都挂这儿） |

**新增二级设置页 = 3 处**：`Screen` 加路由 → `RouteActivity` 的 `entryProvider` 注册 → `SettingPage` 或
`ExtensionsPage` 加入口。世界书建议走 **ExtensionsPage**（它已经是"功能扩展"的聚合地）。

---

## Q6. 依赖注入用 Koin 还是 Hilt？新的 Repository/ViewModel 怎么注册？

**Koin**（`org.koin`），模块集中在 `app/src/main/java/me/rerere/rikkahub/di/`：

| 文件 | 注册内容 |
|---|---|
| `AppModule.kt` | `Json`、`ProviderManager`、`AppEventBus`、**`ChatService`**、`FilesManager`、`UpdateChecker`… |
| `DataSourceModule.kt` | Room `AppDatabase`、各 DAO、`SettingsStore`、DataStore |
| `RepositoryModule.kt` | `single { ConversationRepository(...) }` 等全部 Repository |
| `ViewModelModule.kt` | `viewModel<ChatVM> { params -> … }` / `viewModelOf(::SettingVM)` |

新增：
```kotlin
// RepositoryModule
single { GameRepository(get(), get()) }          // 构造参数用 get() 注入
// ViewModelModule
viewModelOf(::WorldbookVM)                        // 无参构造
viewModel<GameVM> { params -> GameVM(id = params.get(), repo = get()) }
```
页面里 `val vm: GameVM = koinViewModel()`（或 `koinViewModel { parametersOf(id) }`）。

---

## 额外发现（会直接影响施工）

1. **DB 版本 32**。`ConversationEntity` 加 `gameSessionId` 需要 `version = 33` +
   `AutoMigration(from = 32, to = 33)`（新增可空列，Room 能自动生成）。新增 4 张表同理。
2. **消息树是 JSON 一列**，不是关系表。所以"事件挂 messageId"在 DB 层没有外键保护，
   删除会话时要靠 `onDelete` 级联或派生时过滤。
3. **上游没有群聊**（那是另一个分支的自研功能），M1–M5 不需要考虑。
4. **`ConversationConfig` 快照**：会话开始后模型/搜索等配置固定到会话上，
   游戏层读配置要学它用 `Settings.getAssistantOf(conversation)`，不要直接读 assistant。
5. **`AGENTS.md` 的硬约束**：ktlint/`spotlessApply`、MD3 Expressive、`./gradlew test`、
   模块化（app / ai / ui / search / speech / web / workspace / terminal…），以及"优先 `CardGroup`"。
   我们的代码要放在 `app` 模块（依赖 UI/DB），游戏逻辑可以单开一个 `game` 子包，暂不建新 Gradle 模块。
6. **库结构**里有 `workspace` 和 `terminal` 模块（proot + SSH），M4 的"镜头切换"如果想做
   "NPC 有自己的工作区"可以直接复用，不用自己造。

---

## 发布门禁：迁移策略（M1 就要生效）

**硬规则：禁止 `fallbackToDestructiveMigration()`。**

事实核对：当前 `AppDatabaseFactory.create()`（`data/db/AppDatabaseFactory.kt`）**没有**调用它，
`ksp { arg("room.schemaLocation", "$projectDir/schemas") }` 已开启 schema 导出，
`app/schemas/…/32.json` 是最新一份。上游自己就是"每次改 schema 必写 Migration"的纪律。

我们在此基础上追加三条门禁：

1. **任何 schema 变更**必须同时提交：
   - `AppDatabase.version + 1`
   - 显式 `Migration(old, new)`（不用 AutoMigration 兜底，便于 review）
   - `AppDatabaseFactory.addMigrations(...)` 里注册
   - `app/schemas/…/<new>.json` 新 schema 文件
   - `app/src/androidTest/.../migrations/Migration_<old>_<new>_Test.kt`（照抄 `Migration_30_31_Test.kt` 的写法）
2. **CI 门禁**：`./gradlew test` + `assembleGithubDebug` 必须绿；schema 文件缺失视为失败。
3. **M1 唯一一次迁移**：`Migration_32_33` 只做一件事——
   `ALTER TABLE conversationentity ADD COLUMN game_session_id TEXT`（可空，无默认值），
   以及建 4 张新表。**不重建表、不改既有列**。

---

## game 模块结构（纯 JVM，不依赖 Android）

```
game/                                  # Kotlin/JVM library
├── build.gradle.kts                   # kotlin("jvm")，依赖 kotlinx-serialization + 测试
└── src
    ├── main/kotlin/com/isledeer/soullink/game
    │   ├── model/GameState.kt          # GameState / InventoryItem / ItemChange（纯数据，无 org.json）
    │   ├── model/GameAction.kt         # GameAction + 解析（用 kotlinx.serialization，不依赖 android.util.Json）
    │   ├── ledger/GameEvent.kt         # 事件模型（messageId 用 String）
    │   ├── ledger/GameStateOps.kt      # fold 纯函数：apply(state, event) + derive(initial, events)
    │   ├── worldbook/WorldBook.kt      # WorldBook / WorldEntry / 激活与预算裁剪
    │   └── worldbook/WorldbookTemplates.kt
    └── test/kotlin/com/isledeer/soullink/game
        ├── LedgerFoldTest.kt           # 14 个（移植）
        └── WorldbookTest.kt            # 7 个（移植）
```

**依赖方向**：`app → game`（单向）。`game` 不 import 任何 `android.*`、不 import `me.rerere.*`。
好处：`./gradlew :game:test` 秒级跑完；账本逻辑与 UI/DB 解耦；将来 B/C 模式直接复用。

**id 类型**：账本层一律 `String`（`messageId: String`、`conversationId: String`、`sessionId: String`）。
RikkaHub 的 `Uuid` 只在边界处 `toString()` 一次，**不在账本层引入 `Uuid`**。

---

## M1 落点（建议，待你确认）

| M1 任务 | 落点 | 改动量 |
|---|---|---|
| 4 张表 + DAO + Repository | 新建 `data/db/entity/GameSessionEntity.kt`、`GameEventEntity.kt`、`WorldBookEntity.kt`、`WorldEntryEntity.kt` + DAO + `GameRepository`；`AppDatabase` 注册 + `version 33` + `Migration_32_33` | 新文件为主 + 2 处注册 |
| 会话挂存档 | `ConversationEntity` 加 `game_session_id`；`Conversation` 加 `gameSessionId: String? = null`；`ConversationRepository` 映射（L380/L405 各 1 行） | 3 个文件，共 ~4 行 |
| 定稿回调 | `ChatService.runTurn()` 的 `finally` 里**只加一行**：`GameLedgerHooks.onTurnFinished(session, replyNodeId, finishReason)` | **1 行** |
| 单测 | `game/src/test/...` 移植 14 账本 + 7 世界书；`app` 侧加 `Migration_32_33_Test` | 新文件 |

### `GameLedgerHooks.onTurnFinished` 的契约（内部自己兜住一切）

```kotlin
object GameLedgerHooks {
    suspend fun onTurnFinished(session: ConversationSession, replyNodeId: Uuid, finishReason: AgentFinishReason?) {
        val conversation = runCatching { session.state.value }.getOrNull() ?: return
        val gameSessionId = conversation.gameSessionId ?: return      // 门控：普通聊天零开销
        if (finishReason == null || finishReason is AgentFinishReason.AwaitingApproval) return
        try {
            // 取最后一条助手消息 → 解析 <game> 尾块 / 取工具暂存 → ledger.replaceActions(...)
        } catch (t: Throwable) {
            Log.e("GameLedgerHooks", "ledger write failed, ignored", t) // 绝不上抛
        }
    }
}
```

- 取消 / 报错 / 等审批 **一律不写**；只有"正常完成"才落账。
- `replyNodeId` 是 `Uuid`，进账本前 `toString()`。
- 整段 `try/catch`，任何异常只写日志。

### 改动分类（M1）

| 纯新增（零侵入） | 动了上游文件 |
|---|---|
| `game/` 整个模块 | `data/db/AppDatabase.kt`：实体列表 + `version = 33`（1 行） |
| 4 个 Entity + 4 个 DAO + `GameRepository` | `data/db/AppDatabaseFactory.kt`：`addMigrations(Migration_32_33)`（1 行） |
| `GameLedgerHooks.kt` | `data/db/entity/ConversationEntity.kt`：+1 列（1 行） |
| `game/.../*Test.kt`（21 个） | `data/model/Conversation.kt`：+1 字段（1 行） |
| `app/src/androidTest/...Migration_32_33_Test.kt` | `data/repository/ConversationRepository.kt`：映射 +2 行 |
| `app/schemas/…/33.json`（生成物） | `service/ChatService.kt`：+1 行调用（`finally` 内） |

**上游文件合计改动 ≈ 7 行**，且集中在 6 个文件的固定位置，merge upstream 时最多冲突这 7 行。

**唯一的不确定点**：`runTurn` 的 `finally` 在**取消**时也执行（`NonCancellable`），
所以回调里必须靠 `finishReason`/`TurnResult` 判断"正常完成"，否则会写入半截状态。
这一点我在 M1 实现时会单独处理。
