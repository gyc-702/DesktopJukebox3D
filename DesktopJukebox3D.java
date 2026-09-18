import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;

public class DesktopJukebox3D extends JFrame {

    private static final int LOGICAL_WIDTH = 400;
    private static final int LOGICAL_HEIGHT = 400;

    public DesktopJukebox3D() {
        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setSize(LOGICAL_WIDTH, LOGICAL_HEIGHT);
        setResizable(false);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        add(new CubePanel());
    }

    class CubePanel extends JPanel {

        private BufferedImage texTop;
        private BufferedImage texSide;
        private BufferedImage texNote;
        private BufferedImage texNoteTint;
        private BufferedImage[] faceTextures;
        private boolean textureLoaded = false;
        private boolean firstRender = true;

        private final float[][] v = {
                {-1, -1, -1}, { 1, -1, -1}, { 1,  1, -1}, {-1,  1, -1},
                {-1, -1,  1}, { 1, -1,  1}, { 1,  1,  1}, {-1,  1,  1}
        };

        private final int[][] faces = {
                {3, 7, 6, 2},
                {0, 1, 5, 4},
                {4, 5, 6, 7},
                {0, 3, 2, 1},
                {0, 4, 7, 3},
                {1, 2, 6, 5}
        };

        private final Color[] faceColors = {
                Color.RED, Color.GREEN, Color.BLUE,
                Color.YELLOW, Color.CYAN, Color.MAGENTA
        };

        private float rotX = 0.4f;
        private float rotY = 0.6f;
        private boolean leftMouseDown = false;
        private java.awt.Point lastMouse;

        private final float scale = 130;
        private final float distance = 5f;
        private static final float CUBE_SCALE = 0.425f;

        private BufferedImage renderBuffer;
        private Graphics2D bufferG2;

        private final ArrayList<Note> notes = new ArrayList<>();
        private long lastNoteSpawn = 0;
        private static final long SPAWN_INTERVAL = 350;
        private boolean notesSpawnEnabled = true;

        // UI 工具栏：5 个按钮
        private final ArrayList<UiButton> uiButtons = new ArrayList<>();
        private boolean uiShown = false;

        private final WindowDragPos windowDrag = new WindowDragPos();

        private static final Color NOTE_COLOR = new Color(20, 130, 70);

