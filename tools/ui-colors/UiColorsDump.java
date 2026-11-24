import java.awt.Color;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Writes the running IDE's UIManager colors to the file named by the agent argument, as JBColor.namedColor
 * resolves them: one "key<TAB>AARRGGBB" line per String key with a color, then one "*<TAB>suffix<TAB>AARRGGBB"
 * line per entry of the theme's "*" map, in that map's iteration order (JBColor.findPatternMatch takes the first).
 */
public final class UiColorsDump {
  public static void agentmain(final String outputPath) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      try (final PrintWriter output = new PrintWriter(new FileWriter(outputPath))) {
        final Map<String, Color> colors = new TreeMap<>();
        for (final Object key : Collections.list(UIManager.getDefaults().keys())) {
          if (!(key instanceof String name) || name.equals("*")) continue;
          final Color color = UIManager.getColor(name);
          if (color != null) colors.put(name, color);
        }
        colors.forEach((name, color) -> output.println(name + "\t" + hex(color)));
        if (UIManager.get("*") instanceof Map<?, ?> wildcard) {
          for (final Map.Entry<?, ?> entry : wildcard.entrySet()) {
            if (entry.getKey() instanceof String suffix && entry.getValue() instanceof Color color) {
              output.println("*\t" + suffix + "\t" + hex(color));
            }
          }
        }
      } catch (final Exception writeFailure) {
        throw new RuntimeException(writeFailure);
      }
    });
  }

  private static String hex(final Color color) {
    return String.format("%08X", color.getRGB());
  }
}
