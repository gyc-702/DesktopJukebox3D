import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.Properties;
import javax.sound.sampled.*;

public class DesktopJukebox3D extends JFrame {

    private static final int LOGICAL_WIDTH = 400;
    private static final int LOGICAL_HEIGHT = 400;
    private static final String CONFIG_FILE = "jukebox.properties";

    public DesktopJukebox3D() {
        setUndecorated(true);
        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setSize(LOGICAL_WIDTH, LOGICAL_HEIGHT);
        setResizable(false);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        add(new CubePanel());
        loadWindowState();
        setVisible(true);
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
                {3, 7, 6, 2}, {0, 1, 5, 4}, {4, 5, 6, 7},
                {0, 3, 2, 1}, {0, 4, 7, 3}, {1, 2, 6, 5}
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
        private boolean notesSpawnEnabled = false;
        private static final Color NOTE_COLOR = new Color(20, 130, 70);

        private final WindowDragPos windowDrag = new WindowDragPos();

        private final ArrayList<File> playlist = new ArrayList<>();
        private int currentTrackIndex = 0;
        private boolean isPlaying = false;
        private enum LoopMode { ALL, ONE, OFF }
        private LoopMode loopMode = LoopMode.ALL;
        private AudioPlayer audioPlayer;

        private final ArrayList<UiButton> uiButtons = new ArrayList<>();
        private boolean uiShown = false;
        private static final int BTN_RADIUS = 28;
        private static final int UI_Y = 70;

        public CubePanel() {
            setOpaque(false);
            setFocusable(true);
            requestFocusInWindow();

            audioPlayer = new AudioPlayer();
            initUiButtons();

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
                    int mx = e.getX();
                    int my = e.getY();

                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        uiShown = !uiShown;
                        if (uiShown) resetUiAnimation();
                        return;
                    }

                    if (SwingUtilities.isRightMouseButton(e)) {
                        Window window = SwingUtilities.getWindowAncestor(CubePanel.this);
                        windowDrag.screenX = e.getXOnScreen();
                        windowDrag.screenY = e.getYOnScreen();
                        windowDrag.windowX = window.getLocation().x;
                        windowDrag.windowY = window.getLocation().y;
                        return;
                    }

                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (uiShown) {
                            UiButton clicked = getClickedButton(mx, my);
                            if (clicked != null) {
                                doButtonAction(clicked.type);
                                return;
                            } else {
                                uiShown = false;
                            }
                        }
                        leftMouseDown = true;
                        lastMouse = e.getPoint();
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

            new javax.swing.Timer(16, e -> repaint()).start();
        }

        private void initUiButtons() {
            uiButtons.clear();
            int[] xs = {50, 130, 200, 270, 350};
            UiButtonType[] types = {
                    UiButtonType.FOLDER,
                    UiButtonType.PREV,
                    UiButtonType.PAUSE,
                    UiButtonType.NEXT,
                    UiButtonType.LOOP
            };
            for (int i = 0; i < 5; i++) {
                UiButton btn = new UiButton();
                btn.x = xs[i];
                btn.y = UI_Y;
                btn.radius = BTN_RADIUS;
                btn.type = types[i];
                btn.progress = 0f;
                uiButtons.add(btn);
            }
        }

        private void resetUiAnimation() {
            for (UiButton btn : uiButtons) {
                btn.progress = 0f;
            }
        }

        private UiButton getClickedButton(int mx, int my) {
            for (UiButton btn : uiButtons) {
                double dist = Math.hypot(mx - btn.x, my - btn.y);
                if (dist <= btn.radius) {
                    return btn;
                }
            }
            return null;
        }

        private void doButtonAction(UiButtonType type) {
            switch (type) {
                case FOLDER: chooseMusicFolder(); break;
                case PREV: playPrev(); break;
                case PAUSE:
                case PLAY: togglePlayPause(); break;
                case NEXT: playNext(); break;
                case LOOP: toggleLoopMode(); break;
            }
        }

