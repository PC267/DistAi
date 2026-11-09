<p align="center"><img src="docs/banner.png" alt="DistAI：在多部安卓手机上运行同一个大语言模型" width="100%"></p>

# DistAI：在多部安卓手机上运行同一个大语言模型

DistAI 将普通 ARM64 智能手机（如果你有的话，加上 PC 或树莓派）的 RAM 和 CPU 聚合在一起，来运行一个单台设备无法容纳的大型语言模型。它由一个 Android 应用和一套基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) 及其 **RPC** 后端的 PC 工具组成。


## 目标与成果

**目标：** 在普通智能手机上，以可用的 token 速率运行一个比任何单台手机能容纳的都要大的 LLM。

- **在手机间分发模型是可行的。** 节点会自动相互发现，层会被自动拆分，集群会提供一个聊天 UI 和一个兼容 OpenAI 的 API。
- **代价是速度变慢。** RPC 流水线累加的是设备的*内存*，而不是它们的*速度*：每个 token 必须按顺序穿过所有手机，并在每一跳付出网络延迟的代价。
  在 PC 加一部手机的组合上运行 7B 模型大约只有 2 tok/s。一个单独在 PC 上能跑到 7.0 tok/s 的 30B 模型，在拆分后降到了 3.9 tok/s。
- **速度通过其他方式找回**（见下文）：混合专家模型（MoE）、批处理、副本以及有线连接。对于多个用户，集群总吞吐量可达 39 tok/s。

## 目前的功能

- **Android 应用**（`app/`）：每部手机都成为一个节点，并通过 mDNS 宣告自己的存在。其中一部手机作为协调器（Coordinator）：它负责下载模型、计算层的拆分、启动 `llama-server` 并打开内置的聊天界面。它还运行自动基准测试，保持服务存活（前台服务、唤醒锁），并有一个“释放内存”按钮。
- **PC 节点**（`tools/pcnode.py`）：Linux、Windows 和 macOS 可以像手机一样加入集群；专用的 GPU（Vulkan/Metal）可以作为独立节点。
- **优化的 ARM 二进制文件**：三种构建版本（`v8`、`v82`、`v86`），在运行时根据 CPU 特性自动选择。
- **llama.cpp 补丁**（`native/patches/`）：重新打包的内核和 RPC worker 内部的持久线程池。
- **测量工具**（`tools/`）：token/s 和延迟基准测试、22 个编码任务的质量测试、副本路由、拆分规划器。
- **实验性扩散引擎**（ggml 上的 BD3LM 块扩散）：`native/patches-sperimentali/`。

## 使用的技术

| 技术 | 作用 |
|---|---|
| 基于 llama.cpp RPC 的层流水线 | 让模型跨多台设备运行 |
| 加权拆分规划器（空闲 RAM、带宽、延迟；排除慢节点） | 按比例利用每个设备；安卓空闲内存按约 50% 计算 |
| mDNS 发现，手动 IP 回退 | 零配置集群 |
| 输出层保留在协调器上 | 避免每个 token 发送约 600 KB 的 logits：3.5 → 5.3 tok/s |
| RPC worker 中的重打包内核 (`rpc-repack.patch`) | prompt 提升 ×4.7，通过 RPC 的 prefill 提升 ×3-4 |
| 持久线程池、绑定大核、flash attention、轮询 | 小米设备提升 +23%（仅限 v82/v86） |
| 按 CPU 分发的二进制文件（`v8`/`v82`/`v86`） | 在可用的情况下使用 dotprod、fp16 和 i8mm |
| 混合专家模型，减少激活专家数（8 → 6） | PC 上的 Qwen3-Coder-30B-A3B 达到 7-8 tok/s；提升 12-25% 且质量无损失 |
| 批处理（同时处理多个请求） | 小米设备在 4 用户时从 8 → 22.8 tok/s |
| 副本 + 路由，代理队列 | 22 个编码任务只需 67.7 秒，而不是 203 秒（3.0×） |
| 投机解码（仅在实测有帮助时启用） | 在一部手机上提升 +13%，其他手机自动关闭 |
| 用 USB 线缆（adb forward、USB 网络共享、以太网）代替 Wi-Fi，自动选择最低延迟通道 | 237 MB/s vs ~2 MB/s，延迟约 2 ms |
| Wi-Fi 保活、唤醒锁、忽略电池优化 | 消除 200 毫秒以上的延迟尖峰和后台查杀 |

