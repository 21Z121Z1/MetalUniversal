# CUTOUT shimmer 线交接 — 2026-07-27

> **状态**:这条线能跑,判据已演进到第三层并测出了定量规律;验证门的红**不是**我造成的(完成门硬编码 `!= 10` 而别的线把捕获数加到了 12+)。真正的卡点是一个证据缺口:所有数字都是静止相机 hold,**测不到 ghosting**——降 reactive 的收益已经量化,代价一次都没测过。另有一个**必须先处理的不一致**:用户游戏里正在跑的 jar 带着补丁里的新默认值,集成分支源码是旧值,两者对不上(见 §5)。

交接人所在会话的 cwd 误配成了 `spektrafilm-main`,因此整晚没收到协作协议通知,一直直接写集成 checkout。在途改动已按要求导出为补丁并把共享树恢复干净。**本文只写代码和 git 里读不出来的东西**。

---

## 1. cutout 判据演进到哪一步,为什么是这个形状

### 1.1 病因只有一个:`reactive` 抑制本身

reactive 越高 → 时域累积越少 → 而正是时域累积在重建抖动采样下的亚像素覆盖。所以给 alpha-test 内容写高 reactive 去"保护"它,**恰恰制造了它要防的抖动**。FSR2 文档明说接近 1.0 的值不会有好结果。

这个病因今晚在**三个不同的写入者**里各犯了一次,而且每修好一层,下一层才浮上来当主导——这是最重要的一条经验:**不要以为修完一处就完了,要去测"现在是谁在主导"**。

| 层 | 写入者 | 修之前占轮廓带 | 处理 |
|---|---|---|---|
| a | cutout dilation kernel 铺满整片树叶 = 1.0 | — | 边缘带 + 内部 0.0 |
| c | `metallum_motion_merge_v2` 的 `if (disocclusion > 0.5) reactive = 1.0` | 98%+ | 3×3 深度膨胀 + cap 0.85 → **降到 0.6%** |
| d | depth-edge 启发式饱和到 cap | **98.5%** | cap 0.5 → 0.25,边缘带 0.35 → 0.20 |

第 c 层的机制值得记住:重投影用单点最近邻 `uint2(previousPixel)`,亚像素抖动让这个探针在轮廓两侧逐帧来回跳;叶片深度(~0.01)对天空(0.00002)的差恒大于 `max(0.0025, |d|*0.01)` 阈值,于是**一个完全静止的轮廓被隔帧判成 disocclusion**,历史每隔一帧丢一次。

### 1.2 定量规律(这条是判据形状的依据)

写入者之间用 `max()` 合成,所以轮廓带实际拿到的是 `max(cutoutReactiveEdgeWeight, depthEdgeReactiveCap)`。扫描这个值:

| 轮廓带 reactive | skyEdge 均值 | p95 | 整体 cutout mask | skyInterior | control |
|---|---|---|---|---|---|
| 0.50 | 5.0099 | 17 | 2.5262 | 0.0985 | 0.1888 |
| 0.35 | 3.6094 | 12 | 1.9468 | 0.0622 | 0.1448 |
| 0.25 | 2.6998 | 9 | 1.4082 | 0.0618 | 0.1414 |
| 0.25(经由 edge 0.10) | 2.6744 | 9 | 1.2913 | 0.0616 | 0.1398 |

**闪烁在轮廓带上对 reactive 值是线性的**,斜率约 9.2/单位,外推到 0 落在 ≈0.4,正好是该场景 control 区水平(0.14–0.19)。所以判据形状的结论是:reactive 不是保护,它按比例就是闪烁本身。

选 0.20/0.25 而不是更低,是因为再往下只动了 1%(表中最后两行同值不同 edge 权重,说明 `max()` 把主导权交回了 edge band),同时保留两个写入者非零。

### 1.3 定位手法(建议沿用)

不要一个旋钮跑一次全量验证。`beginFlickerSeries` 现在会把最终 `reactiveTexture` 读回来,在 render 空间轮廓带上按值分 16 桶(`skyEdgeReactiveBuckets`)。每个写入者的值互不相同,**一次跑就能指认主导者**:

| 值 | 写入者 |
|---|---|
| 0.00 | 内部 / 无 — 正常累积 |
| ~0.35 | CUTOUT 边缘带 |
| ~0.50 | depth-edge cap |
| ~0.85 | disocclusion cap |
| ~0.90 | transparency |
| 1.00 | 全抑制 — **绝不应出现** |

