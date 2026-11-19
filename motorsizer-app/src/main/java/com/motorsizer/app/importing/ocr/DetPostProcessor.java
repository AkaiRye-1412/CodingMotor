package com.motorsizer.app.importing.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * DB 检测结果后处理：把概率图变成文本框。
 *
 * <p>只做「二值化 → 8 邻域连通域 → 外接矩形 → 同行合并」，不实现旋转框（minAreaRect）：
 * 样册与图纸的表格文字都是水平的，矩形足够；省掉多边形偏移与轮廓逼近，纯 Java 可维护。
 */
final class DetPostProcessor {

    /** 概率阈值：低于它视为背景（PP-OCR 官方默认 0.3）。 */
    private static final float THRESHOLD = 0.3f;
    /** 连通域最少像素数：过滤噪点。 */
    private static final int MIN_PIXELS = 8;
    /** 框的最小边长（检测图像素）。 */
    private static final int MIN_SIDE = 3;
    /**
     * 水平膨胀半径（检测图像素）：DB 输出的概率区域是「收缩过的字芯」，
     * 不膨胀的话每个字各成一个框，一行会被切成十几个单字。
     */
    private static final int DILATE_X = 6;

    private DetPostProcessor() {
    }

    /** 一个文本框（检测图坐标系）。 */
    record Box(int x0, int y0, int x1, int y1) {
        int width() {
            return x1 - x0 + 1;
        }

        int height() {
            return y1 - y0 + 1;
        }
    }

    /**
     * @param prob 概率图，[h][w]
     * @param w    概率图宽
     * @param h    概率图高
     */
    static List<Box> extract(float[][] prob, int w, int h) {
        // 1. 二值化
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                mask[row + x] = prob[y][x] >= THRESHOLD;
            }
        }
        // 2. 水平膨胀：把同一行相邻的字连成一段（DB 的 unclip 作用）
        boolean[] dilated = dilateHorizontally(mask, w, h);

        boolean[] visited = new boolean[w * h];
        int[] stack = new int[w * h];
        List<Box> boxes = new ArrayList<>();

        for (int startY = 0; startY < h; startY++) {
            for (int startX = 0; startX < w; startX++) {
                int start = startY * w + startX;
                if (visited[start] || !dilated[start]) {
                    continue;
                }
                // 洪泛填充，同时记录包围盒
                int top = 0;
                stack[top++] = start;
                visited[start] = true;
                int minX = w, maxX = -1, minY = h, maxY = -1, pixels = 0;

                while (top > 0) {
                    int idx = stack[--top];
                    int px = idx % w;
                    int py = idx / w;
                    pixels++;
                    if (px < minX) {
                        minX = px;
                    }
                    if (px > maxX) {
                        maxX = px;
                    }
                    if (py < minY) {
                        minY = py;
                    }
                    if (py > maxY) {
                        maxY = py;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            int nx = px + dx;
                            int ny = py + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                                continue;
                            }
                            int ni = ny * w + nx;
                            if (!visited[ni] && dilated[ni]) {
                                visited[ni] = true;
                                stack[top++] = ni;
                            }
                        }
                    }
                }

                if (pixels < MIN_PIXELS) {
                    continue;
                }
                // 3. 回缩膨胀量，再按高度补一点点边（等价于 DB 的 unclip）
                int pad = Math.max(2, Math.min(6, (maxY - minY) / 6));
                Box box = new Box(Math.max(0, minX + DILATE_X - pad), Math.max(0, minY - pad),
                        Math.min(w - 1, maxX - DILATE_X + pad), Math.min(h - 1, maxY + pad));
                if (box.width() < MIN_SIDE || box.height() < MIN_SIDE) {
                    continue;
                }
                boxes.add(box);
            }
        }
        return mergeSameLine(boxes);
    }

    /** 水平膨胀：先向右扩 R，再向左扩 R（等价于对称膨胀，O(n)）。 */
    private static boolean[] dilateHorizontally(boolean[] mask, int w, int h) {
        boolean[] out = new boolean[mask.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int last = Integer.MIN_VALUE / 2;
            for (int x = 0; x < w; x++) {
                if (mask[row + x]) {
                    last = x;
                }
                if (x - last <= DILATE_X) {
                    out[row + x] = true;
                }
            }
            last = Integer.MAX_VALUE / 2;
            for (int x = w - 1; x >= 0; x--) {
                if (mask[row + x]) {
                    last = x;
                }
                if (last - x <= DILATE_X) {
                    out[row + x] = true;
                }
            }
        }
        return out;
    }

    /**
     * 同一行被切成几段时合并（DB 偶尔会把长行的词间空隙判成断开）。
     * 判据严格：垂直方向重叠超过 60%，且水平间隙小于行高的 40%。
     */
    private static List<Box> mergeSameLine(List<Box> boxes) {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> a.y0() != b.y0() ? Integer.compare(a.y0(), b.y0())
                : Integer.compare(a.x0(), b.x0()));

        List<Box> out = new ArrayList<>();
        for (Box b : sorted) {
            Box merged = null;
            for (int i = 0; i < out.size(); i++) {
                Box o = out.get(i);
                if (overlapRatio(o, b) > 0.6 && gapX(o, b) < 0.4 * Math.min(o.height(), b.height())) {
                    merged = new Box(Math.min(o.x0(), b.x0()), Math.min(o.y0(), b.y0()),
                            Math.max(o.x1(), b.x1()), Math.max(o.y1(), b.y1()));
                    out.set(i, merged);
                    break;
                }
            }
            if (merged == null) {
                out.add(b);
            }
        }
        return out;
    }

    private static double overlapRatio(Box a, Box b) {
        int top = Math.max(a.y0(), b.y0());
        int bottom = Math.min(a.y1(), b.y1());
        int overlap = bottom - top + 1;
        if (overlap <= 0) {
            return 0;
        }
        return (double) overlap / Math.min(a.height(), b.height());
    }

    private static int gapX(Box a, Box b) {
        if (a.x1() < b.x0()) {
            return b.x0() - a.x1();
        }
        if (b.x1() < a.x0()) {
            return a.x0() - b.x1();
        }
        return 0;
    }
}
