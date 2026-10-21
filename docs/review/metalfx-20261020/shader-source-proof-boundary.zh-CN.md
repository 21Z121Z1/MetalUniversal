# 同名核心 shader 的语义证明边界

源码 Identifier、namespace、defines、layout 和 blend 状态不足以证明实际 shader 内容。资源包可在同一 ID 下改变变形、clip 或 discard；实际 include 读取及其原生 owner 必须一起审查。

当前捕获绑定每个异步 pending compile 自己的 ShaderSource。实际根源码、经过声明长度检查的 include 内容、预期 stage 集合及 import 依赖与固定 26.3 client 参考逐项比较；不从全局当前库补写旧 pending 的内容。编译快照不可变，中途变化、缺失、错误依赖、大小／条目预算、未知输入和 location 宏均保留拒绝原因。

80 项参考只含 SHA256、字节数与依赖，不打包 Minecraft shader 正文；原始 client 身份先核对 metadata 声明的 41483720 字节和 SHA1 `e877b6a07acd633fb3bb475002175cec036e7b87`。精确展开的 fixture 输入与真实 raw/include 输入分别验证，不能以 classloader 第一份同名资源冒充原始参考。

library proof 属于实际 Configs generation。编译任务持有跨越 shaderc 指针使用的 lifetime 租约；关闭使旧证明失效，reload 的新 owner 不复活旧输入。UI preload 的临时 provider 不能替换完整 live 库，backend lazy cache 随完整库发布退役。来源读取长度错误的更正、固定依赖与真实客户端边界见[所有权核验](source-provider-lifetime.zh-CN.md)。

待验证的运行门禁要求：被审计 motion family 的实际 compiled 输入匹配且 owner 存活，才能冻结其 SourceState；退役 owner 不再生成历史身份。失败只否决该 compiled 输入所属 owner，不能用旧库错误污染另一个新库。Temporal 同时需要设备能力、canonical live 库与已证明的源语义；当帧发现未知内容时丢弃其历史／FG 收据并回退真实颜色，随后重选安全模式。该门禁尚须自己的准确 SHA 类型、原生负例及客户端验证，不能借用只捕获指纹阶段的绿灯。

census 的 `observedCompiles` 保留累计内容快照，`canonicalProofs` 单列匹配及 generation。它们用于诊断真实执行的程序，不等于某一源帧全部 producer 的完备证明。所有原始输入快照仍 `sourceAdmissionGranted=false`；canonical 内容匹配也不自动发放完整 FG、UI、颜色或混合投影准入。
