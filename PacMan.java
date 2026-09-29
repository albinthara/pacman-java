import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import javax.swing.*;

public class PacMan {

    // ── Grid & game state ────────────────────────────────────────────────────
    private static final int ROWS = 10, COLS = 10;
    private static final int CELL = 56; // pixel size of each cell

    private JFrame frame;
    private GamePanel gamePanel;
    private JLabel scoreLabel, levelLabel;
    private Timer gameTimer;
    private boolean paused = false;

    private char[][] grid = new char[ROWS][COLS];

    // Pac-Man
    private int pacManRow = 5, pacManCol = 5;
    private int targetRow  = 5, targetCol  = 5;   // where we're animating TO
    private double pacX, pacY;                      // pixel position (top-left of cell)
    private int mouthAngle = 45;                    // degrees open
    private int mouthDir   = -5;                    // animation direction
    private int facingAngle = 0;                    // Java2D: 0=right 90=up 180=left 270=down

    // Score / level
    private int score = 0, level = 1;
    private int pelletsCollected = 0, numberOfPellets = 5;

    // Power-up
    private boolean powerUpActive = false;
    private int powerUpTurnsLeft  = 0;
    private static final int POWER_UP_DURATION = 5;

    // Ghosts [row, col, pixelX, pixelY, wobble]
    private List<double[]> ghosts = new ArrayList<>();
    private Random random = new Random();

    // Floating score labels
    private List<FloatLabel> floatLabels = new ArrayList<>();

    // Flash overlay (level-up)
    private float flashAlpha = 0f;

    // Movement queue
    private int pendingDRow = 0, pendingDCol = 0;
    private boolean moving = false;

