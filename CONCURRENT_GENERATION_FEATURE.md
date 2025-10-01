# 并发道路生成控制功能

## 📋 功能概述

新增配置选项 `maxConcurrentRoadGeneration`，允许玩家控制**同时生成的道路数量上限**，以平衡性能和生成速度。

---

## 🎯 为什么需要这个功能？

### 问题背景
- 道路生成使用 A* 算法，CPU 密集型
- 默认有 7 个工作线程，可能同时处理多条道路
- 在低配置电脑上可能导致卡顿或延迟

### 解决方案
通过限制**同时进行的生成任务数量**，让玩家根据自己的硬件配置调整性能开销。

---

## ⚙️ 配置选项

### 参数详情

| 属性 | 值 |
|------|-----|
| **配置键** | `maxConcurrentRoadGeneration` |
| **分类** | `pre-generation` (预生成设置) |
| **默认值** | `3` |
| **范围** | `1 - 10` |
| **类型** | 整数 |

### 中文名称
**同时生成道路数量上限**

### 配置说明
```
数值越小 = 性能占用越低 + 道路生成越慢
数值越大 = 性能占用越高 + 道路生成越快
```

---

## 🔧 技术实现

### 1. 配置定义

**文件**: `ModConfig.java`

```java
@Entry(category = "pre-generation", min = 1, max = 10)
public static int maxConcurrentRoadGeneration = 3;
```

### 2. 并发控制逻辑

**文件**: `ModEventHandler.java`

#### 核心改进

**改进前**:
```java
// 问题：每个世界只能有一个任务，无法真正并发
runningTasks.put(serverWorld.getRegistryKey().getValue().toString(), future);
```

**改进后**:
```java
// 1. 清理已完成的任务
runningTasks.entrySet().removeIf(entry -> entry.getValue().isDone());

// 2. 检查是否达到并发上限
if (runningTasks.size() >= ModConfig.maxConcurrentRoadGeneration) {
    return;
}

// 3. 使用唯一任务ID，允许真正的并发
String taskId = worldId + "_" + System.nanoTime();
Future<?> future = executor.submit(() -> {
    try {
        new Road(...).generateRoad(steps);
    } finally {
        runningTasks.remove(taskId);  // 自动清理
    }
});
runningTasks.put(taskId, future);
```

### 3. 翻译支持

**中文** (`zh_cn.json`):
```json
{
  "settlement-roads.midnightconfig.maxConcurrentRoadGeneration": "同时生成道路数量上限",
  "settlement-roads.midnightconfig.maxConcurrentRoadGeneration.tooltip": "同时生成的道路任务数量上限。数值越小性能占用越低，但道路生成速度会变慢。默认：3"
}
```

**英文** (`en_us.json`):
```json
{
  "settlement-roads.midnightconfig.maxConcurrentRoadGeneration": "Max Concurrent Road Generation",
  "settlement-roads.midnightconfig.maxConcurrentRoadGeneration.tooltip": "Maximum number of roads that can be generated simultaneously. Lower values reduce performance impact but slow down road generation. Default: 3"
}
```

---

## 📊 性能影响分析

### 不同配置值的效果

| 配置值 | CPU 占用 | 内存占用 | 生成速度 | 适用场景 |
|--------|----------|----------|----------|----------|
| **1** | 🟢 最低 | 🟢 最低 | 🔴 很慢 | 低配置电脑、服务器 |
| **2** | 🟡 低 | 🟡 低 | 🟡 较慢 | 中低配置 |
| **3** | 🟡 中 | 🟡 中 | 🟢 正常 | **默认推荐** |
| **5** | 🟠 较高 | 🟠 较高 | 🟢 快 | 高配置单人游戏 |
| **7** | 🔴 高 | 🔴 高 | 🟢 很快 | 高配置或专用服务器 |
| **10** | 🔴 很高 | 🔴 很高 | 🟢 极快 | 极高配置 |

### 性能估算

假设单个道路生成任务：
- CPU: ~15-30% 单核心
- 内存: ~50-100 MB
- 时间: 1-10 秒（取决于距离和地形）

**并发数量为 3 时**:
- CPU: ~45-90% 单核心（分布在多核）
- 内存: ~150-300 MB
- 并发效率: 3x 吞吐量

---

## 🎮 使用建议

### 低配置电脑 (4GB RAM, 双核)
```
推荐值: 1-2
说明: 避免卡顿，确保游戏流畅
```

### 中等配置 (8GB RAM, 四核)
```
推荐值: 3 (默认)
说明: 平衡性能和速度
```

### 高配置 (16GB+ RAM, 6核+)
```
推荐值: 5-7
说明: 充分利用硬件，快速生成道路网络
```

### 专用服务器
```
推荐值: 7-10
说明: 最大化生成速度，服务器通常有更好的硬件
```

---