        public CubePanel() {
            setOpaque(false);
            setFocusable(true);
            requestFocusInWindow();

            File baseDir = new File(System.getProperty("user.dir"));
            try {
                texTop = ImageIO.read(new File(baseDir, "jukebox_top.png"));
                texSide = ImageIO.read(new File(baseDir, "jukebox_side.png"));
                texNote = ImageIO.read(new File(baseDir, "note.png"));

                if (texTop != null && texSide != null) {
                    faceTextures = new BufferedImage[6];
                    faceTextures[0] = texTop;
                    faceTextures[1] = texSide;
                    faceTextures[2] = texSide;
                    faceTextures[3] = texSide;
                    faceTextures[4] = texSide;
                    faceTextures[5] = texSide;
                    textureLoaded = true;
                }
                if (texNote != null) {
                    texNoteTint = tintImage(texNote, NOTE_COLOR);
                }
            } catch (IOException e) {
            }

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        leftMouseDown = true;
                        lastMouse = e.getPoint();
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        Window window = SwingUtilities.getWindowAncestor(CubePanel.this);
                        windowDrag.screenX = e.getXOnScreen();
                        windowDrag.screenY = e.getYOnScreen();
                        windowDrag.windowX = window.getLocation().x;
                        windowDrag.windowY = window.getLocation().y;
                    } else if (SwingUtilities.isMiddleMouseButton(e)) {
                        // 中键：显示/保持 UI 工具栏
                        showUiToolbar();
                    } else {
                        // 其他按键点击：隐藏 UI
                        hideUiToolbar();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        leftMouseDown = false;
                    }
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && leftMouseDown && lastMouse != null) {
                        int dx = e.getX() - lastMouse.x;
                        int dy = e.getY() - lastMouse.y;
                        rotY -= dx * 0.01f;
                        rotX += dy * 0.01f;
                        lastMouse = e.getPoint();
                        repaint();
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        int dx = e.getXOnScreen() - windowDrag.screenX;
                        int dy = e.getYOnScreen() - windowDrag.screenY;
                        SwingUtilities.getWindowAncestor(CubePanel.this)
                                .setLocation(windowDrag.windowX + dx, windowDrag.windowY + dy);
                    }
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                        notesSpawnEnabled = !notesSpawnEnabled;
                    }
                }
            });

            new javax.swing.Timer(16, e -> repaint()).start();
        }

        private void showUiToolbar() {
            if (uiShown) return;

            uiButtons.clear();

            // 5 个 UI 按钮位置：在方块上方横向扇形排列
            // 注意：这里的坐标是方块局部坐标，不随视角旋转
            float[][] targets = {
                    {-0.90f, 1.50f, 0f},
                    {-0.45f, 1.55f, 0f},
                    { 0.00f, 1.60f, 0f},
                    { 0.45f, 1.55f, 0f},
                    { 0.90f, 1.50f, 0f}
            };

            UiButtonType[] types = {
                    UiButtonType.FOLDER,
                    UiButtonType.PREV,
                    UiButtonType.PAUSE,
                    UiButtonType.NEXT,
                    UiButtonType.LOOP
            };

            for (int i = 0; i < 5; i++) {
                UiButton btn = new UiButton();
                btn.targetX = targets[i][0];
                btn.targetY = targets[i][1];
                btn.targetZ = targets[i][2];
                btn.type = types[i];
                btn.progress = 0f;
                uiButtons.add(btn);
            }

            uiShown = true;
        }

        private void hideUiToolbar() {
            if (!uiShown) return;
            uiShown = false;
            uiButtons.clear();
        }

        private BufferedImage tintImage(BufferedImage src, Color color) {
            int w = src.getWidth();
            int h = src.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

            int targetR = color.getRed();
            int targetG = color.getGreen();
            int targetB = color.getBlue();

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = src.getRGB(x, y);
                    int a = (rgb >> 24) & 0xff;

                    if (a <= 0) {
                        out.setRGB(x, y, 0);
                    } else {
                        int gray = (int) (((rgb >> 16) & 0xff) * 0.299 +
                                          ((rgb >> 8) & 0xff) * 0.587 +
                                          (rgb & 0xff) * 0.114);
                        gray = Math.max(0, Math.min(255, gray));

                        int r = targetR * gray / 255;
                        int g = targetG * gray / 255;
                        int b = targetB * gray / 255;

                        out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
            }
            return out;
        }

        private float[] rotate(float x, float y, float z) {
            x *= CUBE_SCALE;
            y *= CUBE_SCALE;
            z *= CUBE_SCALE;

            float x1 = (float) (x * Math.cos(rotY) - z * Math.sin(rotY));
            float z1 = (float) (x * Math.sin(rotY) + z * Math.cos(rotY));
            float y1 = (float) (y * Math.cos(rotX) - z1 * Math.sin(rotX));
            float z2 = (float) (y * Math.sin(rotX) + z1 * Math.cos(rotX));
            return new float[]{x1, y1, z2};
        }

        private float[] project(float x, float y, float z) {
            float factor = distance / (distance - z);
            float sx = x * factor * scale + LOGICAL_WIDTH / 2f;
            float sy = -y * factor * scale + LOGICAL_HEIGHT / 2f;
            float invZ = 1f / (distance - z);
            return new float[]{sx, sy, z, invZ};
        }

        private float[] barycentric(float px, float py,
                                    float x1, float y1, float x2, float y2, float x3, float y3) {
            float det = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3);
            if (Math.abs(det) < 0.0001f) return new float[]{-1, -1, -1};
            float u = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / det;
            float v = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / det;
            float w = 1 - u - v;
            return new float[]{u, v, w};
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;

            if (renderBuffer == null) {
                renderBuffer = new BufferedImage(LOGICAL_WIDTH * 2, LOGICAL_HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);
                bufferG2 = renderBuffer.createGraphics();
                bufferG2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            }

            bufferG2.setComposite(AlphaComposite.getInstance(AlphaComposite.CLEAR));
            bufferG2.fillRect(0, 0, renderBuffer.getWidth(), renderBuffer.getHeight());
            bufferG2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

            long now = System.currentTimeMillis();

            if (notesSpawnEnabled && now - lastNoteSpawn > SPAWN_INTERVAL) {
                int face = (int)(Math.random() * 5);
                float x, y, z;
                switch (face) {
                    case 0: x = (float)(Math.random() * 1.4f - 0.7f); y = 1.02f; z = (float)(Math.random() * 1.4f - 0.7f); break;
                    case 1: x = (float)(Math.random() * 1.4f - 0.7f); y = (float)(Math.random() * 1.4f - 0.7f); z = 1.02f; break;
                    case 2: x = (float)(Math.random() * 1.4f - 0.7f); y = (float)(Math.random() * 1.4f - 0.7f); z = -1.02f; break;
                    case 3: x = -1.02f; y = (float)(Math.random() * 1.4f - 0.7f); z = (float)(Math.random() * 1.4f - 0.7f); break;
                    case 4: x = 1.02f; y = (float)(Math.random() * 1.4f - 0.7f); z = (float)(Math.random() * 1.4f - 0.7f); break;
                    default: x = 0; y = 1.02f; z = 0;
                }
                notes.add(new Note(x, y, z));
                lastNoteSpawn = now;
            }

            Iterator<Note> noteIt = notes.iterator();
            while (noteIt.hasNext()) {
                Note n = noteIt.next();
                n.update();
                if (n.life <= 0) noteIt.remove();
            }

            // 更新 UI 按钮展开动画
            for (UiButton btn : uiButtons) {
                if (btn.progress < 1f) {
                    btn.progress += 0.025f;
                    if (btn.progress > 1f) btn.progress = 1f;
                }
            }

            float[][] rotated = new float[v.length][3];
            float[][] projected = new float[v.length][4];
            for (int i = 0; i < v.length; i++) {
                rotated[i] = rotate(v[i][0], v[i][1], v[i][2]);
                projected[i] = project(rotated[i][0], rotated[i][1], rotated[i][2]);
            }

            ArrayList<DrawElement> drawList = new ArrayList<>();

            for (int i = 0; i < faces.length; i++) {
                int[] face = faces[i];
                float centerZ = 0;
                for (int idx : face) centerZ += rotated[idx][2];
                centerZ /= 4;

                int[] xs = new int[4];
                int[] ys = new int[4];
                float[] invZs = new float[4];
                for (int j = 0; j < 4; j++) {
                    xs[j] = (int) (projected[face[j]][0] * 2);
                    ys[j] = (int) (projected[face[j]][1] * 2);
                    invZs[j] = projected[face[j]][3];
                }

                FaceData fd = new FaceData(xs, ys, invZs, i, centerZ);
                final int faceIndex = i;

                if (textureLoaded && faceTextures[faceIndex] != null) {
                    drawList.add(new DrawElement(centerZ, () -> drawTexturedFace(fd, faceTextures[faceIndex])));
                } else {
                    drawList.add(new DrawElement(centerZ, () -> {
                        Polygon poly = new Polygon(fd.xs, fd.ys, 4);
                        bufferG2.setColor(faceColors[faceIndex]);
                        bufferG2.fill(poly);
                        bufferG2.setColor(Color.BLACK);
                        bufferG2.draw(poly);
                    }));
                }
            }

            if (texNoteTint != null) {
                for (Note n : notes) {
                    float[] worldPos = rotate(n.x, n.y, n.z);
                    float[] proj = project(worldPos[0], worldPos[1], worldPos[2]);
                    double depth = proj[2];

                    final float sx = proj[0] * 2;
                    final float sy = proj[1] * 2;
                    final float life = n.life;
                    final int noteSize = 24 * 2;

                    drawList.add(new DrawElement(depth, () -> {
                        int x = (int) (sx - noteSize / 2f);
                        int y = (int) (sy - noteSize / 2f);
                        AlphaComposite ac = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, life);
                        bufferG2.setComposite(ac);
                        bufferG2.drawImage(texNoteTint, x, y, noteSize, noteSize, null);
                        bufferG2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
                    }));
                }
            }

            // UI 按钮：固定在方块上方，不随视角旋转
            for (UiButton btn : uiButtons) {
                float ease = 1 - (float) Math.pow(1 - btn.progress, 3);
                float drawX = btn.targetX;
                float drawY = btn.targetY;
                float drawZ = btn.targetZ;

                float[] worldPos = rotate(drawX, drawY, drawZ);
                float[] proj = project(worldPos[0], worldPos[1], worldPos[2]);
                double depth = proj[2];

                final float sx = proj[0] * 2;
                final float sy = proj[1] * 2;
                final float alpha = ease;
                final UiButtonType type = btn.type;

                drawList.add(new DrawElement(depth, () -> {
                    drawUiButton(sx, sy, alpha, type);
                }));
            }

            Collections.sort(drawList);
            for (DrawElement e : drawList) {
                e.draw.run();
            }

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(renderBuffer, 0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT, null);

            if (firstRender) {
                firstRender = false;
                System.out.println("渲染成功");
            }
        }

        private void drawUiButton(float sx, float sy, float alpha, UiButtonType type) {
            int centerX = (int) sx;
            int centerY = (int) sy;
            int radius = 34; // 大按钮
            int inner = 26;

            AlphaComposite ac = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
            bufferG2.setComposite(ac);

            // 外圈
            bufferG2.setColor(new Color(255, 255, 255, 230));
            bufferG2.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

            // 内圈
            bufferG2.setColor(new Color(245, 245, 245, 255));
            bufferG2.fillOval(centerX - inner, centerY - inner, inner * 2, inner * 2);

            // 图标线
            bufferG2.setColor(new Color(40, 40, 40, 255));
            bufferG2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (type) {
                case FOLDER:
                    drawFolderIcon(centerX, centerY);
                    break;
                case PREV:
                    drawPrevIcon(centerX, centerY);
                    break;
                case PAUSE:
                    drawPauseIcon(centerX, centerY);
                    break;
                case NEXT:
                    drawNextIcon(centerX, centerY);
                    break;
                case LOOP:
                    drawLoopIcon(centerX, centerY);
                    break;
            }

            bufferG2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
        }

        private void drawFolderIcon(int cx, int cy) {
            bufferG2.drawLine(cx - 9, cy - 7, cx - 2, cy - 7);
            bufferG2.drawLine(cx - 2, cy - 7, cx, cy - 4);
            bufferG2.drawLine(cx, cy - 4, cx + 9, cy - 4);
            bufferG2.drawLine(cx + 9, cy - 4, cx + 9, cy + 8);
            bufferG2.drawLine(cx + 9, cy + 8, cx - 9, cy + 8);
            bufferG2.drawLine(cx - 9, cy + 8, cx - 9, cy - 7);
        }

        private void drawPrevIcon(int cx, int cy) {
            bufferG2.drawLine(cx + 8, cy - 8, cx - 6, cy);
            bufferG2.drawLine(cx - 6, cy, cx + 8, cy + 8);
            bufferG2.drawLine(cx - 8, cy - 8, cx - 8, cy + 8);
        }

        private void drawPauseIcon(int cx, int cy) {
            bufferG2.fillRect(cx - 7, cy - 9, 5, 18);
            bufferG2.fillRect(cx + 2, cy - 9, 5, 18);
        }

        private void drawNextIcon(int cx, int cy) {
            bufferG2.drawLine(cx - 8, cy - 8, cx + 6, cy);
            bufferG2.drawLine(cx + 6, cy, cx - 8, cy + 8);
            bufferG2.drawLine(cx + 8, cy - 8, cx + 8, cy + 8);
        }

        private void drawLoopIcon(int cx, int cy) {
            bufferG2.drawArc(cx - 9, cy - 9, 18, 18, 30, 300);
            bufferG2.drawLine(cx + 7, cy - 7, cx + 9, cy - 9);
            bufferG2.drawLine(cx + 7, cy - 7, cx + 5, cy - 5);
        }

        private void drawTexturedFace(FaceData fd, BufferedImage texture) {
            int texW = texture.getWidth();
            int texH = texture.getHeight();

            int minX = Math.min(Math.min(fd.xs[0], fd.xs[1]), Math.min(fd.xs[2], fd.xs[3]));
            int maxX = Math.max(Math.max(fd.xs[0], fd.xs[1]), Math.max(fd.xs[2], fd.xs[3]));
            int minY = Math.min(Math.min(fd.ys[0], fd.ys[1]), Math.min(fd.ys[2], fd.ys[3]));
            int maxY = Math.max(Math.max(fd.ys[0], fd.ys[1]), Math.max(fd.ys[2], fd.ys[3]));

            minX = Math.max(0, minX);
            maxX = Math.min(renderBuffer.getWidth() - 1, maxX);
            minY = Math.max(0, minY);
            maxY = Math.min(renderBuffer.getHeight() - 1, maxY);

            float[][] uv = {{0,0}, {1,0}, {1,1}, {0,1}};
            float[] uOverZ = new float[4];
            float[] vOverZ = new float[4];
            for (int j = 0; j < 4; j++) {
                uOverZ[j] = uv[j][0] * fd.invZs[j];
                vOverZ[j] = uv[j][1] * fd.invZs[j];
            }

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    float[] bc1 = barycentric(x, y,
                            fd.xs[0], fd.ys[0], fd.xs[1], fd.ys[1], fd.xs[2], fd.ys[2]);
                    float u, v;
                    boolean inside = false;

                    if (bc1[0] >= 0 && bc1[1] >= 0 && bc1[2] >= 0) {
                        float uoz = bc1[0]*uOverZ[0] + bc1[1]*uOverZ[1] + bc1[2]*uOverZ[2];
                        float voz = bc1[0]*vOverZ[0] + bc1[1]*vOverZ[1] + bc1[2]*vOverZ[2];
                        float invZ = bc1[0]*fd.invZs[0] + bc1[1]*fd.invZs[1] + bc1[2]*fd.invZs[2];
                        u = uoz / invZ;
                        v = voz / invZ;
                        inside = true;
                    } else {
                        float[] bc2 = barycentric(x, y,
                                fd.xs[0], fd.ys[0], fd.xs[2], fd.ys[2], fd.xs[3], fd.ys[3]);
                        if (bc2[0] >= 0 && bc2[1] >= 0 && bc2[2] >= 0) {
                            float uoz = bc2[0]*uOverZ[0] + bc2[1]*uOverZ[2] + bc2[2]*uOverZ[3];
                            float voz = bc2[0]*vOverZ[0] + bc2[1]*vOverZ[2] + bc2[2]*vOverZ[3];
                            float invZ = bc2[0]*fd.invZs[0] + bc2[1]*fd.invZs[2] + bc2[2]*fd.invZs[3];
                            u = uoz / invZ;
                            v = voz / invZ;
                            inside = true;
                        } else {
                            continue;
                        }
                    }

                    if (!inside) continue;

                    u = Math.max(0, Math.min(1, u));
                    v = Math.max(0, Math.min(1, v));
                    int tx = (int) (u * (texW - 1));
                    int ty = (int) (v * (texH - 1));
                    int rgb = texture.getRGB(tx, ty);

                    float light;
                    if (fd.faceIndex == 0) light = 1.0f;
                    else if (fd.faceIndex == 1) light = 0.45f;
                    else light = 0.75f;

                    int a = (rgb >> 24) & 0xff;
                    int r = (int) Math.min(255, ((rgb >> 16) & 0xff) * light);
                    int green = (int) Math.min(255, ((rgb >> 8) & 0xff) * light);
                    int b = (int) Math.min(255, (rgb & 0xff) * light);

                    bufferG2.setColor(new Color(r, green, b, a));
                    bufferG2.drawLine(x, y, x, y);
                }
            }

            Polygon poly = new Polygon(fd.xs, fd.ys, 4);
            bufferG2.setColor(Color.BLACK);
            bufferG2.draw(poly);
        }

        enum UiButtonType {
            FOLDER, PREV, PAUSE, NEXT, LOOP
        }

        class UiButton {
            float targetX, targetY, targetZ;
            float progress;
            UiButtonType type;
        }

        class Note {
            float x, y, z;
            float life;

            Note(float x, float y, float z) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.life = 1.0f;
            }

            void update() {
                y += 0.012f;
                x += (Math.random() - 0.5) * 0.002f;
                z += (Math.random() - 0.5) * 0.002f;
                life -= 0.008f;
            }
        }

        class FaceData {
            int[] xs;
            int[] ys;
            float[] invZs;
            int faceIndex;
            double depth;

            FaceData(int[] xs, int[] ys, float[] invZs, int faceIndex, double depth) {
                this.xs = xs;
                this.ys = ys;
                this.invZs = invZs;
                this.faceIndex = faceIndex;
                this.depth = depth;
            }
        }

        class DrawElement implements Comparable<DrawElement> {
            double depth;
            Runnable draw;

            DrawElement(double depth, Runnable draw) {
                this.depth = depth;
                this.draw = draw;
            }

            @Override
            public int compareTo(DrawElement o) {
                return Double.compare(this.depth, o.depth);
            }
        }

        class WindowDragPos {
            int screenX, screenY;
            int windowX, windowY;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DesktopJukebox3D().setVisible(true));
    }
}