---

## 2. 试过并否决的方案(最贵的信息,别重踩)

1. **原始的 coverage→dilate→reactive=1.0 「修复」** — 已证伪。它是病因不是解药。用户在 2026-07-26 明确反馈没有效果,由此才重新诊断。
2. **只改 cutout 侧、不动天空侧** — 无效。轮廓重建需要边界**两侧**都有可用历史;天空侧被 `reactive=1.0` + `disocclusion=1.0` 每帧清空,cutout 侧再怎么调都没用。
3. **把藤蔓当成独立问题** — 否决。用 JDK 25 的 `javap` 反编译 `DefaultTerrainRenderPasses.class` 确认 Sodium 0.9 只有 SOLID / CUTOUT(discard) / TRANSLUCENT 三个 pass,藤蔓已经在 CUTOUT 内,和树叶同一套策略。它更抖只是因为 1–2 px 宽的细条几乎 100% 是边缘带且背景是天空。
4. **怀疑 §14 的 sky far-plane motion 让天空自己变抖** — 否决,实测方向相反。隔离 A/B(两臂 mask 逐字节相同):开阔天空 0.1490 → 0.0829,两臂 p95 **都是 0**。天空渐变本身是稳的。
5. **把 depthEdgeReactiveCap 降到 edge 权重以下** — 无额外收益,`max()` 会把主导权交回 edge band。想继续降必须两个一起降。
6. **用 4 次全量客户端跑做因果扫描** — 低效(每次约 4 分钟)。改用 §1.3 的读回+分桶,一次跑定位主导者,再针对性扫描。
7. **把工作树拷到 scratch 目录做隔离** — 失败,而且**误导了我一小时**。拷贝本身没问题,是拷到了一个已经被 `options.txt` 改坏的状态(见 §4.1),我据此误判成"隔离副本坏了"。教训:先验证基线能跑 Metal,再归因。
8. **transparency mask 改成 alpha 正比(`clamp(coverage) * value` 取代二值)** — **保留疑虑,未验证**。理由是 FSR2 的 "write the alpha" 指导,但云/天气/粒子**没有运动矢量**,按 alpha 降低它们的 reactive 会让它们落到无法重投影的历史上。用户报告过云边缘抖动,时间点和这个改动吻合。harness 强制 `cloudStatus(OFF)`,所以从未测到。**这是我最不放心的一处改动。**

---

## 3. 验证门的真实状态(哪些红不是你的)

- **`minecraftMetalFxClientValidation` 必红,与 cutout 线无关**:`MetalValidationClient` 的完成门硬编码 `completed != 10`,而别的线已经把捕获场景加到 12+(object-motion / minecart / arrow 等)。门本身没跟着更新。flicker JSON 在 frame 151 就写完了,**在门抛异常之前**,所以数据照样拿得到,只是进程退出码非 0。
- **`item_spin` 在所有 arm 都失败**,包括不动任何 reactive 旋钮的基线。是别的线的新场景,与 reactive 策略无关。除它以外逐场景契约在我扫过的每个配置里都是 11/12,**完全一致**。
- **`./gradlew build` 会挂在 `metalFrameGenerationPresentationValidation`**(Metal 4 present 线在改)。覆盖 reactive 改动的子集是:
  ```
  ./gradlew test metalFxOffscreenValidation metalMrtSmokeTest jar
  ```
  这组我验证过是绿的。
- **`MetalFXOffscreenValidation.swift` 的 `alpha_test` 断言我改过,已在集成分支里**。旧断言要求"每个 CUTOUT 覆盖像素 `reactive > 127`"——那正是被移除的全抑制策略被写成了测试,它之前能过只是因为合成场景里每个覆盖像素都恰好被判 disocclusion 而拿到 1.0。新断言:轮廓带 `≥72` 至少存在一个,且**没有**覆盖像素 `> 224/255`。这是有意的契约变更,不是放宽阈值。

---

## 4. 金样 / 帧精确捕获的陷阱

### 4.1 最贵的一条:`run/options.txt` 的 `preferredGraphicsBackend`

**必须是 `"default"`。** 否则 `PreferredGraphicsApiMixin` 直接 early-return,`MetalBackend` 根本不进候选列表,客户端**静默跑 OpenGL**,MetalFX 从不初始化。唯一症状是 metallum 只打 **2 行**日志而不是约 150 行——没有任何错误、没有 fallback 警告。