        private void chooseMusicFolder() {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择音乐文件夹");

            File lastFolder = getLastMusicFolder();
            if (lastFolder != null && lastFolder.exists()) {
                chooser.setSelectedFile(lastFolder);
            }

            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                File folder = chooser.getSelectedFile();
                scanMusicFiles(folder);
                saveMusicFolder(folder.getAbsolutePath());

                if (!playlist.isEmpty()) {
                    currentTrackIndex = 0;
                    playCurrentTrack();
                } else {
                    JOptionPane.showMessageDialog(this,
                            "文件夹内没有找到 mp3/ogg/wav 文件",
                            "提示",
                            JOptionPane.WARNING_MESSAGE);
                }
            }
        }

        private void scanMusicFiles(File folder) {
            playlist.clear();
            File[] files = folder.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".wav");
            });
            if (files != null) {
                Collections.addAll(playlist, files);
            }
        }

        private void playCurrentTrack() {
            if (playlist.isEmpty()) return;
            File track = playlist.get(currentTrackIndex);
            audioPlayer.play(track);
            isPlaying = true;
            notesSpawnEnabled = true;
            uiButtons.get(2).type = UiButtonType.PAUSE;
        }

        private void playPrev() {
            if (playlist.isEmpty()) return;
            currentTrackIndex = (currentTrackIndex - 1 + playlist.size()) % playlist.size();
            playCurrentTrack();
        }

        private void playNext() {
            if (playlist.isEmpty()) return;
            currentTrackIndex = (currentTrackIndex + 1) % playlist.size();
            playCurrentTrack();
        }

        private void togglePlayPause() {
            if (playlist.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请先选择音乐文件夹", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (isPlaying) {
                audioPlayer.pause();
                isPlaying = false;
                notesSpawnEnabled = false;
                uiButtons.get(2).type = UiButtonType.PLAY;
            } else {
                audioPlayer.resume();
                isPlaying = true;
                notesSpawnEnabled = true;
                uiButtons.get(2).type = UiButtonType.PAUSE;
            }
        }

        private void toggleLoopMode() {
            LoopMode[] values = LoopMode.values();
            int ord = (loopMode.ordinal() + 1) % values.length;
            loopMode = values[ord];
        }

        private void onTrackFinished() {
            SwingUtilities.invokeLater(() -> {
                switch (loopMode) {
                    case ONE:
                        playCurrentTrack();
                        break;
                    case ALL:
                        if (currentTrackIndex < playlist.size() - 1) {
                            playNext();
                        } else {
                            isPlaying = false;
                            notesSpawnEnabled = false;
                            uiButtons.get(2).type = UiButtonType.PLAY;
                        }
                        break;
                    case OFF:
                        isPlaying = false;
                        notesSpawnEnabled = false;
                        uiButtons.get(2).type = UiButtonType.PLAY;
                        break;
                }
            });
        }

        class AudioPlayer {
            private SourceDataLine line;
            private Thread playThread;
            private volatile boolean paused;
            private volatile boolean stopped;

            public void play(File audioFile) {
                stop();
                paused = false;
                stopped = false;

                playThread = new Thread(() -> {
                    try {
                        AudioInputStream rawAis = AudioSystem.getAudioInputStream(audioFile);
                        AudioFormat baseFormat = rawAis.getFormat();

                        AudioFormat targetFormat = new AudioFormat(
                                AudioFormat.Encoding.PCM_SIGNED,
                                baseFormat.getSampleRate(),
                                16,
                                baseFormat.getChannels(),
                                baseFormat.getChannels() * 2,
                                baseFormat.getSampleRate(),
                                false
                        );

                        AudioInputStream decodedAis = AudioSystem.getAudioInputStream(targetFormat, rawAis);
                        DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetFormat);
                        line = (SourceDataLine) AudioSystem.getLine(info);
                        line.open(targetFormat);
                        line.start();

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while (!stopped && (bytesRead = decodedAis.read(buffer)) != -1) {
                            while (paused && !stopped) {
                                Thread.sleep(10);
                            }
                            if (stopped) break;
                            line.write(buffer, 0, bytesRead);
                        }

                        if (!stopped) {
                            line.drain();
                            onTrackFinished();
                        }

                        line.close();
                        decodedAis.close();
                        rawAis.close();

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                playThread.setDaemon(true);
                playThread.start();
            }

            public void pause() {
                paused = true;
            }

            public void resume() {
                paused = false;
            }

            public void stop() {
                stopped = true;
                paused = false;
                if (line != null && line.isRunning()) {
                    line.stop();
                    line.close();
                }
                if (playThread != null) {
                    playThread.interrupt();
                }
            }
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

            Collections.sort(drawList);
            for (DrawElement e : drawList) {
                e.draw.run();
            }

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(renderBuffer, 0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT, null);

            if (uiShown) {
                draw2dUi(g2);
            }

            if (firstRender) {
                firstRender = false;
                System.out.println("渲染成功");
            }
        }

        private void draw2dUi(Graphics2D g2) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            for (UiButton btn : uiButtons) {
                if (btn.progress < 1f) {
                    btn.progress += 0.03f;
                    if (btn.progress > 1f) btn.progress = 1f;
                }
                float ease = 1 - (float) Math.pow(1 - btn.progress, 3);
                float alpha = ease;
                float scale = 0.5f + 0.5f * ease;

                int cx = btn.x;
                int cy = btn.y;
                int r = (int)(btn.radius * scale);

                AlphaComposite ac = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
                g2.setComposite(ac);

                g2.setColor(new Color(255, 255, 255, 230));
                g2.fillOval(cx - r, cy - r, r * 2, r * 2);
                g2.setColor(new Color(40, 40, 40, 255));

                switch (btn.type) {
                    case FOLDER: drawFolderIcon(g2, cx, cy, r); break;
                    case PREV: drawPrevIcon(g2, cx, cy, r); break;
                    case PLAY: drawPlayIcon(g2, cx, cy, r); break;
                    case PAUSE: drawPauseIcon(g2, cx, cy, r); break;
                    case NEXT: drawNextIcon(g2, cx, cy, r); break;
                    case LOOP: drawLoopIcon(g2, cx, cy, r); break;
                }
            }
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));
        }

        private void drawFolderIcon(Graphics2D g, int cx, int cy, int r) {
            int s = (int)(r * 0.6);
            g.drawRect(cx - s, cy - s/2, s*2, s);
            g.drawLine(cx - s, cy - s/2, cx - s/2, cy - s);
            g.drawLine(cx - s/2, cy - s, cx + s/2, cy - s);
        }

        private void drawPrevIcon(Graphics2D g, int cx, int cy, int r) {
            int s = (int)(r * 0.6);
            g.drawLine(cx + s, cy - s, cx - s/2, cy);
            g.drawLine(cx - s/2, cy, cx + s, cy + s);
            g.drawLine(cx - s, cy - s, cx - s, cy + s);
        }

        private void drawPlayIcon(Graphics2D g, int cx, int cy, int r) {
            int s = (int)(r * 0.6);
            int[] x = {cx - s/2, cx + s, cx - s/2};
            int[] y = {cy - s, cy, cy + s};
            g.fillPolygon(x, y, 3);
        }

        private void drawPauseIcon(Graphics2D g, int cx, int cy, int r) {
            int s = (int)(r * 0.6);
            g.fillRect(cx - s, cy - s, s/2, s*2);
            g.fillRect(cx + s/2, cy - s, s/2, s*2);
        }

        private void drawNextIcon(Graphics2D g, int cx, int cy, int r) {
            int s = (int)(r * 0.6);
            g.drawLine(cx - s, cy - s, cx + s/2, cy);
            g.drawLine(cx + s/2, cy, cx - s, cy + s);
            g.drawLine(cx + s, cy - s, cx + s, cy + s);
        }

        private void drawLoopIcon(Graphics2D g, int cx, int cy, int r) {
            int s = (int)(r * 0.65);
            g.drawArc(cx - s, cy - s, s*2, s*2, 30, 300);
            g.drawLine(cx + s - 2, cy - s + 2, cx + s + 3, cy - s - 3);
            g.drawLine(cx + s - 2, cy - s + 2, cx + s - 4, cy - s - 1);

            if (loopMode == LoopMode.ONE) {
                g.setFont(new Font("Dialog", Font.BOLD, s));
                String text = "1";
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(text);
                int th = fm.getAscent();
                g.drawString(text, cx - tw / 2, cy + th / 2);
            } else if (loopMode == LoopMode.OFF) {
                g.drawLine(cx - s, cy - s, cx + s, cy + s);
                g.drawLine(cx + s, cy - s, cx - s, cy + s);
            }
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

        enum UiButtonType { FOLDER, PREV, PLAY, PAUSE, NEXT, LOOP }

        class UiButton {
            int x, y;
            int radius;
            float progress;
            UiButtonType type;
        }

        class Note {
            float x, y, z;
            float life;
            Note(float x, float y, float z) {
                this.x = x; this.y = y; this.z = z;
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
            int[] xs, ys;
            float[] invZs;
            int faceIndex;
            double depth;
            FaceData(int[] xs, int[] ys, float[] invZs, int faceIndex, double depth) {
                this.xs = xs; this.ys = ys;
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
            int screenX, screenY, windowX, windowY;
        }
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return props;
    }

    private void saveConfig(Properties props) {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            props.store(fos, "Jukebox Config");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadWindowState() {
        Properties props = loadConfig();
        try {
            int x = Integer.parseInt(props.getProperty("window.x", "100"));
            int y = Integer.parseInt(props.getProperty("window.y", "100"));
            setLocation(x, y);
        } catch (NumberFormatException e) {
            setLocationRelativeTo(null);
        }

        CubePanel panel = (CubePanel) getContentPane().getComponent(0);
        try {
            panel.rotX = Float.parseFloat(props.getProperty("cube.rotX", "0.4"));
            panel.rotY = Float.parseFloat(props.getProperty("cube.rotY", "0.6"));
        } catch (NumberFormatException e) {
        }
    }

    private void saveWindowState() {
        Properties props = loadConfig();
        props.setProperty("window.x", String.valueOf(getLocation().x));
        props.setProperty("window.y", String.valueOf(getLocation().y));

        CubePanel panel = (CubePanel) getContentPane().getComponent(0);
        props.setProperty("cube.rotX", String.valueOf(panel.rotX));
        props.setProperty("cube.rotY", String.valueOf(panel.rotY));

        saveConfig(props);
    }

    private File getLastMusicFolder() {
        Properties props = loadConfig();
        String path = props.getProperty("music.folder");
        if (path == null || path.isEmpty()) return null;
        return new File(path);
    }

    private void saveMusicFolder(String path) {
        Properties props = loadConfig();
        props.setProperty("music.folder", path);
        saveConfig(props);
    }

    @Override
    protected void processWindowEvent(WindowEvent e) {
        if (e.getID() == WindowEvent.WINDOW_CLOSING) {
            saveWindowState();
        }
        super.processWindowEvent(e);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DesktopJukebox3D().setVisible(true));
    }
}