## 优点与缺点

**优点**
- 在人们已有的硬件上运行单台手机无法容纳的模型。
- 除了安装应用外无需其他设置：发现、拆分和模型传输全自动。
- 对于多个用户或代理（副本）扩展性好，并且支持 PC 和树莓派。
- 文档中的每一项声明都是经过测量的，包括那些没有奏效的方案。

**缺点**
- 单次对话的速度永远不会快于能容纳整个模型的最快设备；拆分的模型总是比能单机装下的模型慢。
- 不支持 dotprod 的旧手机（例如麒麟 970）速度很慢：在 3B 模型上大约只有 3.4 tok/s。
- Wi-Fi 的延迟不可靠；用线缆会好得多。
- RPC 协议没有身份验证或加密：请仅在受信任的私有网络中使用。
- 手机无法达到 50-100 tok/s 运行密集型 27B/70B 模型的目标（参见 [`ROADMAP.md`](ROADMAP.md)）。

## 步骤：通过 USB 在三部手机上运行 14B 模型

这是在 **Qwen2.5-Coder-14B-Instruct (Q3_K_M, 7.3 GB)** 上使用的确切步骤：通过纯 USB 线缆（`adb forward`，无 Wi-Fi）从 Linux PC 驱动，将模型均匀拆分到三部手机上。这里的目标不是速度（拆分的模型很慢），而是证明模型确实存在于手机的内存中。

