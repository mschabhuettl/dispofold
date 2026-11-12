package app.dispofold.desktop.launcher;

import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.Console;
import java.util.Arrays;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/** For {@code --change-password}: Swing dialogs when a display exists, else stderr and the console. */
final class SystemUi implements Ui {

    /** Same as DatabaseKey.MIN_PASSWORD_LENGTH, which the app enforces for a new database. */
    static final int MIN_LENGTH = 12;
    private static final String TITLE = "Dispofold";
    static final String WARNING = "Dispofold verschlüsselt alle Daten mit diesem Passwort. "
            + "Ohne das Passwort sind die Daten verloren, es gibt keine Wiederherstellung.";

    private final boolean gui = !GraphicsEnvironment.isHeadless();

    @Override
    public void info(String code, String text) {
        System.err.println(text);
        if (gui) {
            onEdt(() -> JOptionPane.showMessageDialog(null, text, TITLE, JOptionPane.INFORMATION_MESSAGE));
        }
    }

    @Override
    public void warn(String code, Map<String, Object> params, String text) {
        System.err.println(text);
        if (gui) {
            onEdt(() -> JOptionPane.showMessageDialog(null, text, TITLE, JOptionPane.WARNING_MESSAGE));
        }
    }

    @Override
    public void fail(String code, Map<String, Object> params, String text) {
        System.err.println(text);
        if (gui) {
            onEdt(() -> JOptionPane.showMessageDialog(null, text, TITLE, JOptionPane.ERROR_MESSAGE));
        }
    }

    @Override
    public String password(boolean firstStart, boolean retry) {
        return gui ? dialog(firstStart, retry) : console(firstStart, retry);
    }

    private String dialog(boolean firstStart, boolean retry) {
        String[] result = new String[1];
        onEdt(() -> {
            String problem = retry ? "Das Passwort ist falsch." : null;
            while (true) {
                JPasswordField first = new JPasswordField(24);
                JPasswordField second = new JPasswordField(24);
                JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
                if (problem != null) {
                    panel.add(new JLabel(problem));
                }
                if (firstStart) {
                    panel.add(new JLabel("<html><body style='width:320px'>Erster Start: Bitte ein Passwort festlegen"
                            + " (mindestens " + MIN_LENGTH + " Zeichen).<br><b>" + WARNING + "</b></body></html>"));
                    panel.add(new JLabel("Passwort:"));
                    panel.add(first);
                    panel.add(new JLabel("Passwort wiederholen:"));
                    panel.add(second);
                } else {
                    panel.add(new JLabel("Passwort:"));
                    panel.add(first);
                }
                int choice = JOptionPane.showConfirmDialog(null, panel, TITLE, JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) {
                    return;
                }
                char[] a = first.getPassword();
                problem = check(firstStart, a, second.getPassword());
                if (problem == null) {
                    result[0] = new String(a);
                    return;
                }
            }
        });
        return result[0];
    }

    private static String console(boolean firstStart, boolean retry) {
        Console c = System.console();
        if (c == null) {
            System.err.println("Kein Passwort: kein Fenster und keine Konsole. Bitte DISPOFOLD_PASSWORD setzen.");
            return null;
        }
        if (retry) {
            c.printf("Das Passwort ist falsch.%n");
        }
        if (firstStart) {
            c.printf("Erster Start: Bitte ein Passwort festlegen (mindestens %d Zeichen).%n%s%n", MIN_LENGTH, WARNING);
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            char[] a = c.readPassword("Passwort: ");
            if (a == null) {
                return null;
            }
            char[] b = firstStart ? c.readPassword("Passwort wiederholen: ") : a;
            String problem = check(firstStart, a, b == null ? new char[0] : b);
            if (problem == null) {
                return new String(a);
            }
            c.printf("%s%n", problem);
        }
        return null;
    }

    /** Null if acceptable, else the German reason. */
    static String check(boolean firstStart, char[] a, char[] b) {
        String s = new String(a);
        if (s.isEmpty()) {
            return "Bitte ein Passwort eingeben.";
        }
        if (s.contains("\n") || s.contains("\r")) {
            return "Das Passwort darf keinen Zeilenumbruch enthalten.";
        }
        if (firstStart && s.length() < MIN_LENGTH) {
            return "Das Passwort muss mindestens " + MIN_LENGTH + " Zeichen lang sein.";
        }
        if (firstStart && !Arrays.equals(a, b)) {
            return "Die beiden Eingaben stimmen nicht überein.";
        }
        return null;
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.err.println("Fenster konnte nicht angezeigt werden ("
                    + (e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName()) + ").");
        }
    }
}