今晚它在某个时点被设成了 `"opengl"`(很可能是 Iris/GL 对比那条线),之后所有从那个 `run/` 派生的树都继承了它,包括我的 scratch 副本和新建 worktree。我据此得出过"Metal 后端被某个提交改坏了"的错误结论,还去 bisect 了提交历史。

**在相信任何「Metal 路径回退了」的结论之前,先跑:**
```bash
grep preferredGraphicsBackend run/options.txt
```
附带:OpenGL 路径上 Fabric 的 `MovingBlockFeatureRendererMixin` 注入失败会直接崩客户端。那是上面这条的**症状**,不是独立 bug(集成分支 `c82bdaf` 已经修了 mixin 那一侧)。

### 4.2 密封石屋场景里没有任何远平面像素

`installSceneClearing` 造的是一个**刻意密封的石屋**,为的是让天气/远景/粒子不破坏逐字节可复现的金样捕获。代价是 `depth.bin` 范围 0.0062–0.0355,**一个 sky 像素都没有**。

后果:任何和天空相关的改动在 `cutout_grass_hold` 上都会测成 **bit-for-bit no-op**。§14 的 sky far-plane 改动就是这么被误判成"无效"的(0.636798 → 0.636861)。天空相关的东西必须用 `cutout_sky_hold`(frame 118 开顶,棋盘格树叶 + 独立渲染藤蔓,相机 −50°)。

flicker JSON 里的 **`skyPixels: 0` 就是"这个场景测不到你以为的东西"的哨兵**,先看它。

### 4.3 Halton phase 必须在 series 起点钉死

`setFlickerCaptureFrame` 在 `first` 时把 `phase = 0`。不钉死的话,warmup 和 terrain-settle 实际渲染了多少帧会因跑而异,起始抖动相位跟着变,**mask 大小和数字一起漂**(实测 `maskPixels` 292961 vs 299084,`maskedMeanDelta` 0.637 vs 0.997)。钉死之后两次跑的 `maskPixels`/`skyEdgePixels` 完全相同,A/B 才能逐像素比。

### 4.4 其它

- **世界存档 `session.lock`**:多会话同时跑客户端会撞。也**不要在别的客户端跑着的时候 `cp -R` 存档**——我这么拷出过一个 torn save,表现为 `Chunk found in invalid location`。
- **不同 arm 的 `scale`/`phases` 必须一致**。今晚所有跑都是 `scale=0.5, phases=32`;文档 §12a 那组老数字是 `0.67/18`。**两组绝对值不能混着比**,只能各自内部比。
- `histogramMean` 空直方图返回 0 而不是 `NaN`——`NaN` 不是合法 JSON,会让 flicker 文件解析不了。
- 跑验证用 `-Dmetallum.validation.output=build/metal-validation/<name>`,路径**相对 `run/`**。`build.gradle` 按 `metallum.` 前缀转发所有 `-D` 到 runClient(不是白名单,不用每加一个旋钮改一次)。
- 构建必须 `JAVA_HOME=/opt/homebrew/opt/openjdk@25`,否则 Gradle 报「不支持发行版本 25」。

---

## 5. 在途补丁:`MinecraftMetal/cutout-shimmer-inflight-2026-07-27.patch`

**这不是半成品。** 它已编译、已跑过 §3 的验证子集(绿)、已构建成 jar 并部署到用户两个实例。之所以没进集成分支,纯粹是协作协议要求我立刻停止写共享 checkout。

补丁在 git 仓库之外(`MinecraftMetal/` 目录下),含 **3 个文件**、170 行:

| 文件 | 改动 | 意图 |
|---|---|---|
| `MetalFxConfig.java` | `cutoutReactiveEdgeWeight` 0.35 → **0.20**;`depthEdgeReactiveCap` 0.5 → **0.25** | §1.2 的线性律结论,轮廓带 −46%、p95 17→9 |
| `MetalFxManager.java` | `EDGE_REACTIVE_MIN` 72 → **40** | 旧的 72 **高于两个新值**,一直是靠 disocclusion 的 0.85 蒙混通过的——它本该证明"边缘带有 reactive",实际证明的是"有像素被判 disocclusion" |
| `docs/cutout-shimmer-remediation-2026-07-27.md` | 新增 §16 | 归因手法、线性律、环境陷阱 |

应用方式(在 worktree 内):
```bash
git apply ../../../../cutout-shimmer-inflight-2026-07-27.patch
```

