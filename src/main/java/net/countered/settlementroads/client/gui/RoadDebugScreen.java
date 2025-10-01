package net.countered.settlementroads.client.gui;

import net.countered.settlementroads.helpers.Records;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * 道路网络调试屏幕
 * 功能: 显示结构节点、道路连接、支持平移/缩放、点击传送
 */
public class RoadDebugScreen extends Screen {

    private static final int RADIUS = 5;
    private static final int PADDING = 20;
    private static final int TARGET_GRID_PX = 80;

    private final List<BlockPos> structures;
    private final List<Records.StructureConnection> connections;
    private final List<Records.RoadData> roads;

    private final Map<BlockPos, ScreenPos> screenPositions = new HashMap<>();
    private final Map<String, Integer> statusColors = Map.of(
            "structure", 0xFF27AE60,   // 绿色 - 结构
            "planned", 0xFFF2C94C,     // 黄色 - 计划中
            "generating", 0xFFE67E22,  // 橙色 - 生成中
            "completed", 0xFF27AE60,   // 绿色 - 已完成（不显示）
            "failed", 0xFFE74C3C,      // 红色 - 生成失败
            "road", 0xFF3498DB         // 蓝色 - 道路
    );

    private boolean dragging = false;
    private boolean firstLayout = true;
    private double zoom = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;
    private double baseScale = 1.0;
    private int minX, maxX, minZ, maxZ;

