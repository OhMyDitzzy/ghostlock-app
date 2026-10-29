# 内核 profile 适配指南

> English: [README.md](README.md)

GhostLock 用 `uname -r` 精确匹配内核版本。匹配不到时应用会拒绝运行。当多个设备
共享同一内核版本（Android Common Kernel 常见）时，应用会进一步用从 `boot.img`
提取的 bootconfig 参数 `androidboot.hardware` 和 `androidboot.product.device`
进行消歧，运行时与 `Build.HARDWARE` 和 `Build.DEVICE` 比对。支持一款
新内核通常不用改代码，只要补一份 profile（HOCON 配置）。

只有内核几何（符号与结构体偏移）是必填项。`recommend_shizuku` 和 `execution`
里的重试次数、等待时间都是建议值：profile 可以省略，省略时使用随包默认值。
见[公共执行默认值](defaults_ZH.md)。

## 开始之前

- 一台能用 `adb` 连接的目标设备，或与设备同版本的固件包。
- 项目能构建：`./gradlew :app:assembleDebug` 通过。
- 提取工具能编译（[`tools/extract_rs`](../../tools/extract_rs)，Rust）。

## 配置文件放在哪

内置配置都在 `app/src/main/assets/kernel_profiles/`：

- `index.conf`：内置内核清单。新 profile 必须登记到这里。
- `<uname-r>_<hardware>_<product-device>.conf`：每个内核变体一份完整 profile。
  `index.conf` 中的 `release` 必须与 `uname -r` 完全一致。当多个 profile 共享同一
  release 时，文件名编码 hardware（`androidboot.hardware`，如 `qcom`、`mtk`、`exynos`）
  和 product device（`androidboot.product.device`，如 `sakura`、`veux`、`fog`）
  用于消歧。运行时 App 会选择最佳匹配。
- `execution-tuning.conf`：所有内核共用的调优值。
- `execution-<route>.conf`：各攻击路线（route）专属的调优值。这些 preset 由
  resolver 加载，设备 profile 不 include。
- `credential-6x.conf`、`kernelsnitch-6x.conf`：6.x 内核共享的凭据模板与
  KernelSnitch 参数。
- `*-template.conf`：带逐字段注释的参考模板。可以在调试页手动加载，但不参与
  设备匹配。
- `docs/kernel_profiles/templates/`：按大版本整理的模板文档。

这些文件都按 HOCON（Typesafe Config）解析，支持 `#` 注释、`${?变量}`、尾逗号，
以及 `include "file.conf"`（相对同目录、可嵌套）。JSON 仍是合法 HOCON，无需转换。

字段含义、必填矩阵与校验规则见 [Profile 结构文档](PROFILE_SCHEMA_ZH.md)。

## Bootconfig 消歧机制

当提取器处理带有 v3/v4 头部且包含 bootconfig 节区的 `boot.img` 时，它会解析
`androidboot.hardware` 和 `androidboot.product_device`，并把它们嵌入生成的 profile
的 `bootconfig` 块中：

```hocon
bootconfig {
  hardware = "qcom"
  product_device = "sakura"
}
```

运行时，App 读取 `Build.HARDWARE` 和 `Build.DEVICE`，并与 profile 的
`bootconfig.hardware` 和 `bootconfig.product_device` 比对。

- 如果 profile 没有 bootconfig 块，它匹配该内核 release 下的任意设备（旧行为）。
- 如果 profile 有 bootconfig 且设备值完全匹配，选中该 profile。
- 如果 profile 有 bootconfig 但设备值不匹配，跳过该 profile，在同一 release 下
  寻找其他 profile。
- 如果没有任何 profile 匹配设备的 bootconfig，该内核视为不支持。

这样就可以为同一内核 release 编写多个设备专用 profile（如
`6.6.118-android15-8-..._qcom_sakura.conf` 和
`6.6.118-android15-8-..._mtk_veux.conf`）。

## 适配一款内核

1. **确定内核版本。**
   运行 `adb shell uname -r`，把输出完整记下来。任何字符差异都会导致匹配失败。

2. **确定 hardware 和 product device（可选但推荐）。**
   运行 `adb shell getprop ro.hardware` 和 `adb shell getprop ro.product.device`
   （或在代码中查看 `Build.HARDWARE` / `Build.DEVICE`）。它们对应 bootconfig 里的
   `androidboot.hardware` 和 `androidboot.product.device`。

3. **选择模板。**
   按大版本打开对应文档：
   [5.x](templates/kernel-5.x.template_ZH.md)、
   [6.1](templates/kernel-6.1.template_ZH.md)、
   [6.6](templates/kernel-6.6.template_ZH.md)、
   [6.12](templates/kernel-6.12.template_ZH.md)。

