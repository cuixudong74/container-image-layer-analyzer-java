# container-image-layer-analyzer-java

本地容器镜像的分层体积分析与冗余内容优化工具。输入为磁盘上已导出的镜像归档
（`docker save` / OCI layout 的 tar 或 tar.gz）或展开后的镜像目录，整个过程不
依赖任何外部服务。

## 功能概览

- **分层体积报告**：按层与整镜像统计体积，条目区分普通文件（FILE）、目录
  （DIRECTORY）、符号链接（SYMLINK）、硬链接（HARDLINK）、删除标记
  （WHITEOUT）、不透明目录标记（OPAQUE_MARKER）与其他（OTHER）。
- **叠加语义还原**：正确处理后层覆盖、whiteout 删除、不透明目录、硬链接引用，
  重建最终文件系统并计算可回收空间。
- **有界内存**：所有内容经固定大小缓冲区流式处理（哈希边读边算），层内容从不
  整体入内存；报告每层只保留体积最大的 top-N 条目。
- **确定性**：相同输入在任意并发度与处理顺序下产生逐字节一致的报告。
- **任务调度**：多任务共享执行池，按层切分工作单元交错执行，单个大任务不会
  饿死其他任务；支持取消与超时，资源及时释放、不留中间产物。
- **镜像优化**：产出清除冗余后的等价镜像（squash 为单层 + 内容去重为硬链接），
  其叠加后的最终文件系统与原镜像等价。

## 叠加语义

按层序（从底层到顶层）依次应用每个条目，规则如下：

1. 同路径的后层条目覆盖先层条目；被覆盖条目的字节计入**可回收**。
2. 目录是**合并**的：上层重建同名目录不会删除下层目录里的子条目。
3. 非目录条目替换目录时，整个子树被移除并计入可回收。
4. 名为 `.wh.<name>` 的条目是 whiteout：删除同级的 `<name>` 及其子树。
5. 名为 `.wh..wh..opq` 的条目是不透明目录标记：隐藏该目录所有来自下层的子
   条目，但保留同层及后层新增的子条目。
6. 硬链接条目引用同镜像内另一路径的内容，自身不新增内容字节。
7. whiteout 与不透明标记本身不出现在最终文件系统中。

## 体积口径

- **层体积（totalBytes）**：该层所有普通文件内容字节之和（元数据、目录、链接
  不计内容字节）。
- **镜像总体积**：各层体积之和。
- **最终文件系统体积（finalFilesystemBytes）**：叠加还原后最终文件系统中所有
  普通文件的字节之和。
- **可回收字节（reclaimableBytes）**：被后层覆盖、被 whiteout 删除、或被不透明
  目录隐藏的条目字节，按**贡献层**（条目最初出现的层）与**路径**归属，报告给
  出 top 贡献层与 top 贡献路径。
- **重复内容字节（duplicateContentBytes）**：最终文件系统中 SHA-256 相同的多份
  文件内容，除第一份外的字节合计（优化时以硬链接去重）。
- 报告条目中的 `redundant` 标记表示该条目未出现在最终文件系统中。

## 错误处理

损坏输入绝不静默通过。tar 截断（TRUNCATED_ARCHIVE）、校验和不匹配
（CHECKSUM_MISMATCH）、路径穿越（PATH_TRAVERSAL）、非法元数据
（INVALID_METADATA）、I/O 错误（IO_ERROR）都会使对应**层**失败并中止扫描，
任务状态为 FAILED 并携带层序号、层 ID 与原因；优化任务失败或取消时，暂存目录
（`<输出目录>.tmp`）会被完整删除，不会留下部分写入的产物。报告与优化产物均采
用"临时文件 + 原子改名"发布。

## 任务模型

- 任务状态：`PENDING → RUNNING → COMPLETED / FAILED / CANCELLED / TIMEOUT`。
- 每个任务被切分为按层的工作单元，在共享线程池上交错执行（公平性）。
- 取消与超时是协作式的：运行中的单元在下一次缓冲读取检查点中止，随后释放流
  并清理中间产物。

## 配置项（application.properties 前缀 `analyzer.`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `analyzer.buffer-size-bytes` | 65536 | 每条流的 I/O 缓冲区，约束单层内存占用 |
| `analyzer.worker-threads` | 2 | 所有任务共享的工作线程数 |
| `analyzer.task-timeout-millis` | 600000 | 单任务最长运行时间，超时置为 TIMEOUT |
| `analyzer.temp-dir` | 系统临时目录 | 中间产物（如外层 gzip 解压）存放目录 |
| `analyzer.max-report-entries-per-layer` | 10000 | 报告每层保留的最大条目数（按体积 top-N） |

内存上界：单层的文件内容只经过 `buffer-size-bytes` 的缓冲区；报告每层至多保留
`max-report-entries-per-layer` 条元数据；合并视图只保存条目元数据（路径、类型、
大小、哈希），不保存文件内容。

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/scans` | 提交扫描任务，body: `{"path": "/path/to/image.tar或目录"}` |
| POST | `/api/optimizations` | 提交优化任务，body: `{"path": "...", "outputDir": "..."}` |
| GET | `/api/tasks` | 列出所有任务快照 |
| GET | `/api/tasks/{id}` | 查询任务状态与实时进度（已完成层数、已扫描字节、当前层） |
| POST | `/api/tasks/{id}/cancel` | 请求取消 |
| GET | `/api/tasks/{id}` → result | 完成后经 `/api/tasks/{id}/result` 获取报告 JSON |

## 优化产物

优化输出为 docker-save 布局目录：单个 `optimized0/layer.tar` 包含最终文件系统的
全部条目（被覆盖/删除的内容不复存在；重复内容以硬链接指向首次出现的路径），
外加确定性的 `manifest.json` 与合成 `config.json`。产物先写入 `<输出目录>.tmp`
再原子改名，因此任何时刻都不存在部分写入的镜像。

## 构建与测试

```bash
mvn package        # 构建
mvn test           # 运行全部测试（覆盖跨层重复、whiteout、不透明目录、
                   # 硬链接、损坏输入、并发、取消、超时与确定性）
java -jar target/container-image-layer-analyzer-java-1.0-SNAPSHOT.jar
```