    // ── Main ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(PacMan::new);
    }

    public PacMan() {
        this(true);
    }

    // The headless mode lets the game logic and rendering be checked without a window.
    PacMan(boolean showWindow) {
        if (showWindow) {
            buildUI();
        } else {
            scoreLabel = new JLabel("Score: 0");
            levelLabel = new JLabel("Level: 1");
            gamePanel = new GamePanel();
            gamePanel.setBackground(Color.BLACK);
        }
        initializeGame();
        if (showWindow) startGameLoop();
    }

    // ── UI construction ───────────────────────────────────────────────────────
    private void buildUI() {
        frame = new JFrame("Pac-Man");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setLayout(new BorderLayout());

        // Menu
        JMenuBar mb = new JMenuBar();
        JMenu gm = new JMenu("Game");
        JMenuItem ni = new JMenuItem("New Game");
        ni.addActionListener(e -> confirmNewGame());
        JMenuItem qi = new JMenuItem("Quit");
        qi.addActionListener(e -> confirmQuit());
        gm.add(ni); gm.add(qi);
        mb.add(gm);
        frame.setJMenuBar(mb);

        // Top bar
        JPanel top = new JPanel(new GridLayout(1, 2));
        top.setBackground(Color.BLACK);
        scoreLabel = new JLabel("Score: 0", JLabel.CENTER);
        levelLabel = new JLabel("Level: 1", JLabel.CENTER);
        for (JLabel l : new JLabel[]{scoreLabel, levelLabel}) {
            l.setFont(new Font("Arial", Font.BOLD, 18));
            l.setForeground(Color.YELLOW);
        }
        top.add(scoreLabel); top.add(levelLabel);
        frame.add(top, BorderLayout.NORTH);

        // Game panel
        gamePanel = new GamePanel();
        gamePanel.setPreferredSize(new Dimension(COLS * CELL, ROWS * CELL));
        gamePanel.setBackground(Color.BLACK);
        frame.add(gamePanel, BorderLayout.CENTER);

        // Window-scoped bindings keep controls working after a menu or dialog closes.
        for (int key : new int[]{KeyEvent.VK_W, KeyEvent.VK_UP, KeyEvent.VK_S,
                KeyEvent.VK_DOWN, KeyEvent.VK_A, KeyEvent.VK_LEFT,
                KeyEvent.VK_D, KeyEvent.VK_RIGHT}) {
            String action = "move-" + key;
            gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(key, 0), action);
            gamePanel.getActionMap().put(action, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { queueMove(key); }
            });
        }
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ── Game loop (60 fps) ────────────────────────────────────────────────────
    private void startGameLoop() {
        gameTimer = new Timer(16, e -> tick());
        gameTimer.start();
    }


    private int animStep = 0;
    private static final int ANIM_STEPS = 8; // steps to slide one cell

    private void tick() {
        if (paused) return;
        // Animate Pac-Man mouth
        mouthAngle += mouthDir;
        if (mouthAngle <= 5 || mouthAngle >= 45) mouthDir = -mouthDir;

        // Animate ghost wobble
        for (double[] g : ghosts) {
            g[4] += 0.15;
            g[2] = lerp(g[2], g[1] * CELL, 0.35);
            g[3] = lerp(g[3], g[0] * CELL, 0.35);
        }

        // Animate float labels
        Iterator<FloatLabel> it = floatLabels.iterator();
        while (it.hasNext()) { FloatLabel fl = it.next(); fl.tick(); if (fl.dead()) it.remove(); }

        // Flash fade
        if (flashAlpha > 0) flashAlpha = Math.max(0, flashAlpha - 0.03f);

        // Smooth movement
        if (moving) {
            animStep++;
            double t = (double) animStep / ANIM_STEPS;
            pacX = lerp(pacManCol * CELL, targetCol * CELL, t);
            pacY = lerp(pacManRow * CELL, targetRow * CELL, t);

            if (animStep >= ANIM_STEPS) {
                pacManRow = targetRow; pacManCol = targetCol;
                pacX = pacManCol * CELL; pacY = pacManRow * CELL;
                moving = false;
                animStep = 0;
                resolveCell(); // handle pellets, ghosts, power-ups
                applyPendingMove();
            }
        } else {
            applyPendingMove();
        }

        gamePanel.repaint();
    }

    private void applyPendingMove() {
        if (pendingDRow == 0 && pendingDCol == 0) return;
        int nr = pacManRow + pendingDRow;
        int nc = pacManCol + pendingDCol;
        pendingDRow = 0; pendingDCol = 0;

        if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) return;
        if (grid[nr][nc] == '#') {
            // Hit wall – flash red briefly (we just ignore move here)
            return;
        }
        targetRow = nr; targetCol = nc;
        moving = true;
        animStep = 0;
    }

    private void resolveCell() {
        char cell = grid[pacManRow][pacManCol];

        if (ghostAt(pacManRow, pacManCol) && !powerUpActive && cell != 'I') {
            gameOver("A ghost caught Pac-Man!");
            return;
        }

        if (cell == 'o') {
            score++;
            pelletsCollected++;
            scoreLabel.setText("Score: " + score);
            floatLabels.add(new FloatLabel("+1", pacManCol * CELL + CELL / 2, pacManRow * CELL));
            grid[pacManRow][pacManCol] = '-';
            if (pelletsCollected >= numberOfPellets) { levelUp(); return; }
        }

        if (cell == 'I') {
            powerUpActive   = true;
            powerUpTurnsLeft = POWER_UP_DURATION;
            grid[pacManRow][pacManCol] = '-';
            floatLabels.add(new FloatLabel("POWER!", pacManCol * CELL + CELL / 2, pacManRow * CELL));
        }

        moveGhosts();

        // Post-ghost-move collision check
        for (double[] g : ghosts) {
            if ((int) g[0] == pacManRow && (int) g[1] == pacManCol && !powerUpActive) {
                gameOver("A ghost caught Pac-Man!"); return;
            }
        }

        // Keep protection for five subsequent moves, including each collision check.
        if (powerUpActive && cell != 'I' && --powerUpTurnsLeft <= 0) {
            powerUpActive = false;
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────────
    private void queueMove(int keyCode) {
        if (paused) return;
        switch (keyCode) {
            case KeyEvent.VK_W, KeyEvent.VK_UP    -> { pendingDRow = -1; pendingDCol =  0; facingAngle = 90; }
            case KeyEvent.VK_S, KeyEvent.VK_DOWN  -> { pendingDRow =  1; pendingDCol =  0; facingAngle = 270; }
            case KeyEvent.VK_A, KeyEvent.VK_LEFT  -> { pendingDRow =  0; pendingDCol = -1; facingAngle = 180; }
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> { pendingDRow =  0; pendingDCol =  1; facingAngle = 0;   }
        }
    }

    // ── Game logic ────────────────────────────────────────────────────────────
    private void initializeGame() {
        for (int i = 0; i < ROWS; i++) for (int j = 0; j < COLS; j++) grid[i][j] = '-';
        ghosts.clear(); floatLabels.clear();
        moving = false; animStep = 0;
        pendingDRow = 0; pendingDCol = 0;
        pacManRow = 5; pacManCol = 5;
        pacX = pacManCol * CELL; pacY = pacManRow * CELL;
        targetRow = pacManRow; targetCol = pacManCol;
        pelletsCollected = 0;
        numberOfPellets  = Math.min(3 + level, 24); // bounded by the fixed board size
        powerUpActive    = false;
        powerUpTurnsLeft = 0;

        int obstacleCount = Math.min(4 + level, 12); // start with 5, cap at 12
        int powerUpCount  = Math.max(3 - level / 3, 1); // start with 3 power-ups, reduce over time
        int ghostCount    = Math.min(1 + (level - 1) / 3, 8);

        placeObstacles(obstacleCount);
        placeItems('o', numberOfPellets);
        placeItems('I', powerUpCount);
        initGhosts(ghostCount);
    }

    private void levelUp() {
        level++;
        levelLabel.setText("Level: " + level);
        flashAlpha = 0.8f;
        initializeGame();
    }

    private void gameOver(String msg) {
        withGamePaused(() -> {
            moving = false;
            if (frame != null) JOptionPane.showMessageDialog(frame,
                    msg + "\nFinal Score: " + score, "Game Over", JOptionPane.INFORMATION_MESSAGE);
            resetGame();
        });
    }

    private void confirmNewGame() {
        withGamePaused(() -> {
            if (JOptionPane.showConfirmDialog(frame, "Start a new game?", "New Game",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) resetGame();
        });
    }

    private void confirmQuit() {
        withGamePaused(() -> {
            if (JOptionPane.showConfirmDialog(frame, "Quit?", "Quit",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) System.exit(0);
        });
    }

    private void resetGame() {
        score = 0; level = 1;
        scoreLabel.setText("Score: 0"); levelLabel.setText("Level: 1");
        initializeGame();
    }

    private void withGamePaused(Runnable action) {
        boolean wasRunning = gameTimer != null && gameTimer.isRunning();
        paused = true;
        if (gameTimer != null) gameTimer.stop();
        try {
            action.run();
        } finally {
            paused = false;
            if (wasRunning) gameTimer.start();
        }
    }

    private boolean ghostAt(int row, int col) {
        for (double[] ghost : ghosts) {
            if ((int) ghost[0] == row && (int) ghost[1] == col) return true;
        }
        return false;
    }

    private boolean canGhostOccupy(double[] movingGhost, int row, int col) {
        if (grid[row][col] == '#') return false;
        for (double[] ghost : ghosts) {
            if (ghost != movingGhost && (int) ghost[0] == row && (int) ghost[1] == col) return false;
        }
        return true;
    }

    private void moveGhosts() {
        // Ghost intelligence scales with level:
        // Starts at 20% chase behaviour, increases by 15 percentage points per level.
        double chaseChance = Math.min(0.2 + (level - 1) * 0.15, 1.0);

        // Ghost move frequency: early levels ghosts skip turns to feel slower
        // Starts at 50% of turns, increases by 12 percentage points per level.
        double moveChance = Math.min(0.5 + (level - 1) * 0.12, 1.0);

        for (double[] g : ghosts) {
            // Skip this ghost's turn based on move frequency
            if (random.nextDouble() > moveChance) continue;

            int gr = (int) g[0], gc = (int) g[1];
            int rowDist = Math.abs(gr - pacManRow);
            int colDist = Math.abs(gc - pacManCol);

            int primaryDRow = 0, primaryDCol = 0;
            int fallbackDRow = 0, fallbackDCol = 0;

            if (powerUpActive) {
                // Flee: move away from Pac-Man
                if (rowDist <= colDist) {
                    primaryDRow = (gr <= pacManRow) ? -1 : 1;
                    fallbackDCol = (gc <= pacManCol) ? -1 : 1;
                } else {
                    primaryDCol = (gc <= pacManCol) ? -1 : 1;
                    fallbackDRow = (gr <= pacManRow) ? -1 : 1;
                }
            } else if (random.nextDouble() < chaseChance) {
                // Smart chase: move toward Pac-Man on the farther axis
                if (rowDist >= colDist) {
                    primaryDRow = (gr < pacManRow) ? 1 : -1;
                    fallbackDCol = (gc < pacManCol) ? 1 : (gc > pacManCol) ? -1 : 0;
                } else {
                    primaryDCol = (gc < pacManCol) ? 1 : -1;
                    fallbackDRow = (gr < pacManRow) ? 1 : (gr > pacManRow) ? -1 : 0;
                }
            } else {
                // Dumb random move — pick a random direction
                int[][] allDirs = {{-1,0},{1,0},{0,-1},{0,1}};
                int[] chosen = allDirs[random.nextInt(4)];
                primaryDRow = chosen[0];
                primaryDCol = chosen[1];
            }

            // Try primary direction first
            int nr = Math.max(0, Math.min(ROWS - 1, gr + primaryDRow));
            int nc = Math.max(0, Math.min(COLS - 1, gc + primaryDCol));

            if (canGhostOccupy(g, nr, nc)) {
                g[0] = nr; g[1] = nc;
            } else if (fallbackDRow != 0 || fallbackDCol != 0) {
                // Try fallback direction (the other axis)
                nr = Math.max(0, Math.min(ROWS - 1, gr + fallbackDRow));
                nc = Math.max(0, Math.min(COLS - 1, gc + fallbackDCol));
                if (canGhostOccupy(g, nr, nc)) {
                    g[0] = nr; g[1] = nc;
                } else {
                    // Both blocked — try all 4 directions randomly to escape
                    int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
                    java.util.Collections.shuffle(java.util.Arrays.asList(dirs), random);
                    for (int[] d : dirs) {
                        int tr = Math.max(0, Math.min(ROWS - 1, gr + d[0]));
                        int tc = Math.max(0, Math.min(COLS - 1, gc + d[1]));
                        if (canGhostOccupy(g, tr, tc)) {
                            g[0] = tr; g[1] = tc;
                            break;
                        }
                    }
                }
            }
        }
    }

    private void placeObstacles(int count) {
        List<int[]> candidates = emptyCells();
        java.util.Collections.shuffle(candidates, random);
        int placed = 0;
        for (int[] cell : candidates) {
            if (placed >= count) break;
            int r = cell[0], c = cell[1];
            grid[r][c] = '#';
            if (reachableCellCount() == ROWS * COLS - placed - 1) placed++;
            else grid[r][c] = '-';
        }
    }

    private void placeItems(char item, int count) {
        List<int[]> cells = emptyCells();
        java.util.Collections.shuffle(cells, random);
        if (count > cells.size()) throw new IllegalArgumentException("Too many items for the board");
        for (int i = 0; i < count; i++) {
            int[] cell = cells.get(i);
            grid[cell[0]][cell[1]] = item;
        }
    }

    private void initGhosts(int count) {
        List<int[]> cells = emptyCells();
        java.util.Collections.shuffle(cells, random);
        if (count > cells.size()) throw new IllegalArgumentException("Too many ghosts for the board");
        for (int i = 0; i < count; i++) {
            int[] cell = cells.get(i);
            int r = cell[0], c = cell[1];
            ghosts.add(new double[]{r, c, c * CELL, r * CELL, random.nextDouble() * Math.PI * 2});
        }
    }

    private List<int[]> emptyCells() {
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) {
            if (grid[r][c] == '-' && !(r == pacManRow && c == pacManCol) && !ghostAt(r, c)) {
                cells.add(new int[]{r, c});
            }
        }
        return cells;
    }

    private int reachableCellCount() {
        boolean[][] seen = new boolean[ROWS][COLS];
        List<int[]> queue = new ArrayList<>();
        queue.add(new int[]{pacManRow, pacManCol});
        seen[pacManRow][pacManCol] = true;
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < queue.size(); i++) {
            int[] cell = queue.get(i);
            for (int[] direction : directions) {
                int r = cell[0] + direction[0], c = cell[1] + direction[1];
                if (r >= 0 && r < ROWS && c >= 0 && c < COLS && !seen[r][c] && grid[r][c] != '#') {
                    seen[r][c] = true;
                    queue.add(new int[]{r, c});
                }
            }
        }
        return queue.size();
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private double lerp(double a, double b, double t) { return a + (b - a) * t; }

    // ── Inner classes ─────────────────────────────────────────────────────────

    /** Floating "+1" or "POWER!" text that rises and fades. */
    static class FloatLabel {
        String text; float x, y, alpha = 1f;
        FloatLabel(String t, float x, float y) { this.text = t; this.x = x; this.y = y; }
        void tick() { y -= 1.2f; alpha -= 0.03f; }
        boolean dead() { return alpha <= 0; }
    }

    /** Custom rendering panel. */
    class GamePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final Color WALL_COLOR   = new Color(30, 80, 200);
        private static final Color PELLET_COLOR = new Color(255, 220, 150);
        private static final Color POWER_COLOR  = new Color(100, 200, 255);
        private static final Color GHOST_COLOR  = new Color(255, 70, 70);
        private static final Color FLEE_COLOR   = new Color(60, 60, 220);

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw grid cells
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    int x = c * CELL, y = r * CELL;
                    // subtle grid lines
                    g.setColor(new Color(20, 20, 20));
                    g.fillRect(x, y, CELL, CELL);
                    g.setColor(new Color(40, 40, 40));
                    g.drawRect(x, y, CELL, CELL);

                    switch (grid[r][c]) {
                        case '#' -> drawWall(g, x, y);
                        case 'o' -> drawPellet(g, x, y);
                        case 'I' -> drawPowerUp(g, x, y);
                    }
                }
            }

            // Draw ghosts
            for (double[] gh : ghosts) drawGhost(g, gh);

            // Draw Pac-Man (animated pixel position)
            drawPacMan(g, (int) Math.round(pacX), (int) Math.round(pacY));

            // Floating score labels
            g.setFont(new Font("Arial", Font.BOLD, 14));
            for (FloatLabel fl : floatLabels) {
                g.setColor(new Color(1f, 1f, 0f, fl.alpha));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(fl.text, fl.x - fm.stringWidth(fl.text) / 2f, fl.y);
            }

            // Level-up flash overlay
            if (flashAlpha > 0) {
                g.setColor(new Color(1f, 1f, 1f, flashAlpha));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        }

        private void drawWall(Graphics2D g, int x, int y) {
            g.setColor(WALL_COLOR);
            g.fillRoundRect(x + 2, y + 2, CELL - 4, CELL - 4, 10, 10);
            g.setColor(new Color(80, 130, 255));
            g.setStroke(new BasicStroke(2));
            g.drawRoundRect(x + 2, y + 2, CELL - 4, CELL - 4, 10, 10);
            g.setStroke(new BasicStroke(1));
        }

        private void drawPellet(Graphics2D g, int x, int y) {
            int sz = 10;
            g.setColor(PELLET_COLOR);
            g.fillOval(x + CELL / 2 - sz / 2, y + CELL / 2 - sz / 2, sz, sz);
            g.setColor(Color.WHITE);
            g.fillOval(x + CELL / 2 - 2, y + CELL / 2 - 4, 3, 3); // shine
        }

        private void drawPowerUp(Graphics2D g, int x, int y) {
            // Pulsing star shape
            double pulse = 1.0 + 0.15 * Math.sin(System.currentTimeMillis() / 200.0);
            int sz = (int) (18 * pulse);
            g.setColor(POWER_COLOR);
            g.fillOval(x + CELL / 2 - sz / 2, y + CELL / 2 - sz / 2, sz, sz);
            g.setColor(Color.WHITE);
            g.fillOval(x + CELL / 2 - 3, y + CELL / 2 - 5, 4, 4);
        }

        private void drawPacMan(Graphics2D g, int x, int y) {
            int pad = 4;
            int sz  = CELL - pad * 2;
            int cx  = x + pad, cy = y + pad;
            int startAngle = facingAngle + mouthAngle / 2;
            int arcAngle   = 360 - mouthAngle;

            // Shadow
            g.setColor(new Color(0, 0, 0, 80));
            g.fillArc(cx + 3, cy + 3, sz, sz, startAngle, arcAngle);

            // Body
            g.setColor(Color.YELLOW);
            g.fillArc(cx, cy, sz, sz, startAngle, arcAngle);

            // Eye
            double eyeRad = Math.toRadians(facingAngle + 60);
            int ex = (int) (cx + sz / 2 + (sz / 2 - 8) * Math.cos(eyeRad));
            int ey = (int) (cy + sz / 2 - (sz / 2 - 8) * Math.sin(eyeRad));
            g.setColor(Color.BLACK);
            g.fillOval(ex - 3, ey - 3, 6, 6);
        }

        private void drawGhost(Graphics2D g, double[] gh) {
            int x = (int) Math.round(gh[2]) + 3;
            int y = (int) Math.round(gh[3]) + 3;
            int w = CELL - 6, h = CELL - 6;
            double wobble = gh[4];

            Color bodyColor = powerUpActive ? FLEE_COLOR : GHOST_COLOR;

            // Flicker when power-up about to expire
            if (powerUpActive && powerUpTurnsLeft == 1) {
                bodyColor = (System.currentTimeMillis() / 200 % 2 == 0) ? FLEE_COLOR : Color.WHITE;
            }

            // Body (rounded top)
            g.setColor(bodyColor);
            g.fillArc(x, y, w, h, 0, 180);
            g.fillRect(x, y + h / 2, w, h / 2);

            // Wavy bottom
            int segments = 3;
            int segW = w / segments;
            Path2D.Double wave = new Path2D.Double();
            wave.moveTo(x, y + h);
            for (int i = 0; i < segments; i++) {
                double phase = wobble + i * Math.PI;
                int midY = (int) (y + h - 5 + 4 * Math.sin(phase));
                wave.curveTo(x + i * segW + segW / 3, y + h - 8, x + i * segW + 2 * segW / 3, midY, x + (i + 1) * segW, y + h);
            }
            wave.lineTo(x, y + h); wave.closePath();
            g.fill(wave);

            // Eyes
            g.setColor(Color.WHITE);
            g.fillOval(x + w / 4 - 4, y + h / 3 - 4, 10, 10);
            g.fillOval(x + 3 * w / 4 - 6, y + h / 3 - 4, 10, 10);
            g.setColor(powerUpActive ? Color.RED : Color.BLUE);
            g.fillOval(x + w / 4 - 1, y + h / 3 - 1, 5, 5);
            g.fillOval(x + 3 * w / 4 - 3, y + h / 3 - 1, 5, 5);
        }
    }
}