4. **复制成新的 profile。**
   把模板复制到 `app/src/main/assets/kernel_profiles/<uname-r>_<hardware>_<product-device>.conf`，
   文件名必须包含 release 加上 hardware 与 product device，然后填入必填字段。
   如果无法获取 bootconfig，旧命名 `<uname-r>.conf` 仍然有效。

5. **提取偏移。**

   ```sh
   (cd tools/extract_rs && cargo build --release)
   build/extract/release/ghostlock-extract boot.img --format conf
   ```

   提取器现在会使用新命名约定自动写入文件：
   `<release>_<hardware>_<product-device>.conf`
   （如 `6.6.118-android15-8-..._qcom_sakura.conf`）。如果 bootconfig 不可用，
   则退回到 `<release>.conf`。用 `--out` 可覆盖输出路径。

   `--format conf` 输出 flatten（无 `include`、6.x 凭据/KernelSnitch 常量内联）的
   自包含 profile：写出所选路由的全部字段与公共几何，无法取得的值写显式 `null`，
   不会用相邻内核族的猜测值补齐。把它当作候选底稿：导入后由 App 校验缺失字段并在
   执行前拦截，生成成功不代表可用。5.x 的凭据引用修复与 multicast 几何也会从
   镜像与 BTF 自动推导。

   工具支持 `boot.img`（可附带 `xbl_config.img`）、完整 OTA zip，或指向它们的
   `http(s)` 链接。kallsyms 可以显式传 `--kallsyms`，省略时会尝试恢复镜像内嵌表。
   `pselect_waiter_shift` 和 `off_slide_loggers_0_1` 由内置的 arm64 反汇编器推导，
   route 由内核证据建议（`--route` 覆盖）。联发科的物理地址可通过
   `--vendor-boot vendor_boot.img` 或 `--iomem iomem.txt` 取得。vendor 头部提供加载
   地址，且仅在对齐模式能证明时提供 DRAM 基址。iomem 转储提供 DRAM 基址与
   `Kernel code` 范围，后者需结合 kallsyms 推导加载地址，单独的 `_text` 不足以
   得到物理地址。`--phys` 仅提供显式加载地址，不提供 DRAM 基址。

5. **逐字段转录并自检。**
   字段含义与必填要求见 [Profile 结构文档](PROFILE_SCHEMA_ZH.md)。确认所有必填的
   `off_*` 非零，task/cred 布局来自同一镜像，然后按需设置 `recommend_shizuku`
   （默认 `0`；部分随包 profile 设为 `1`）。

6. **确有必要时才改 `execution`。**
   没有真机实测证据就保留[公共默认值](defaults_ZH.md)。也可以先在 App 的参数
   覆盖页试，确认后再写回 profile。

7. **登记到 `index.conf`。**
   增加一条 `{release, file}`，然后运行校验与构建：

   ```sh
   jq . app/src/main/assets/kernel_profiles/index.conf
   (cd tools/extract_rs && cargo test)
   ./gradlew clean :app:assembleDebug
   ```

8. **真机验证。**
   用 `./gradlew installDebug` 装到设备上，在相同环境、固定核心、单一攻击路线下
   反复测试。测试前先让机身降温，避免 CPU 降频影响结果。未通过真机验证前，
   不要标记为已支持。

## 在 App 内修改配置

调参不必每次都改 assets 再重装 App。两层覆盖保存后立即生效，并参与下一次运行：

- **参数覆盖页**：调整 `execution.*` 的调优值，例如尝试次数、等待/超时时间、
  核心选择。
- **高级配置覆盖页**：修改 `route` / `fallback.to`，以及任意数值路径（以 sparse
  HOCON 保存）。适合试验新的偏移或几何。

**导出配置**把合并后的完整 HOCON 写到你选定的目录（SAF），可以直接当作新
profile 的底稿。

覆盖按 release 保存，优先级高于内置 profile 和导入的偏移。各层优先级与存储
位置见 [Profile 结构文档](PROFILE_SCHEMA_ZH.md) 第 7 节。给内置库新增内核仍需
走上面的流程并提交 assets。

## 安全注意事项

- 模板里的 `off_* = 0` 是相对内核镜像基址的符号偏移，必须自己提取。
- task/cred/multicast 布局写错可能造成任意内核内存破坏、黑屏或重启。
- 调大 `execution` 的尝试次数、调短等待时间会明显升温，并可能降低成功率。
- 新 profile 在通过真机验证前，不要提交为已支持。