### ⚠️ 必须先处理的不一致

- 用户两个实例里正在跑的 jar 是 **`f4ebcb8e0708c306…`**,带 **0.20/0.25**。
- 集成分支源码是 **0.35/0.5**。
- **用户看到的行为和当前代码对不上。**

接手后二选一,不要放着:**(a)** 应用补丁、重建、重新部署;**(b)** 从集成分支重建一个 jar 覆盖部署,并告知用户体感会退回上一版。两个实例路径:
```
~/Library/Application Support/minecraft/instances/{MinecraftMetal-Current-2026-07-26,MetalUniversal-26.2}/mods/metallum-1.0.1.jar
```
每个实例**根目录**(不是 `mods/`)有 `CURRENT-BUILD-RECEIPT.txt`,当前内容描述的是 0.20/0.25 那一版,含完整回滚开关列表。launcher profile **不需要改**——三个 profile 都指向这两个 gameDir,新默认值自动生效;`javaArgs` 里没有任何会覆盖 reactive 旋钮的旧值。

---

## 6. 距离「验证门恢复绿」还差什么

按影响排序:

1. **完成门的 `completed != 10` 需要跟捕获数走**(在 `MetalValidationClient`)。这不是 cutout 线的改动,但门红在这里,S9B/C 与 S10 的开启态验收被它挡着。**这一项和 cutout 判据无关,可以独立推进。**
2. **`item_spin` 场景**(别的线)。
3. **cutout 判据侧只剩一件事:补丁里的 `EDGE_REACTIVE_MIN` 40 要和新默认值一起进去**,否则新默认值下那条断言又变成靠 disocclusion 蒙混。判据本身已经演进完并有定量依据。

**真正缺的证据不是门,是 ghosting。** 所有数字都是静止相机 hold,而 ghosting 正是 reactive mask 存在的理由。"越低越好"只在拖影没冒出来之前成立。移动相机 / 遮挡揭露契约通过只是弱证据,不是证明。**建议下一步优先建一个移动相机的拖影度量,而不是继续往下压 reactive。**

---

## 7. 需要用户裁决的悬而未决事项

1. **补丁应用与否 / 部署一致性**(§5 的 a/b 二选一)。这个必须先定,因为用户随时会进游戏看效果。
2. **transparency 的 alpha 正比是否回退**。这是我最不放心的改动(§2.7),疑似造成云边缘抖动回退,且 harness 结构性测不到(强制关云)。回退方式:让无运动矢量的层(clouds/weather/particles)保持配置值,只让 translucent/itemEntity 按 alpha 正比;或直接 `-Dmetallum.metalfx.transparencyReactiveValue=1.0` 观察。**注意目前没有任何旋钮能退回二值行为**,要退需要改代码。
3. **是否继续往下压 reactive**。线性律预测 `edge=0.10 / cap=0.10` 还有明显收益,但零点是外推不是实测(三次尝试都死在 §4.1 那个 OpenGL 崩溃上),而且 ghosting 未测。
4. 用户最后一次反馈是:**要看树顶、藤蔓和云边,特别是转视角移动时有没有拖影**。这个体感验收还没做。

---

## 8. 别人给我、但我没来得及处理的两条线索

均由协调会话提供,记录以免丢失:

1. `@_cdecl("metallum_metalfx_mark_transparency")` 的 compute encoder **完全没有 fence**(ABI 签名里就没有 fence 参数,函数体零 `waitForFence`/`updateFence`),它读 translucent/itemEntity/particles/weather/clouds、写 `reactive`,两侧同步边都缺;紧邻的 `encodeCutoutReactiveMask` 却传了 fence。纹理是 `hazardTrackingMode = .untracked`,驱动不兜底。**如果 reactive mask 出现间歇性、不可复现的内容错误,先查这里。** 同样零 fence 的还有 `metallum_metalfx_encode_motion_v2` 里的 `historyBlit`。
2. MetalFX TEMPORAL 开启时,**Iris 对 CUTOUT 地形的覆盖会被静默绕过**:`MetalCutoutReactivePipeline.forVertexFormat` 造的管线 location 是 `metallum:pipeline/terrain_cutout_reactive`,命名空间不含 `sodium`,而 Iris 侧 `isSodiumPipeline` 判的就是这个 → 返回 null,一行日志都不打。当前默认 `Mode.OFF` 不会踩,阶段二一开就爆。建议在 `metallum$compileCutoutReactivePipeline` 加 warn-once。