    public RoadDebugScreen(List<BlockPos> structures, 
                          List<Records.StructureConnection> connections,
                          List<Records.RoadData> roads) {
        super(Text.translatable("gui.settlementroads.debug_map.title"));
        // 创建不可变副本，避免并发修改异常
        this.structures = structures != null ? new ArrayList<>(structures) : new ArrayList<>();
        this.connections = connections != null ? new ArrayList<>(connections) : new ArrayList<>();
        this.roads = roads != null ? new ArrayList<>(roads) : new ArrayList<>();

        if (!this.structures.isEmpty()) {
            minX = this.structures.stream().mapToInt(BlockPos::getX).min().orElse(0);
            maxX = this.structures.stream().mapToInt(BlockPos::getX).max().orElse(0);
            minZ = this.structures.stream().mapToInt(BlockPos::getZ).min().orElse(0);
            maxZ = this.structures.stream().mapToInt(BlockPos::getZ).max().orElse(0);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        computeLayout();

        // 背景面板
        ctx.fill(PADDING, PADDING, width - PADDING, height - PADDING, 0xA0101010);
        ctx.drawBorder(PADDING, PADDING, width - 2 * PADDING, height - 2 * PADDING, 0xFFFFFFFF);

        // 绘制网格
        drawGrid(ctx);

        // 绘制道路路径
        drawRoadPaths(ctx);

        // 绘制连接线（已完成的不显示，因为有实际道路）
        for (Records.StructureConnection conn : connections) {
            // 跳过已完成的连接
            if (conn.status() == Records.ConnectionStatus.COMPLETED) {
                continue;
            }
            
            ScreenPos a = screenPositions.get(conn.from());
            ScreenPos b = screenPositions.get(conn.to());
            if (a == null || b == null) continue;
            
            // 根据状态选择颜色
            int color = switch (conn.status()) {
                case PLANNED -> statusColors.get("planned");
                case GENERATING -> statusColors.get("generating");
                case COMPLETED -> statusColors.get("completed");
                case FAILED -> statusColors.get("failed");
            };
            
            drawLine(ctx, a.x, a.y, b.x, b.y, color);
        }

        // 绘制结构节点
        BlockPos hovered = null;
        for (BlockPos pos : structures) {
            ScreenPos p = screenPositions.get(pos);
            if (p == null) continue;
            
            fillCircle(ctx, p.x, p.y, RADIUS, statusColors.get("structure"));
            drawCircleOutline(ctx, p.x, p.y, RADIUS, 0xFF000000);

            if (dist2(p.x, p.y, mouseX, mouseY) <= RADIUS * RADIUS) {
                hovered = pos;
            }
        }

        // 显示悬停提示
        if (hovered != null) {
            TextRenderer font = MinecraftClient.getInstance().textRenderer;
            ctx.drawTooltip(font, Text.literal(hovered.toShortString()), mouseX, mouseY);
        }

        // 绘制玩家位置
        drawPlayerMarker(ctx);

        // 绘制比例尺和图例
        drawScale(ctx);
        drawLegend(ctx);

        // 绘制标题
        drawCenteredTitle(ctx);

        // 绘制统计信息
        drawStats(ctx);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawRoadPaths(DrawContext ctx) {
        if (roads == null || roads.isEmpty()) return;

        int roadColor = statusColors.get("road");
        
        for (Records.RoadData roadData : roads) {
            List<Records.RoadSegmentPlacement> segments = roadData.roadSegmentList();
            if (segments == null || segments.size() < 2) continue;

            // 绘制道路路径（连接中心点）
            for (int i = 0; i < segments.size() - 1; i++) {
                BlockPos pos1 = segments.get(i).middlePos();
                BlockPos pos2 = segments.get(i + 1).middlePos();
                
                ScreenPos p1 = worldToScreen(pos1.getX(), pos1.getZ());
                ScreenPos p2 = worldToScreen(pos2.getX(), pos2.getZ());
                
                // 使用半透明的蓝色绘制道路
                drawLine(ctx, p1.x, p1.y, p2.x, p2.y, (roadColor & 0x00FFFFFF) | 0x80000000);
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void applyBlur(float delta) {
        // 禁用模糊效果
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // 点击节点传送
        BlockPos clicked = findClickedStructure(mouseX, mouseY);
        if (clicked != null) {
            teleportTo(clicked);
            return true;
        }
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == 0) {
            offsetX += deltaX;
            offsetY += deltaY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        double old = zoom;
        zoom = vertical > 0 ? zoom * 1.1 : zoom / 1.1;
        zoom = Math.max(0.1, Math.min(10.0, zoom)); // 限制缩放范围
        
        offsetX = (offsetX - mouseX + PADDING) * (zoom / old) + mouseX - PADDING;
        offsetY = (offsetY - mouseY + PADDING) * (zoom / old) + mouseY - PADDING;
        return true;
    }

    private void drawCenteredTitle(DrawContext ctx) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        Text title = Text.translatable("gui.settlementroads.debug_map.title");
        int tw = font.getWidth(title);
        ctx.drawText(font, title, (width - tw) / 2, PADDING - 12, 0xFFFFFFFF, true);
    }

    private void drawStats(DrawContext ctx) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int x = width - PADDING - 150;
        int y = PADDING + 5;
        
        // 统计各状态的连接数（避免 ConcurrentModificationException）
        int planned = 0;
        int generating = 0;
        int completed = 0;
        int failed = 0;
        for (Records.StructureConnection conn : connections) {
            switch (conn.status()) {
                case PLANNED -> planned++;
                case GENERATING -> generating++;
                case COMPLETED -> completed++;
                case FAILED -> failed++;
            }
        }
        
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.structures", structures.size()), x, y, 0xFFFFFFFF, true);
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.planned", planned), x, y + 12, statusColors.get("planned"), true);
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.generating", generating), x, y + 24, statusColors.get("generating"), true);
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.completed", completed), x, y + 36, statusColors.get("completed"), true);
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.failed", failed), x, y + 48, statusColors.get("failed"), true);
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.roads", roads.size()), x, y + 60, statusColors.get("road"), true);
        ctx.drawText(font, Text.translatable("gui.settlementroads.debug_map.zoom", String.format("%.1f", zoom)), x, y + 72, 0xFFFFFFFF, true);
    }

    private void drawGrid(DrawContext ctx) {
        int w = width - PADDING * 2;
        int h = height - PADDING * 2;

        double worldX0 = minX + (-offsetX) / (baseScale * zoom);
        double worldZ0 = minZ + (-offsetY) / (baseScale * zoom);
        double worldX1 = minX + (w - offsetX) / (baseScale * zoom);
        double worldZ1 = minZ + (h - offsetY) / (baseScale * zoom);

        int spacing = computeGridSpacing();

        int startWX = (int) Math.floor(worldX0 / spacing) * spacing;
        int startWZ = (int) Math.floor(worldZ0 / spacing) * spacing;

        // 绘制垂直线
        for (int x = startWX; x <= worldX1; x += spacing) {
            int sx = PADDING + (int) ((x - worldX0) * baseScale * zoom);
            fillV(ctx, sx, PADDING, PADDING + h, 0x40444444);
            drawSmallLabel(ctx, String.valueOf(x), sx + 2, PADDING + 2);
        }

        // 绘制水平线
        for (int z = startWZ; z <= worldZ1; z += spacing) {
            int sz = PADDING + (int) ((z - worldZ0) * baseScale * zoom);
            fillH(ctx, PADDING, PADDING + w, sz, 0x40444444);
            drawSmallLabel(ctx, String.valueOf(z), PADDING + 2, sz + 2);
        }
    }

    private void drawScale(DrawContext ctx) {
        int spacing = computeGridSpacing();
        int lengthPx = (int) (spacing * baseScale * zoom);
        int x = width - PADDING - lengthPx - 10;
        int y = height - PADDING - 20;

        fillH(ctx, x, x + lengthPx, y, 0xFFFFFFFF);
        fillV(ctx, x, y - 3, y + 3, 0xFFFFFFFF);
        fillV(ctx, x + lengthPx, y - 3, y + 3, 0xFFFFFFFF);
        drawSmallLabel(ctx, Text.translatable("gui.settlementroads.debug_map.blocks", spacing).getString(), x, y - 12);
    }

    private void drawLegend(DrawContext ctx) {
        int x = PADDING + 5;
        int y = PADDING + 5;
        
        // 结构
        ctx.fill(x, y, x + 10, y + 10, statusColors.get("structure"));
        ctx.drawBorder(x, y, 10, 10, 0xFFFFFFFF);
        drawSmallLabel(ctx, Text.translatable("gui.settlementroads.debug_map.legend.structures").getString(), x + 15, y + 1);
        
        // 计划中
        y += 15;
        ctx.fill(x, y, x + 10, y + 10, statusColors.get("planned"));
        ctx.drawBorder(x, y, 10, 10, 0xFFFFFFFF);
        drawSmallLabel(ctx, Text.translatable("gui.settlementroads.debug_map.legend.planned").getString(), x + 15, y + 1);
        
        // 生成中
        y += 15;
        ctx.fill(x, y, x + 10, y + 10, statusColors.get("generating"));
        ctx.drawBorder(x, y, 10, 10, 0xFFFFFFFF);
        drawSmallLabel(ctx, Text.translatable("gui.settlementroads.debug_map.legend.generating").getString(), x + 15, y + 1);
        
        // 失败
        y += 15;
        ctx.fill(x, y, x + 10, y + 10, statusColors.get("failed"));
        ctx.drawBorder(x, y, 10, 10, 0xFFFFFFFF);
        drawSmallLabel(ctx, Text.translatable("gui.settlementroads.debug_map.legend.failed").getString(), x + 15, y + 1);
        
        // 道路
        y += 15;
        ctx.fill(x, y, x + 10, y + 10, statusColors.get("road"));
        ctx.drawBorder(x, y, 10, 10, 0xFFFFFFFF);
        drawSmallLabel(ctx, Text.translatable("gui.settlementroads.debug_map.legend.roads").getString(), x + 15, y + 1);
    }

    private void computeLayout() {
        if (structures.isEmpty()) return;
        
        int w = Math.max(1, width - PADDING * 2);
        int h = Math.max(1, height - PADDING * 2);

        double scaleX = (double) w / Math.max(1, maxX - minX);
        double scaleZ = (double) h / Math.max(1, maxZ - minZ);
        baseScale = Math.min(scaleX, scaleZ) * 0.9; // 留一些边距

        if (firstLayout) {
            double graphW = (maxX - minX) * baseScale * zoom;
            double graphH = (maxZ - minZ) * baseScale * zoom;
            offsetX = (w - graphW) / 2.0;
            offsetY = (h - graphH) / 2.0;
            firstLayout = false;
        }

        screenPositions.clear();
        for (BlockPos pos : structures) {
            double sx = (pos.getX() - minX) * baseScale * zoom + offsetX;
            double sy = (pos.getZ() - minZ) * baseScale * zoom + offsetY;
            screenPositions.put(pos, new ScreenPos(PADDING + (int) sx, PADDING + (int) sy));
        }
    }

    private int computeGridSpacing() {
        double unitsPerPixel = 1.0 / (baseScale * zoom);
        double raw = TARGET_GRID_PX * unitsPerPixel;
        double pow10 = Math.pow(10, Math.floor(Math.log10(raw)));
        
        for (int n : new int[]{1, 2, 5}) {
            double candidate = n * pow10;
            if (candidate >= raw) return (int) candidate;
        }
        return (int) (10 * pow10);
    }

    private BlockPos findClickedStructure(double mouseX, double mouseY) {
        for (BlockPos pos : structures) {
            ScreenPos p = screenPositions.get(pos);
            if (p != null && dist2(p.x, p.y, mouseX, mouseY) <= RADIUS * RADIUS) {
                return pos;
            }
        }
        return null;
    }

    private void teleportTo(BlockPos pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (mc.getServer() != null) {
            // 单人游戏：在服务器线程执行传送
            mc.getServer().execute(() -> {
                ServerPlayerEntity sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
                if (sp != null) {
                    sp.requestTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                }
            });
        }
    }

    private ScreenPos worldToScreen(double wx, double wz) {
        int sx = PADDING + (int) ((wx - minX) * baseScale * zoom + offsetX);
        int sy = PADDING + (int) ((wz - minZ) * baseScale * zoom + offsetY);
        return new ScreenPos(sx, sy);
    }

    private void drawPlayerMarker(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || structures.isEmpty()) return;

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        ScreenPos p = worldToScreen(px, pz);

        // 红色玩家标记
        final int r = RADIUS + 2;
        final int fill = 0xFFE74C3C;
        final int outline = 0xFF000000;

        fillCircle(ctx, p.x, p.y, r, fill);
        drawCircleOutline(ctx, p.x, p.y, r, outline);

        // 方向箭头
        float yaw = mc.player.getYaw();
        double angle = Math.toRadians(yaw) + Math.PI / 2.0;
        int tx = p.x + (int) Math.round(Math.cos(angle) * (r + 4));
        int ty = p.y + (int) Math.round(Math.sin(angle) * (r + 4));
        drawLine(ctx, p.x, p.y, tx, ty, 0xFFFFFFFF);
    }

    private static double dist2(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private void drawSmallLabel(DrawContext ctx, String s, int x, int y) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        ctx.drawText(font, Text.literal(s), x, y, 0xFFFFFFFF, true);
    }

    // ========== 绘图原语 ==========

    private static void fillH(DrawContext ctx, int x0, int x1, int y, int argb) {
        if (x1 < x0) {
            int t = x0;
            x0 = x1;
            x1 = t;
        }
        ctx.fill(x0, y, x1, y + 1, argb);
    }

    private static void fillV(DrawContext ctx, int x, int y0, int y1, int argb) {
        if (y1 < y0) {
            int t = y0;
            y0 = y1;
            y1 = t;
        }
        ctx.fill(x, y0, x + 1, y1, argb);
    }

    private static void drawLine(DrawContext ctx, int x0, int y0, int x1, int y1, int argb) {
        // Bresenham 算法
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        
        while (true) {
            ctx.fill(x, y, x + 1, y + 1, argb);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static void fillCircle(DrawContext ctx, int cx, int cy, int r, int argb) {
        for (int dy = -r; dy <= r; dy++) {
            int span = (int) Math.round(Math.sqrt(r * r - dy * dy));
            ctx.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, argb);
        }
    }

    private static void drawCircleOutline(DrawContext ctx, int cx, int cy, int r, int argb) {
        int x = r;
        int y = 0;
        int err = 0;
        
        while (x >= y) {
            plot8(ctx, cx, cy, x, y, argb);
            y++;
            if (err <= 0) {
                err += 2 * y + 1;
            }
            if (err > 0) {
                x--;
                err -= 2 * x + 1;
            }
        }
    }

    private static void plot8(DrawContext ctx, int cx, int cy, int x, int y, int argb) {
        ctx.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, argb);
        ctx.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, argb);
        ctx.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, argb);
        ctx.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, argb);
        ctx.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, argb);
        ctx.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, argb);
        ctx.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, argb);
        ctx.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, argb);
    }

    private record ScreenPos(int x, int y) {}
}
