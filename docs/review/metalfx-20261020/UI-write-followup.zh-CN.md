# UI 内容与 shader 写入：有限后续修正

本文件对应的修正必须按包含它的准确SHA验证；不沿用36或ffed的通过结果。当前不授权合并、删除或开启完整FG/UI/颜色准入。

## 可复核的因果路径

`MetalGpuTexture.recordMaterializedClear`记住整个纹理的清空值，后续`clearIsRedundant`可省略同值clear。原`MetalComputePass`只记录/复用texture绑定，dispatch之后没有使该缓存失效。于是“全clear 0 → imageStore非零 → 再clear 0”可能被误判为冗余；UI诊断也可能错误复用已经失效的clear-only内容条件。

修正位于普通及间接dispatch实际编码之后：所有绑定纹理的写访问在缺少机器可读read-only证明时保守失效，不以UI诊断是否开启为条件。它不修改实际shader、dispatch范围或同步策略。render storage image同样在每次实际draw之后失效，覆盖显式绑定和Iris解析后的fallback绑定，不依赖只在首次bind时失效。未知访问保守失效可能使read-only纹理之后多一次clear；后续若优化必须先提供准确访问反射，不能假设所有compute纹理只读。

## 独立执行控制

- 普通/间接imageStore分别写RGB=.8、alpha=.1。全图原始RGBA读回必须RGB>alpha，clear-only证明必须撤销，旧clear缓存不得仍声称等于0。
- 接着再次请求相同clear 0；全图每一通道必须真为0，不能只检查缓存字段或函数返回。之后clear-only必要条件才可重新成立。
- raster imageStore将独立UI纹理作为storage image，FBO颜色附件是另一个目标。要求实际非PMA像素被读回、UI的颜色draw清单为空而潜在shader写计数非零；随后同值clear必须实际擦除。UI owner关闭后证明失效，即使view仍保留GPU资源。
- HDR独立控制保留RGBA16F输入4.0/2.0辐射及alpha=.25，SDR decoder拒绝前后字节一致且不分配SDR输出。这不等于实现HDR tone mapping。

通用UI shader输出范围、全写入覆盖和production premultiplied证书仍为未证明。这里只实现和检验有界完整clear的必要内容条件以及后续写入撤销，不从局部条件自动发放完整FG准入。
