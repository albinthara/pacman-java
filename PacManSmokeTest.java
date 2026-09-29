import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Dependency-free regression checks; no game window is opened. */
public class PacManSmokeTest {
    private static int checks;

    private static Object get(PacMan game, String name) throws Exception {
        Field field = PacMan.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(game);
    }

    private static void set(PacMan game, String name, Object value) throws Exception {
        Field field = PacMan.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(game, value);
    }

    private static void call(PacMan game, String name) throws Exception {
        Method method = PacMan.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(game);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> ghosts(PacMan game) throws Exception {
        return (List<double[]>) get(game, "ghosts");
    }

    private static void emptyBoard(PacMan game) throws Exception {
        char[][] grid = (char[][]) get(game, "grid");
        for (char[] row : grid) java.util.Arrays.fill(row, '-');
        ghosts(game).clear();
    }

    private static void verifyBoard(PacMan game) throws Exception {
        char[][] grid = (char[][]) get(game, "grid");
        int pr = (int) get(game, "pacManRow"), pc = (int) get(game, "pacManCol");
        check(grid[pr][pc] == '-', "Pac-Man must spawn on an empty cell");
        int pellets = 0, free = 0;
        for (char[] row : grid) for (char cell : row) {
            if (cell == 'o') pellets++;
            if (cell != '#') free++;
        }
        check(pellets == (int) get(game, "numberOfPellets"), "All level pellets must be placed");
        Set<String> occupied = new HashSet<>();
        for (double[] ghost : ghosts(game)) {
            int r = (int) ghost[0], c = (int) ghost[1];
            check(r >= 0 && r < 10 && c >= 0 && c < 10, "Ghost must be inside the grid");
            check(!(r == pr && c == pc), "Ghost must not spawn on Pac-Man");
            check(grid[r][c] == '-', "Ghost must spawn off walls and items");
            check(occupied.add(r + ":" + c), "Ghost starting positions must be unique");
        }
        boolean[][] seen = new boolean[10][10];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{pr, pc});
        seen[pr][pc] = true;
        int reached = 0;
        while (!queue.isEmpty()) {
            int[] cell = queue.remove();
            reached++;
            for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int r = cell[0] + d[0], c = cell[1] + d[1];
                if (r >= 0 && r < 10 && c >= 0 && c < 10 && grid[r][c] != '#' && !seen[r][c]) {
                    seen[r][c] = true;
                    queue.add(new int[]{r, c});
                }
            }
        }
        check(reached == free, "Every non-wall cell must be reachable");
    }

    private static void run(String[] args) throws Exception {
        PacMan game = new PacMan(false);
        for (int level : new int[]{1, 2, 3, 4, 7, 10, 20, 30, 1000}) {
            for (int seed = 0; seed < 100; seed++) {
                set(game, "random", new Random(seed));
                set(game, "level", level);
                call(game, "initializeGame");
                verifyBoard(game);
            }
        }

        set(game, "pendingDRow", 1);
        set(game, "pendingDCol", -1);
        set(game, "powerUpTurnsLeft", 3);
        call(game, "initializeGame");
        check((int) get(game, "pendingDRow") == 0 && (int) get(game, "pendingDCol") == 0,
                "Reset must clear queued movement");
        check((int) get(game, "powerUpTurnsLeft") == 0, "Reset must clear power-up state");

        emptyBoard(game);
        set(game, "score", 42);
        ghosts(game).add(new double[]{5, 5, 280, 280, 0});
        call(game, "resolveCell");
        check((int) get(game, "score") == 0, "Entering an unpowered ghost cell must trigger game over");
        check((int) get(game, "level") == 1, "Game over must reset the level");
        check(!(boolean) get(game, "paused"), "Game over must resume the game");

        emptyBoard(game);
        char[][] grid = (char[][]) get(game, "grid");
        grid[5][5] = 'I';
        call(game, "resolveCell");
        check((boolean) get(game, "powerUpActive"), "Power-up must activate");
        check((int) get(game, "powerUpTurnsLeft") == 5, "Pickup must not consume a protected turn");
        for (int turn = 1; turn <= 5; turn++) {
            call(game, "resolveCell");
            check((boolean) get(game, "powerUpActive") == (turn < 5), "Power-up duration: " + turn);
        }

        emptyBoard(game);
        grid[5][5] = 'o';
        set(game, "pelletsCollected", (int) get(game, "numberOfPellets") - 1);
        call(game, "resolveCell");
        check((int) get(game, "level") == 2, "Collecting the final pellet must advance the level");
        verifyBoard(game);

        emptyBoard(game);
        Method input = PacMan.class.getDeclaredMethod("queueMove", int.class);
        input.setAccessible(true);
        input.invoke(game, KeyEvent.VK_DOWN);
        check((int) get(game, "facingAngle") == 270, "Downward movement must face down in Java2D");
        call(game, "applyPendingMove");
        for (int frame = 0; frame < 8; frame++) call(game, "tick");
        check((int) get(game, "pacManRow") == 6 && (int) get(game, "pacManCol") == 5,
                "One queued movement must advance exactly one cell");

        grid[7][5] = '#';
        input.invoke(game, KeyEvent.VK_DOWN);
        call(game, "applyPendingMove");
        check(!(boolean) get(game, "moving"), "A wall must block movement");

        double[] ghost = new double[]{1, 2, 56, 56, 0};
        ghosts(game).add(ghost);
        call(game, "tick");
        check(ghost[2] > 56, "Ghost rendering must advance while Pac-Man is stationary");

        set(game, "level", 1);
        set(game, "score", 0);
        set(game, "flashAlpha", 0f);
        set(game, "facingAngle", 0);
        set(game, "mouthAngle", 45);
        set(game, "random", new Random(7));
        call(game, "initializeGame");
        JPanel panel = (JPanel) get(game, "gamePanel");
        panel.setSize(560, 560);
        BufferedImage preview = new BufferedImage(560, 560, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = preview.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 560, 560);
        panel.paint(graphics);
        graphics.dispose();
        if (args.length > 0) ImageIO.write(preview, "png", new File(args[0]));
        int yellowPixels = 0;
        for (int y = 0; y < 560; y++) for (int x = 0; x < 560; x++) {
            if (preview.getRGB(x, y) == Color.YELLOW.getRGB()) yellowPixels++;
        }
        check(yellowPixels > 1000, "Rendering must draw the yellow player on the board");
        System.out.println("PASS: " + checks + " checks across 900 generated boards, gameplay regressions and rendering.");
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { run(args); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
