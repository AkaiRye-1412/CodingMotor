import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 一次性工具：生成 MotorSizer 应用图标各尺寸 PNG（256 主尺寸缩放）。
 * 输出目录由 args[0] 指定。
 */
public class MakeIcon {

    private static final List<Integer> SIZES = List.of(16, 24, 32, 48, 64, 128, 256);

    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        outDir.mkdirs();

        BufferedImage master = drawMaster(256);
        ImageIO.write(master, "png", new File(outDir, "icon-256.png"));

        for (int size : SIZES) {
            if (size == 256) {
                continue;
            }
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(master, 0, 0, size, size, null);
            g.dispose();
            ImageIO.write(scaled, "png", new File(outDir, "icon-" + size + ".png"));
        }
        System.out.println("icons written to " + outDir.getAbsolutePath());
    }

    /** 绘制 256×256 主图标：圆角渐变方块 + 白色 M + 运动轨迹线。 */
    private static BufferedImage drawMaster(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double s = size / 256.0; // 以 256 为基准的比例

        // 1) 圆角方块背景：深蓝 → 青色 对角渐变
        RoundRectangle2D bg = new RoundRectangle2D.Double(
                8 * s, 8 * s, 240 * s, 240 * s, 52 * s, 52 * s);
        GradientPaint gradient = new GradientPaint(
                8f * (float) s, 8f * (float) s, new Color(0x1B, 0x2C, 0x6B),
                248f * (float) s, 248f * (float) s, new Color(0x00, 0xB3, 0xA4));
        g.setPaint(gradient);
        g.fill(bg);

        // 2) 中央白色粗体 M
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (132 * s)));
        FontMetrics fm = g.getFontMetrics();
        String letter = "M";
        int textW = fm.stringWidth(letter);
        int x = (size - textW) / 2;
        int y = (size - fm.getHeight()) / 2 + fm.getAscent() - (int) (22 * s);
        g.drawString(letter, x, y);

        // 3) M 下方的运动轨迹线：紫 → 青 渐变 + 右端节点
        int lineY = (int) (178 * s);
        GradientPaint lineGradient = new GradientPaint(
                52f * (float) s, (float) lineY, new Color(0x9B, 0x6B, 0xE0),
                196f * (float) s, (float) lineY, new Color(0x35, 0xE0, 0xD0));
        g.setPaint(lineGradient);
        Stroke oldStroke = g.getStroke();
        g.setStroke(new BasicStroke((float) (10 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) (52 * s), lineY, (int) (176 * s), lineY);
        // 左端小节点（起点）
        g.fillOval((int) (48 * s), lineY - (int) (5 * s), (int) (10 * s), (int) (10 * s));
        // 右端大节点（终点/负载点，亮青色）
        g.setColor(new Color(0x6F, 0xF7, 0xEC));
        g.fillOval((int) (180 * s), lineY - (int) (8 * s), (int) (16 * s), (int) (16 * s));
        g.setStroke(oldStroke);

        g.dispose();
        return image;
    }
}