## 🔍 技术细节

### 任务管理机制

#### 1. 任务 ID 生成
```java
String taskId = worldId + "_" + System.nanoTime();
```
- 使用纳秒时间戳保证唯一性
- 允许同一世界多个任务并发

#### 2. 任务清理
```java
runningTasks.entrySet().removeIf(entry -> entry.getValue().isDone());
```
- 每次 tick 自动清理已完成的任务
- 避免内存泄漏

#### 3. 并发限制
```java
if (runningTasks.size() >= ModConfig.maxConcurrentRoadGeneration) {
    return;
}
```
- 简单高效的限流机制
- 实时响应配置变化

#### 4. 任务自清理
```java
finally {
    runningTasks.remove(taskId);
}
```
- 任务完成或异常时自动移除
- 保证资源释放

---

## 🐛 已知问题和注意事项

### 1. 配置热重载
- ✅ 配置更改**立即生效**
- ✅ 无需重启游戏
- ⚠️ 已在运行的任务不会被取消

### 2. 队列积压
```
场景: 设置为 1，但有 100 个待生成连接
结果: 队列会排队，逐个生成
建议: 增大并发数或减少结构数量
```

### 3. 线程池大小
```
当前线程池: 7 个线程
最大并发: 10 个任务

说明: 即使设置为 10，实际并发受线程池限制
      7 个任务会立即执行，3 个会排队
```

### 4. 服务器性能
```
多人服务器建议:
- 根据玩家数量调整
- 监控 TPS (每秒 Tick 数)
- 如果 TPS < 18，降低并发数
```

---

## 📈 监控和调试

### 日志输出
```
[settlement-roads] DEBUG: Active road generation tasks: 3/3
[settlement-roads] DEBUG: Queue size: 12
[settlement-roads] DEBUG: Completed roads: 45
```

### 调试地图
按 `H` 键打开调试地图，查看：
- **Generating**: 当前正在生成的连接数量
- **Planned**: 等待生成的连接数量

### 性能分析
```
1. 使用 F3 查看 TPS
2. 使用 /forge tps 查看服务器性能
3. 观察内存使用（F3 右侧）
4. 监控 CPU 使用率（任务管理器）
```

---

## 🚀 未来改进方向

### 短期
- [ ] 添加动态调整（根据 TPS 自动降低并发）
- [ ] 添加优先级队列（玩家附近优先）
- [ ] 显示实时生成进度

### 中期
- [ ] 分离远程和近距离道路的并发限制
- [ ] 添加调度策略配置（FIFO/优先级/距离）
- [ ] CPU 使用率监控和自适应调整

### 长期
- [ ] 异步区块加载优化
- [ ] GPU 加速 A* 算法（CUDA/OpenCL）
- [ ] 分布式生成（多服务器协作）

---

## 📚 相关配置

这个配置与以下配置协同工作：

### 结构定位
- `maxLocatingCount`: 影响需要生成的道路总数
- `initialLocatingCount`: 影响初始队列大小

### 道路复杂度
- `maxHeightDifference`: 影响 A* 计算复杂度
- `maxTerrainStability`: 影响计算时间

### 建议组合

**快速生成 + 低配置**:
```
maxConcurrentRoadGeneration = 1
maxLocatingCount = 20
maxHeightDifference = 3
```

**平衡配置**:
```
maxConcurrentRoadGeneration = 3  (默认)
maxLocatingCount = 100
maxHeightDifference = 5
```

**极速生成 + 高配置**:
```
maxConcurrentRoadGeneration = 7
maxLocatingCount = 200
maxHeightDifference = 7
```

---

## 📖 代码示例

### 手动触发生成（开发者）
```java
// 检查当前并发数
int current = ModEventHandler.getActiveTaskCount();
int max = ModConfig.maxConcurrentRoadGeneration;

if (current < max) {
    // 可以触发新任务
    ModEventHandler.tryGenerateNewRoads(serverWorld, true, 5000);
}
```

### 配置读取（模组兼容）
```java
// 其他模组可以读取配置
int maxConcurrent = ModConfig.maxConcurrentRoadGeneration;
LOGGER.info("Settlement Roads max concurrent: " + maxConcurrent);
```

---

## 🎉 总结

### 优势
✅ **灵活控制**: 玩家自主选择性能/速度平衡  
✅ **即时生效**: 无需重启游戏  
✅ **智能管理**: 自动清理和限流  
✅ **完整翻译**: 中英文全面支持  
✅ **易于理解**: 清晰的配置说明

### 适用场景
- 🖥️ 单人游戏性能优化
- 🌐 多人服务器负载控制
- 🎮 视频录制/直播（避免卡顿）
- 🔧 模组包整合（平衡资源）

---

**版本**: v2.1.0  
**日期**: 2025-10-01  
**作者**: Settlement Roads Team
