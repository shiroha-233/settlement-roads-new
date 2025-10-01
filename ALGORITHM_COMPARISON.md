# 算法深度对比与优化建议

## 目录
1. [A* 寻路算法详解](#a-寻路算法详解)
2. [地形避障策略](#地形避障策略)
3. [路径优化技术](#路径优化技术)
4. [性能优化方案](#性能优化方案)
5. [混合优化建议](#混合优化建议)

---

## A* 寻路算法详解

### 1. 基础 A* 实现 (Settlement Roads)

#### 核心数据结构
```java
class Node {
    BlockPos pos;        // 当前位置
    Node parent;         // 父节点 (用于路径重建)
    double gScore;       // 从起点到当前点的实际代价
    double fScore;       // f = g + h (总估计代价)
}

PriorityQueue<Node> openSet;           // 待探索节点 (按fScore排序)
Map<BlockPos, Node> allNodes;          // 所有已创建的节点
Set<BlockPos> closedSet;               // 已探索节点
```

#### 算法流程
```java
1. 初始化:
   - 将起点加入 openSet
   - gScore[start] = 0
   - fScore[start] = heuristic(start, goal)

2. 主循环 (while openSet不为空 && 未达到maxSteps):
   a. 从 openSet 取出 fScore 最小的节点 current
   
   b. 目标检测:
      if (distance(current, goal) < threshold)
          return reconstructPath(current)
   
   c. 标记为已探索:
      closedSet.add(current)
   
   d. 扩展邻居:
      for each neighbor of current:
          if (neighbor in closedSet) continue
          
          // 计算代价
          tentativeG = current.gScore + stepCost + elevationCost + biomeCost + ...
          
          // 更新或创建节点
          if (tentativeG < neighbor.gScore):
              neighbor.parent = current
              neighbor.gScore = tentativeG
              neighbor.fScore = tentativeG + heuristic(neighbor, goal)
              openSet.add(neighbor)

3. 失败: 返回空路径
```

#### 启发式函数 (Octile Distance)
```java
// 为什么使用 Octile Distance?
// - 支持8方向移动 (4正交 + 4对角)
// - 比曼哈顿距离更准确
// - 比欧几里得距离计算更快

double heuristic(BlockPos a, BlockPos b) {
    int dx = abs(a.x - b.x);
    int dz = abs(a.z - b.z);
    
    // Octile 公式推导:
    // 假设对角移动代价 = √2 ≈ 1.414
    // 正交移动代价 = 1
    // 最优路径 = min(dx,dz) 次对角 + |dx-dz| 次正交
    // = min(dx,dz) * √2 + |dx-dz| * 1
    // = max(dx,dz) + (√2-1) * min(dx,dz)
    // ≈ max(dx,dz) + 0.414 * min(dx,dz)
    
    // Settlement Roads 使用简化版本:
    double octile = dx + dz - 0.6 * min(dx, dz);
    return octile * 30;  // 缩放因子
}
```

### 2. 加权 A* / ARA* (RoadArchitect)

#### 理论基础
```
标准 A*: f(n) = g(n) + h(n)
加权 A*: f(n) = g(n) + ε * h(n)  其中 ε > 1

效果:
- ε = 1.0: 标准 A*, 保证最优解
- ε > 1.0: 更快但可能不是最优解
- ε 越大: 越接近贪心搜索 (只看启发式)

RoadArchitect 使用 ε = 1.5:
- 速度提升约 40-60%
- 路径长度增加约 5-15%
- 对于道路生成来说是很好的权衡
```

#### 自适应启发式尺度
```java
// 问题: 固定的启发式尺度对所有距离都一样
// 短距离: 可能过于激进，错过更好的路径
// 长距离: 可能过于保守，浪费计算

// 解决方案: 根据起点到终点的距离动态调整
double selectHeuristicScale(BlockPos start, BlockPos goal) {
    int L1 = abs(start.x - goal.x) + abs(start.z - goal.z);
    
    // 定义距离范围
    final double SHORT = 200.0;   // 短距离阈值
    final double LONG = 1200.0;   // 长距离阈值
    
    // 归一化到 [0, 1]
    double t = (L1 - SHORT) / (LONG - SHORT);
    t = clamp(t, 0.0, 1.0);
    
    // Smoothstep 插值 (平滑过渡)
    double s = t * t * (3.0 - 2.0 * t);
    
    // 映射到 [80, 120]
    double scale = 80.0 + s * 40.0;
    
    return scale;
}

// 为什么这样设计?
// 1. 短距离 (scale=80): 更保守，探索更多选项
// 2. 长距离 (scale=120): 更激进，快速接近目标
// 3. Smoothstep: 避免突变，平滑过渡
```

#### 动态步数限制
```java
// 问题: 固定的 maxSteps 对所有距离都一样
// 短距离: 浪费配额
// 长距离: 配额不足，过早失败

// 解决方案: 根据距离动态分配
int selectMaxSteps(BlockPos start, BlockPos goal) {
    int L1 = abs(start.x - goal.x) + abs(start.z - goal.z);
    
    // 经验公式: 每个网格单位分配 16 步
    double k = 16.0;
    long estimate = (long)(k * L1 / GRID_STEP);
    
    // 边界保护
    int MIN = 512;        // 最小值 (避免过早终止)
    int MAX = 200_000;    // 最大值 (防止无限循环)
    
    return clamp(estimate, MIN, MAX);
}

// 实际效果:
// - 100格距离: ~400 步
// - 500格距离: ~2000 步
// - 2000格距离: ~8000 步
```

#### 部分路径接受
```java
// 问题: 有时无法到达目标 (被山脉、海洋阻挡)
// 传统做法: 返回失败，浪费所有计算

// 解决方案: 如果接近目标，接受部分路径
while (!openSet.isEmpty() && iterations < maxSteps) {
    Node current = openSet.poll();
    
    // 跟踪最佳进度
    int currentDist = manhattanDistance(current.pos, goal);
    if (currentDist < bestDistance) {
        bestDistance = currentDist;
        bestNode = current;
    }
    
    // ... 正常的 A* 逻辑 ...
}

// 如果未找到完整路径
if (!foundPath) {
    int initialDist = manhattanDistance(start, goal);
    double progress = (initialDist - bestDistance) / (double)initialDist;
    
    // 如果进度 >= 60%, 接受部分路径
    if (progress >= 0.6) {
        return reconstructPath(bestNode);
    }
}

// 实际应用:
// - 目标在山的另一边: 返回到山脚的路径
// - 目标跨越海洋: 返回到海岸的路径
// - 后续可以手动连接或重新规划
```

### 3. 启发式函数对比

#### Settlement Roads
```java
h(a,b) = (|dx| + |dz| - 0.6 * min(|dx|, |dz|)) * 30

优点:
- 简单直接
- 计算快速
- 对大多数情况足够准确

缺点:
- 固定缩放因子 (30) 可能不适合所有场景
- 不考虑地形复杂度
```

#### RoadArchitect
```java
h(a,b) = (|dx| + |dz| - 0.5 * min(|dx|, |dz|)) * scale(L1)

优点:
- 自适应缩放
- 更准确的 Octile 系数 (0.5 vs 0.6)
- 考虑查询距离

缺点:
- 稍微复杂一点
- 需要额外的距离计算
```

#### 数学验证
```
对角移动代价 = √2 ≈ 1.414
理论 Octile 系数 = √2 - 1 ≈ 0.414

Settlement Roads: 0.6 (偏高，更保守)
RoadArchitect: 0.5 (接近理论值)

示例: 从 (0,0) 到 (100,100)
- 真实最优路径: 100 次对角 = 141.4
- Settlement Roads: (100+100-0.6*100)*30 = 4200
- RoadArchitect: (100+100-0.5*100)*scale = 150*scale
  - 如果 scale=95: 14250
  - 归一化后都是合理的估计
```

---

## 地形避障策略

### 1. 高度限制

#### Settlement Roads
```java
// 简单但有效的方法
int elevation = abs(neighbor.y - current.y);
if (elevation > 3) {
    continue;  // 拒绝这个邻居
}

// 为什么是 3?
// - Minecraft 玩家可以跳1格
// - 马可以跳2-3格
// - 3格是合理的"可通行"阈值
// - 更大的值会导致陡峭的道路
```

#### RoadArchitect
```java
// 相同的策略
if (isSteep(current.y, neighbor.y)) {
    continue;
}

static boolean isSteep(int y1, int y2) {
    return abs(y1 - y2) > 3;
}

// 两者一致，说明这是经过验证的好方法
```

### 2. 地形稳定性检查

#### Settlement Roads - 基础版本
```java
int calculateTerrainStability(BlockPos pos, int y, ServerWorld world) {
    int cost = 0;
    
    // 检查4个基本方向
    for (Direction dir : Direction.Type.HORIZONTAL) {
        BlockPos testPos = pos.offset(dir);
        int testY = heightSampler(testPos.x, testPos.z, world);
        
        int elevation = abs(y - testY);
        cost += elevation;
        
        // 早期退出优化
        if (cost > 2) {
            return Integer.MAX_VALUE;  // 标记为不可通行
        }
    }
    
    return cost;
}

// 代价计算:
// cost * 16 加入总代价
// 如果 cost > 2: 完全拒绝

// 示例:
// 平坦地形: cost = 0, 无额外代价
// 轻微起伏: cost = 1-2, 代价 16-32
// 陡峭地形: cost > 2, 不可通行
```

#### RoadArchitect - 增强版本
```java
double stabilityCost(ServerWorld world, int x, int z, int y) {
    // 阶段1: 局部陡峭度 (与 Settlement Roads 类似)
    int local = 0;
    for (Direction d : Direction.Type.HORIZONTAL) {
        int ny = getHeight(world, x + d.offsetX, z + d.offsetZ);
        local += abs(y - ny);
        if (local > 3) {  // 注意: 阈值是 3 而不是 2
            return Double.MAX_VALUE;
        }
    }
    
    double baseCost = local * 16.0;
    
    // 阶段2: 区域粗糙度分析 (新增)
    if (!config.terrainAnalyzerEnabled()) {
        return baseCost;
    }
    
    // 计算更大范围内的高度变化
    int radius = config.terrainRoughRadius();  // 默认 12-16
    int stride = config.terrainRoughStride();  // 默认 4
    
    int minHeight = Integer.MAX_VALUE;
    int maxHeight = Integer.MIN_VALUE;
    
    for (int dx = -radius; dx <= radius; dx += stride) {
        for (int dz = -radius; dz <= radius; dz += stride) {
            int h = getHeight(world, x + dx, z + dz);
            minHeight = min(minHeight, h);
            maxHeight = max(maxHeight, h);
        }
    }
    
    int range = maxHeight - minHeight;
    
    // 粗糙度惩罚
    int threshold = config.terrainRangeThreshold();  // 默认 12
    double scale = config.terrainPenaltyScale();     // 默认 12.0
    
    double roughnessPenalty = 0.0;
    if (range > threshold) {
        roughnessPenalty = (range - threshold) * scale;
    }
    
    return baseCost + roughnessPenalty;
}

// 效果:
// 1. 局部检查: 确保立即周围可通行
// 2. 区域检查: 识别"山地区域"
// 3. 惩罚机制: 让道路绕过山地，而不是穿过

// 示例:
// 平原 (range=2): baseCost + 0 = 0-48
// 丘陵 (range=15): baseCost + (15-12)*12 = 0-48 + 36 = 36-84
// 山地 (range=40): baseCost + (40-12)*12 = 0-48 + 336 = 336-384
```

### 3. 生物群系代价

#### Settlement Roads
```java
RegistryEntry<Biome> biome = biomeSampler(neighbor, world);

int biomeCost = 0;
if (biome.isIn(BiomeTags.IS_RIVER)) biomeCost = 50;
if (biome.isIn(BiomeTags.IS_OCEAN)) biomeCost = 50;
if (biome.isIn(BiomeTags.IS_DEEP_OCEAN)) biomeCost = 50;

// 最终代价: biomeCost * 8 = 400

// 特点:
// - 简单统一的代价
// - 不禁止通过水域，只是增加代价
// - 适合有桥梁或浮标的道路系统
```

#### RoadArchitect
```java
// 预定义的生物群系代价表
static final Map<TagKey<Biome>, Double> BIOME_COSTS = Map.of(
    BiomeTags.IS_RIVER, 240.0,
    BiomeTags.IS_OCEAN, 280.0,
    BiomeTags.IS_DEEP_OCEAN, 320.0,
    BiomeTags.IS_MOUNTAIN, 160.0,
    BiomeTags.IS_BEACH, 160.0
);

double biomeCost(RegistryEntry<Biome> biome) {
    for (var entry : BIOME_COSTS.entrySet()) {
        if (biome.isIn(entry.getKey())) {
            return entry.getValue();
        }
    }
    return 0.0;
}

// 额外: 禁止的生物群系
List<String> forbiddenBiomeSelectors = config.forbiddenBiomeSelectors();
// 默认: ["#minecraft:is_ocean", "#minecraft:is_deep_ocean"]

if (isForbiddenBiome(biome)) {
    continue;  // 完全拒绝
}

// 额外: 邻近惩罚
double forbiddenProximityPenalty(int x, int z, int y) {
    int buffer = config.forbiddenBiomeBufferBlocks();  // 默认 16
    
    // 检查缓冲区内是否有禁止的生物群系
    for (int dx = -buffer; dx <= buffer; dx += GRID_STEP) {
        for (int dz = -buffer; dz <= buffer; dz += GRID_STEP) {
            if (isForbiddenBiome(sampleBiome(x+dx, z+dz, y))) {
                return config.forbiddenBiomeProximityPenalty();  // 默认 200
            }
        }
    }
    return 0.0;
}

// 效果:
// 1. 差异化代价: 深海 > 海洋 > 河流
// 2. 可配置的禁止列表
// 3. 缓冲区机制: 避免靠近禁止区域
```

### 4. 水域与海岸处理

#### RoadArchitect 独有功能
```java
// 配置选项
boolean preferLandOverWater = config.preferLandOverWater();
double waterStepPenalty = config.waterStepPenalty();  // 默认 150
int coastBuffer = config.coastAvoidBufferBlocks();    // 默认 16
double coastPenalty = config.coastProximityPenalty(); // 默认 100

// 水上行走惩罚
if (preferLandOverWater && isWater(biome)) {
    cost += waterStepPenalty;
}

// 海岸邻近惩罚
double coastProximityPenalty(int x, int z, int y) {
    if (!preferLandOverWater) return 0.0;
    
    // 检查周围是否有水
    for (int dx = -coastBuffer; dx <= coastBuffer; dx += GRID_STEP) {
        for (int dz = -coastBuffer; dz <= coastBuffer; dz += GRID_STEP) {
            if (isWater(sampleBiome(x+dx, z+dz, y))) {
                return coastPenalty;
            }
        }
    }
    return 0.0;
}

// 效果:
// - 道路优先选择陆地路线
// - 避免沿着海岸线紧贴
// - 如果必须跨水，会选择最短路径
```

### 5. Y 层级惩罚

#### 两者都有
```java
// Settlement Roads
int yLevelCost = (y == 62) ? 20 : 0;
cost += yLevelCost * 8;  // = 160

// RoadArchitect
double yLevelCost(int y) {
    return (y <= 63) ? 240.0 : 0.0;
}

// 目的: 避免在海平面或以下建造道路
// 62-63 是 Minecraft 的海平面
```

---

## 路径优化技术

### 1. 路径重建与插值

#### Settlement Roads
```java
// 重建路径
List<Node> pathNodes = new ArrayList<>();
Node current = endNode;
while (current != null) {
    pathNodes.add(current);
    current = current.parent;
}
Collections.reverse(pathNodes);

// 插值 (在网格点之间填充)
Map<BlockPos, List<BlockPos>> interpolatedSegments;

for (int i = 1; i < neighborDistance; i++) {
    int interpX = current.x + (offset[0] * i) / neighborDistance;
    int interpZ = current.z + (offset[1] * i) / neighborDistance;
    BlockPos interpolated = new BlockPos(interpX, current.y, interpZ);
    segmentPoints.add(interpolated);
}

// 结果: 平滑的路径，每个方块都有一个点
```

#### RoadArchitect
```java
// 阶段1: 重建网格顶点
List<BlockPos> vertices = reconstructVertices(goal, start, parent);

// 阶段2: 细化 (refine)
List<BlockPos> refine(ServerWorld world, List<BlockPos> verts) {
    List<BlockPos> out = new ArrayList<>();
    
    for (int i = 0; i < verts.size() - 1; i++) {
        BlockPos a = verts.get(i);
        BlockPos b = verts.get(i + 1);
        
        out.add(a.down());  // 下移1格
        
        // 插值
        int dx = signum(b.x - a.x);
        int dz = signum(b.z - a.z);
        int steps = max(abs(b.x - a.x), abs(b.z - a.z));
        
        for (int j = 1; j < steps; j++) {
            int nx = a.x + dx * j;
            int nz = a.z + dz * j;
            int ny = getHeight(world, nx, nz) - 1;
            out.add(new BlockPos(nx, ny, nz));
        }
    }
    
    out.add(verts.getLast().down());
    return out;
}

// 关键区别:
// - 每个插值点重新采样高度
// - 所有点下移1格 (放置在表面下)
```

### 2. 高度平滑

#### Settlement Roads - 简单平均
```java
int averagingRadius = config.averagingRadius();  // 默认 2-5

// 收集样本
List<Double> heights = new ArrayList<>();
for (int j = i - averagingRadius; j <= i + averagingRadius; j++) {
    if (j >= 0 && j < path.size()) {
        BlockPos sample = path.get(j);
        double y = world.getTopY(WORLD_SURFACE_WG, sample.x, sample.z);
        heights.add(y);
    }
}

// 计算平均
int averageY = (int)Math.round(
    heights.stream().mapToDouble(Double::doubleValue).average().orElse(y)
);

// 在平均高度放置道路
placeRoad(world, pos.withY(averageY), material);

// 优点: 简单有效
// 缺点: 可能产生突变和尖峰
```

#### RoadArchitect - 多层平滑
```java
NormalizeResult normalizeHeights(List<BlockPos> refined) {
    int n = refined.size();
    int[] y = new int[n];
    for (int i = 0; i < n; i++) y[i] = refined.get(i).getY();
    
    for (int pass = 0; pass < SMOOTH_PASSES; pass++) {  // 默认 2 次
        
        // 步骤1: 中值滤波
        int window = SMOOTH_MEDIAN_WINDOW;  // 默认 5
        int radius = window / 2;
        
        int[] tmp = new int[n];
        for (int i = 0; i < n; i++) {
            // 收集窗口内的值
            int[] samples = new int[window];
            int k = 0;
            for (int j = max(0, i-radius); j <= min(n-1, i+radius); j++) {
                samples[k++] = y[j];
            }
            // 填充边界
            while (k < window) {
                samples[k++] = (i < radius) ? y[0] : y[n-1];
            }
            // 取中值
            Arrays.sort(samples);
            tmp[i] = samples[window / 2];
        }
        y = tmp;
        
        // 步骤2: 梯度限制 (前向)
        int maxGrad = SMOOTH_GRAD_MAX;  // 默认 1
        for (int i = 1; i < n; i++) {
            int lo = y[i-1] - maxGrad;
            int hi = y[i-1] + maxGrad;
            y[i] = clamp(y[i], lo, hi);
        }
        
        // 步骤3: 梯度限制 (后向)
        for (int i = n-2; i >= 0; i--) {
            int lo = y[i+1] - maxGrad;
            int hi = y[i+1] + maxGrad;
            y[i] = clamp(y[i], lo, hi);
        }
        
        // 步骤4: 尖峰检测与修剪
        int spikeDelta = DESPIKE_DELTA;  // 默认 2
        for (int i = 1; i < n-1; i++) {
            int maxNeighbor = max(y[i-1], y[i+1]);
            if (y[i] - maxNeighbor >= spikeDelta) {
                y[i] = (y[i-1] + y[i+1]) / 2;  // 替换为平均值
            }
        }
    }
    
    // 重建路径
    List<BlockPos> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
        out.add(new BlockPos(refined.get(i).x, y[i], refined.get(i).z));
    }
    return out;
}

// 效果:
// 1. 中值滤波: 消除噪声
// 2. 梯度限制: 确保相邻方块高度差 ≤ 1
// 3. 尖峰修剪: 消除单点突起
// 4. 多次迭代: 逐步收敛到平滑曲线
```

### 3. Y 型合并 (RoadArchitect 独有)

#### 问题
```
当两条道路几乎平行且靠近时:
- 如果不处理: 会有两条独立的道路
- 视觉效果差: 看起来像错误
- 浪费资源: 两条路做同样的事
```

#### 解决方案
```java
// 步骤1: 检测平行道路
MergeCandidate findBestParallelPartner(PathStorage storage, 
                                       String baseKey, 
                                       List<BlockPos> basePath) {
    for (String otherKey : storage.getPendingPaths()) {
        List<BlockPos> otherPath = storage.getPath(otherKey);
        
        // 检查1: 角度
        double angle = angleBetween(basePath, otherPath);
        if (angle >= ANGLE_THRESHOLD_DEG) continue;  // 默认 35°
        
        // 检查2: 距离
        double minDist = minPointToPointDistance(basePath, otherPath);
        if (minDist >= TOLERANCE_BLOCKS) continue;  // 默认 45
        
        // 计算得分
        double score = angle + minDist * 0.5;
        // 选择得分最低的
    }
}

// 步骤2: 找到会合点
Convergence findConvergence(List<BlockPos> pathA, List<BlockPos> pathB) {
    for (int i = 1; i < pathA.size() - 1; i++) {
        // 找到 pathB 上最近的点
        int j = findClosestPoint(pathA.get(i), pathB);
        
        // 检查尾部是否继续平行
        List<BlockPos> tailA = pathA.subList(i, pathA.size());
        List<BlockPos> tailB = pathB.subList(j, pathB.size());
        
        double tailAngle = angleBetween(tailA, tailB);
        double tailDist = avgDistance(tailA, tailB);
        
        if (tailAngle < TAIL_ANGLE_MAX_DEG &&  // 默认 35°
            tailDist < TOLERANCE * 1.5) {       // 默认 67.5
            return new Convergence(i, j);
        }
    }
    return null;
}

// 步骤3: 构建 Y 型结构
BuildResult buildY(String keyA, List<BlockPos> pathA,
                   String keyB, List<BlockPos> pathB,
                   Convergence conv) {
    BlockPos pA = pathA.get(conv.i);
    BlockPos pB = pathB.get(conv.j);
    
    // 创建交叉点 (中点)
    BlockPos J = new BlockPos(
        (pA.x + pB.x) / 2,
        getHeight(world, (pA.x + pB.x) / 2, (pA.z + pB.z) / 2),
        (pA.z + pB.z) / 2
    );
    
    // 分离为3段
    List<BlockPos> legA = pathA.subList(0, conv.i);
    legA.add(J);
    
    List<BlockPos> legB = pathB.subList(0, conv.j);
    legB.add(J);
    
    // 选择较长的尾部作为主干
    List<BlockPos> trunk;
    if (pathA.size() - conv.i > pathB.size() - conv.j) {
        trunk = pathA.subList(conv.i, pathA.size());
    } else {
        trunk = pathB.subList(conv.j, pathB.size());
    }
    trunk.set(0, J);
    
    return new BuildResult(legA, legB, trunk);
}

// 步骤4: 迭代合并
// trunk 可以继续与其他路径合并
// 最多迭代 MAX_ITER 次 (默认 5)
```

#### 效果示例
```
之前:
  A =============>
  B =============>

之后:
  A ====\
         >=======>
  B ====/

视觉上更自然，像真实的道路交叉口
```

### 4. 端点修剪

#### Settlement Roads
```java
// 硬编码的偏移
if (segmentIndex < 60 || segmentIndex > segmentList.size() - 60) {
    continue;  // 跳过前后60个点
}

// 目的: 避免道路直接进入结构内部
```

#### RoadArchitect
```java
List<BlockPos> trimByManhattan(List<BlockPos> path) {
    int TRIM_RADIUS = 50;  // L1 距离
    
    BlockPos start = path.getFirst();
    BlockPos end = path.getLast();
    
    // 从起点修剪
    int i = 0;
    while (i < path.size() && 
           manhattanXZ(path.get(i), start) <= TRIM_RADIUS) {
        i++;
    }
    
    // 从终点修剪
    int j = path.size() - 1;
    while (j >= 0 && 
           manhattanXZ(path.get(j), end) <= TRIM_RADIUS) {
        j--;
    }
    
    // 如果修剪后太短，保留原路径
    if (j - i + 1 < 2) {
        return path;
    }
    
    return path.subList(i, j + 1);
}

// 优点: 基于实际距离而不是点数
```

---

## 性能优化方案

### 1. 缓存系统

#### Settlement Roads - 简单缓存
```java
// 高度缓存
public static final Map<Long, Integer> heightCache = new ConcurrentHashMap<>();

private static long hashXZ(int x, int z) {
    return ((long)x << 32) | (z & 0xFFFFFFFFL);
}

private static int heightSampler(int x, int z, ServerWorld world) {
    long key = hashXZ(x, z);
    return heightCache.computeIfAbsent(key, k -> 
        world.getChunkManager()
            .getChunkGenerator()
            .getHeightInGround(x, z, WORLD_SURFACE_WG, world, noiseConfig)
    );
}

// 清理策略
if (heightCache.size() > 100_000) {
    heightCache.clear();
}

// 优点: 简单有效
// 缺点: 
// - 不持久化 (重启后丢失)
// - 硬性大小限制
// - 只缓存高度
```

#### RoadArchitect - 持久化缓存
```java
// 三层缓存
class CacheStorage extends PersistentState {
    private final Long2IntMap heights = new Long2IntOpenHashMap();
    private final Long2ObjectMap<RegistryEntry<Biome>> biomes = new Long2ObjectOpenHashMap<>();
    private final Long2DoubleMap stabilities = new Long2DoubleOpenHashMap();
    
    // 持久化
    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        // 序列化所有缓存
        nbt.put("heights", serializeHeights());
        nbt.put("biomes", serializeBiomes());
        nbt.put("stabilities", serializeStabilities());
        return nbt;
    }
    
    @Override
    public void readNbt(NbtCompound nbt) {
        // 反序列化
        deserializeHeights(nbt.getCompound("heights"));
        deserializeBiomes(nbt.getCompound("biomes"));
        deserializeStabilities(nbt.getCompound("stabilities"));
    }
}

// 生命周期管理
public static void onWorldLoad(ServerWorld world) {
    CacheStorage storage = CacheStorage.get(world);
    STATES.put(world.getRegistryKey(), storage);
}

public static void onWorldUnload(ServerWorld world) {
    CacheStorage storage = STATES.remove(world.getRegistryKey());
    if (storage != null) {
        storage.markDirty();  // 触发保存
    }
}

// 优点:
// - 跨会话保留
// - 多类型缓存
// - 按需增长
// 缺点:
// - 更复杂
// - 磁盘 I/O
```

### 2. 并行化

#### Settlement Roads - 无并行化
```java
// 所有操作都在主线程
public void generateRoad(int maxSteps) {
    List<RoadSegmentPlacement> path = 
        RoadPathCalculator.calculateAStarRoadPath(start, end, width, world, maxSteps);
    // 直接保存
    serverWorld.setAttached(WorldDataAttachment.ROAD_DATA_LIST, roadDataList);
}
```

#### RoadArchitect - 多级并行
```java
// 1. 结构扫描并行
List<Candidate> planned = ForkJoinPool.commonPool().submit(() ->
    cells.parallelStream()
        .flatMap(cell -> planCandidatesForCell(index, cell, scanRadius))
        .collect(Collectors.toList())
).join();

// 2. 寻路并行
List<CompletableFuture<PathJob>> futures = new ArrayList<>();
for (String edgeId : newEdges) {
    CompletableFuture<PathJob> job = AsyncExecutor.submit(() -> {
        List<BlockPos> path = finder.findPath(from, to);
        return new PathJob(edgeId, path);
    });
    futures.add(job);
}
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

// 3. 后处理异步
AsyncExecutor.execute(() -> {
    // 路径细化、平滑、合并
    // 完成后提交到主线程
    server.execute(() -> {
        storage.updatePath(key, processedPath, READY);
    });
});

// 优点: 充分利用多核 CPU
// 缺点: 需要仔细管理线程安全
```

### 3. 早期退出优化

#### 两者都使用
```java
// Settlement Roads
int cost = 0;
for (Direction dir : HORIZONTAL) {
    cost += elevation;
    if (cost > 2) {
        return Integer.MAX_VALUE;  // 立即退出
    }
}

// RoadArchitect
int local = 0;
for (Direction d : HORIZONTAL) {
    local += abs(y - ny);
    if (local > 3) {
        return Double.MAX_VALUE;  // 立即退出
    }
}

// 效果: 避免不必要的计算
```

### 4. 空间分区

#### RoadArchitect - 区块索引
```java
// 按区块组织道路段
class RoadBuilderStorage {
    private final Map<ChunkPos, List<SegmentEntry>> segments = new HashMap<>();
    
    void addSegment(ChunkPos chunk, String pathKey, int start, int end) {
        segments.computeIfAbsent(chunk, k -> new ArrayList<>())
                .add(new SegmentEntry(pathKey, start, end));
    }
    
    List<SegmentEntry> getSegments(ChunkPos chunk) {
        return segments.getOrDefault(chunk, List.of());
    }
}

// 效果: 只处理当前加载的区块
```

---

## 混合优化建议

### 1. 改进 Settlement Roads

#### 建议 1: 添加自适应启发式
```java
// 当前
double heuristic(BlockPos a, BlockPos b) {
    int dx = abs(a.x - b.x);
    int dz = abs(a.z - b.z);
    return (dx + dz - 0.6 * min(dx, dz)) * 30;
}

// 改进
double heuristic(BlockPos a, BlockPos b) {
    int dx = abs(a.x - b.x);
    int dz = abs(a.z - b.z);
    int L1 = dx + dz;
    
    // 自适应缩放
    double scale = 30.0;
    if (L1 < 200) {
        scale = 25.0;  // 短距离更保守
    } else if (L1 > 1000) {
        scale = 35.0;  // 长距离更激进
    }
    
    return (dx + dz - 0.5 * min(dx, dz)) * scale;
}
```

#### 建议 2: 添加地形粗糙度检查
```java
private static int calculateTerrainStability(BlockPos pos, int y, ServerWorld world) {
    // 原有的局部检查
    int localCost = 0;
    for (Direction dir : HORIZONTAL) {
        int testY = heightSampler(testPos.x, testPos.z, world);
        localCost += abs(y - testY);
        if (localCost > 2) return Integer.MAX_VALUE;
    }
    
    // 新增: 区域粗糙度
    int radius = 12;
    int minH = Integer.MAX_VALUE;
    int maxH = Integer.MIN_VALUE;
    
    for (int dx = -radius; dx <= radius; dx += 4) {
        for (int dz = -radius; dz <= radius; dz += 4) {
            int h = heightSampler(pos.x + dx, pos.z + dz, world);
            minH = min(minH, h);
            maxH = max(maxH, h);
        }
    }
    
    int range = maxH - minH;
    int roughnessPenalty = max(0, (range - 12) * 2);
    
    return localCost + roughnessPenalty;
}
```

#### 建议 3: 改进高度平滑
```java
private void placeOnSurface(...) {
    // 当前: 简单平均
    int averageY = (int)Math.round(
        heights.stream().mapToDouble(Double::doubleValue).average().orElse(y)
    );
    
    // 改进: 中值 + 梯度限制
    List<Integer> heights = collectHeights(pos, averagingRadius);
    Collections.sort(heights);
    int medianY = heights.get(heights.size() / 2);  // 中值
    
    // 限制与前一个点的高度差
    if (previousY != null) {
        int maxDiff = 1;
        medianY = clamp(medianY, previousY - maxDiff, previousY + maxDiff);
    }
    
    placeRoad(world, pos.withY(medianY), material);
    previousY = medianY;
}
```

### 2. 简化 RoadArchitect

#### 建议 1: 可选的简化模式
```java
// 配置选项
boolean simpleMode = config.simpleMode();  // 默认 false

if (simpleMode) {
    // 使用简化的地形分析
    return localStabilityCost(x, z, y);  // 只检查局部
} else {
    // 使用完整的地形分析
    return localStabilityCost(x, z, y) + roughnessPenalty(x, z, y);
}
```

#### 建议 2: 可选的 Y 型合并
```java
boolean enableYMerge = config.enableYMerge();  // 默认 true

if (enableYMerge) {
    // 完整的后处理流程
    processPendingWithMerge(world);
} else {
    // 简化流程: 只做细化和平滑
    processPendingSimple(world);
}
```

### 3. 混合最佳实践

#### 推荐配置 - 平衡模式
```java
// 寻路
GRID_STEP = 4
HEURISTIC_WEIGHT = 1.3  // 介于 1.0 和 1.5 之间
maxSteps = dynamicSteps(distance) * 0.8  // 稍微保守

// 地形
enableTerrainAnalyzer = true
terrainRoughRadius = 12
terrainRangeThreshold = 10
terrainPenaltyScale = 10.0

// 水域
preferLandOverWater = true
waterStepPenalty = 150
forbiddenBiomes = ["#minecraft:is_deep_ocean"]  // 只禁止深海

// 后处理
enableYMerge = true
SMOOTH_PASSES = 2
SMOOTH_GRAD_MAX = 1

// 装饰
deterministicDecorations = true
lampInterval = 60
buoyInterval = 18
```

#### 推荐配置 - 性能模式
```java
// 寻路
HEURISTIC_WEIGHT = 2.0  // 更激进
maxSteps = dynamicSteps(distance) * 0.5  // 减少步数

// 地形
enableTerrainAnalyzer = false  // 禁用粗糙度分析
localStabilityOnly = true

// 水域
preferLandOverWater = false  // 允许水路

// 后处理
enableYMerge = false  // 禁用合并
SMOOTH_PASSES = 1

// 装饰
deterministicDecorations = false  // 使用随机
```

#### 推荐配置 - 质量模式
```java
// 寻路
HEURISTIC_WEIGHT = 1.0  // 标准 A*
maxSteps = dynamicSteps(distance) * 1.5  // 更多步数

// 地形
enableTerrainAnalyzer = true
terrainRoughRadius = 16  // 更大范围
terrainRangeThreshold = 8  // 更敏感
terrainPenaltyScale = 15.0  // 更强惩罚

// 水域
preferLandOverWater = true
waterStepPenalty = 300
coastAvoidBufferBlocks = 24
forbiddenBiomes = ["#minecraft:is_ocean", "#minecraft:is_deep_ocean"]

// 后处理
enableYMerge = true
MAX_ITER = 7  // 更多合并迭代
SMOOTH_PASSES = 3
SMOOTH_GRAD_MAX = 1

// 装饰
deterministicDecorations = true
maskErosion = 2
```

---

## 总结

### Settlement Roads 的核心优势
1. **简单直接**: 易于理解和修改
2. **低延迟**: 快速生成道路
3. **低内存**: 最小的缓存开销

### RoadArchitect 的核心优势
1. **高质量**: 更平滑、更自然的道路
2. **智能避障**: 复杂的地形分析
3. **可扩展**: 模块化设计

### 最佳实践建议
1. **小型地图**: 使用 Settlement Roads 或简化的 RoadArchitect
2. **大型地图**: 使用完整的 RoadArchitect
3. **性能优先**: 禁用高级功能，使用更激进的启发式
4. **质量优先**: 启用所有功能，使用更保守的参数

### 未来改进方向
1. **机器学习**: 使用 ML 预测最佳路径
2. **动态调整**: 根据实时性能自动调整参数
3. **多目标优化**: 同时考虑长度、平滑度、美观度
4. **增量更新**: 只重新计算受影响的部分
