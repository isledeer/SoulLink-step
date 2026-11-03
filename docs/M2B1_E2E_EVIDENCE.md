# M2-B-1 端到端证据（真机模拟器 · logcat）

- 运行：[DB Migration Test #41579283487](https://github.com/isledeer/SoulLink-step/actions/runs/41579283487)（API 34 / x86_64 模拟器，BUILD SUCCESSFUL in 7m 59s）
- 用例：`me.rerere.rikkahub.data.game.WorldbookInjectionE2ETest.rulesEnterSystemPromptThroughRealTransformer`
- 链路：Room 建世界书 + 存档 → `GameRepository.loadedCompiledInjections` → `compileWorldbook`
  → `buildWorldbookLorebook` → `toRegexInjection` → 上游真实 `transformMessages`
- 取法：`adb logcat -d -s SoulLinkE2E:I`（塞在 android-emulator-runner 的 script 内，模拟器销毁前执行）

## 绑定状态（bound）

```
11-03 04:48:06.063  6182  6240 I SoulLinkE2E: bound system prompt: 基础人设
11-03 04:48:06.063  6182  6240 I SoulLinkE2E: - 测试规则：玩家的名字必须以「旅人」开头
```

实际拼出的 system message 文本：

```
基础人设
- 测试规则：玩家的名字必须以「旅人」开头
```

## 解绑状态（unbound）

```
11-03 04:48:06.064  6182  6240 I SoulLinkE2E: unbound system prompt: 基础人设
```

实际拼出的 system message 文本：

```
基础人设
```

规则消失，断言 `assertFalse(unbound.contains("测试规则"))` 通过。

## 说明

- 断言是内容匹配，不是"测试跑过就算"：绑定分支 `assertTrue(system.contains(rule))`，
  解绑分支 `assertFalse(...)`，任一不成立 CI 红。
- 这条覆盖 `createContext` 三行胶水之外的全部链路；`ChatRequestFactory` 本身没有进这条测试
  （它需要 Koin 全量依赖），但它的三个纯函数已被 `WorldbookInjectionTest` 覆盖。
- API Key 不涉及：测试全程不联网、不读 `Settings.providers`。