**你需要：** 一台有约 12 GB 空闲磁盘空间、Python 3、`adb`（[platform-tools](https://developer.android.com/tools/releases/platform-tools)）、cmake 和 C/C++ 编译器的 Linux PC；三部每部至少有 ~3 GB *空闲* RAM 的 ARM64 手机；USB 线缆（如果 PC 接口少，可以加个带电源的 USB 集线器）。

**1. 在每部手机上启用 USB 调试**
1. *设置 → 关于手机* → 连按 7 次 **版本号**（开发者模式）。
2. *设置 → 开发者选项* → 开启 **USB 调试**（以及充电时的 **保持唤醒**）。在小米/MIUI 上，还需开启 *通过 USB 安装*。
3. 插上手机并接受“允许 USB 调试？”提示（勾选 *始终允许*）。
4. 检查每部手机是否被列为 `device`（而不是 `unauthorized`），并记下序列号：
   ```bash
   adb devices -l
2. 在每部手机上构建并安装 APK

bash
./gradlew assembleDebug                      # APK 位于 app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL_1 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL_2 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL_3 install -r app/build/outputs/apk/debug/app-debug.apk
如果 install -r 因签名不匹配而失败，请先卸载：adb -s SERIAL uninstall com.distai.app。
（ARM 二进制文件必须已经位于 app/src/main/jniLibs 中：运行一次 ./native/build-android.sh，见 构建。）

3. 在每部手机上启动 worker
在每部手机上打开 DistAI 应用。它会启动一个带有 RPC worker（端口 50052）的前台服务。
授予通知权限，关闭该应用的电池优化，保持手机充电，并在加载大模型前点击 释放内存。
不要在任何手机上启动协调器：在这里，PC 是协调器。

4. 构建 PC 端

bash
git clone https://github.com/ggml-org/llama.cpp native/llama.cpp
git -C native/llama.cpp checkout 69f201a     # 与手机相同的提交：RPC 协议必须匹配
python native/build-pc.py                    # llama-server 在 native/build-pc/bin 中（自动应用补丁）
5. 下载模型

bash
mkdir -p ~/distai-models
curl -L -C - -o ~/distai-models/qwen2.5-coder-14b-instruct-q3_k_m.gguf \
  https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF/resolve/main/qwen2.5-coder-14b-instruct-q3_k_m.gguf
-C - 用于断点续传。文件必须正好是 7339203968 字节。

6. 启动集群

bash
export SERIAL_XIAOMI=SERIAL_1 SERIAL_HUAWEI=SERIAL_2 SERIAL_SAMSUNG=SERIAL_3   # 名称只是标签：任何三部手机都可以
python3 tools/video14b.py
tools/video14b.py 会执行以下操作：

从 PC 向每部手机的 RPC worker 创建 adb forward（127.0.0.1:50151..50153 → 手机 :50052）；

在 PC 上启动 llama-server（:8090），参数为 -ngl 49 -ts 1,1,1，并将嵌入/输出张量保留在 CPU 上：所有 48 层发送到手机，每部手机三分之一（每部手机约 2.3 GB），所以每个 token 只有很小的隐藏状态穿过线缆；

在 http://127.0.0.1:8099 启动 PC 仪表盘。

当 llama-server 加载模型时，权重数据会通过 USB 传输；当 /health 响应时，脚本会打印 PRONTO（就绪）。你可以在仪表盘中看着每部手机的 RAM 被填满。使用另一个模型：python3 tools/video14b.py other-model.gguf（文件在 ~/distai-models 中），或另一个 llama-server：LLAMA_SERVER=/path/to/llama-server。

7. 生成

打开 http://127.0.0.1:8099/：它会显示每部手机真实的 CPU 和 RAM（通过 adb 读取），文本随着集群的生成而流式传输。

或者直接使用兼容 OpenAI 的 API：

bash
curl http://127.0.0.1:8090/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Write a Python function that computes the primes up to n."}],"max_tokens":200}'
8. 停止

bash
pkill -x llama-server; pkill -f tools/dashboard.py
关闭手机上的应用以释放它们的 RAM。

故障排除

手机 rpc.log 中的 Failed to create server socket 是无害的：应用的 worker 已经占用了端口 50052，脚本使用的是那个端口。

仪表盘中手机显示为 离线：检查 adb devices，然后确认应用正在运行（worker 是 librpcserver 进程）。

模型装不下：降低上下文（-c）或使用更小的量化；为安卓系统保留每部手机约 1.5-2 GB 的内存（参见 能装下多大的模型）。

如果线缆被拔出，adb forward 会消失：停止并重新运行 tools/video14b.py（worker 会将权重保留在缓存中，因此第二次启动更快）。

绝对不要在提交中放入序列号、IP 或本地主路径：此仓库是公开的。

文档
文件	内容
ROADMAP.md	目标、仍待测试的想法（扩散 LLM、分布式专家等）
NOTES.md	测量数据、被舍弃的方案、单设备检查清单
native/patches-sperimentali/README.md	实验性补丁及其结果
快速开始
在每部手机上安装 APK（adb install -r DistAI.apk）。

将所有设备置于同一网络中。如果路由器开启了“客户端隔离”，请将其关闭。

在每部手机上打开应用：它会成为一个 worker。关闭电池优化并保持手机充电。

在 一部 手机（最好选择内存最大的那部）上，打开 协调器 部分：
选择一个模型 → 下载模型（或者复制一个 .gguf 文件到 Android/data/com.distai.app/files/models/）→
启动分布式 LLM → 当出现 ✅ 时，打开对话。

从同一网络的 PC 上，使用位于 http://协调器IP:8080/v1 的兼容 OpenAI 的 API。

能装下多大的模型
Q4_K_M 模型大约需要每十亿参数 0.6 GB 加上上下文缓存。在每部手机上为安卓系统保留 1.5-2 GB 的内存：

模型大小	文件大小	需要的 6-8 GB 手机数量
0.5B-3B	0.4-2 GB	1
7-8B	~5 GB	1-2
14B	~9 GB	2-3
32B	~20 GB	4-6
70B	~42 GB	8-12
根据模型需要，使用尽可能少的节点。拆分一个已经能放在单个设备上的模型只会让它变慢。

连接方式
推荐优先使用线缆：使用较新版本 adb 的 USB（Android SDK platform-tools；旧版 adb 1.0.32 只能达到 3.9 MB/s）、USB 网络共享或 USB-C 以太网适配器。
如果没有线缆，请使用 5 GHz 或 Wi-Fi 6，并将手机靠近路由器，同时查看 NOTES.md 中的 Wi-Fi 省电说明。

ARM 变体
变体	-march	典型 CPU
v86	armv8.6-a+dotprod+fp16+i8mm	骁龙 8 Gen 1+, 天玑 9000+, Tensor G2+
v82	armv8.2-a+dotprod+fp16	骁龙 845-888, Exynos 9810+, 麒麟 980+
v8	armv8-a	其他所有 ARM64
构建
要求：Android SDK、NDK（已用 r29 测试）、JDK 17+、cmake、Python 3。

bash
git clone https://github.com/ggml-org/llama.cpp native/llama.cpp
git -C native/llama.cpp checkout 69f201a      # 参考提交 (2026-10-10)
./native/build-android.sh                     # arm64 二进制文件 (3 个变体) 生成到 app/src/main/jniLibs
./gradlew assembleRelease                     # APK 生成到 app/build/outputs/apk/release/
在 Windows 上使用 gradlew.bat（JDK 17）。在 Android Studio 中打开项目会自动生成带有 sdk.dir 的 local.properties。

将 PC 作为节点 (Linux, Windows, macOS)
bash
python native/build-pc.py        # ggml-rpc-server 和 llama-server 生成到 native/build-pc/bin
pip install zeroconf             # mDNS 宣告 (在 Linux 上，avahi 也可以)
python tools/pcnode.py           # worker :50052, 状态 :50053, 按需启动协调器 :8090
GPU：python native/build-pc.py --gpu 和 python tools/pcnode.py --gpu 会让 PC 成为 两个节点（CPU 和 GPU），分别进行基准测试。对于集成显卡，通常纯 CPU 更快。

你需要 cmake 和 C/C++ 编译器（gcc/clang；在 Windows 上需要 MSVC Build Tools 或 llvm-mingw）。

在 Windows 上，允许 ggml-rpc-server 和 python 通过专用网络的防火墙。

测量工具
tools/ 中的脚本从 SERIAL_XIAOMI、SERIAL_HUAWEI 和 SERIAL_SAMSUNG 读取手机序列号（用 adb devices 查找你自己的），并将 IP 作为参数（--help）。修改 tools/lbench.py 中的设备名称以适配你自己的。

测试设备
三星 Galaxy Note 10 (Exynos 9825)、华为 Mate 10 Pro (麒麟 970)、一部联发科 SoC 的小米手机、树莓派 4/5，以及一台 i7-7700 的 PC。
这些都是旧款或中端设备；带有 i8mm 的较新手机（v86 构建版）应该表现更好。

状态
实验性。它能工作且测量结果可复现，但 UI 比较粗糙，应用字符串和代码注释是意大利语（译者注：现已加入中文支持）。
欢迎提供其他手机上的测量数据：在 NOTES.md 的检查清单中添加一列。

许可证与致谢
MIT（第三方声明：THIRD_PARTY_NOTICES.md）。基于 llama.cpp（MIT）和 ggml 构建；native/ 中的补丁修改了该代码。

每个模型（Qwen、LFM2、Stable-DiffCoder 等）都有自己的许可证：在重新分发之前请进行检查。
