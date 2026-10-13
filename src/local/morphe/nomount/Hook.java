package local.morphe.nomount;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Morphe's root install decides everything from the mount table: it bind mounts the patched APK
 * (RootInstaller.mount), checks "mount | grep -F <apk>" (isAppMounted) and lifts the mount with
 * umount loops (unmount). Every one of those goes through RootInstaller.execute(String...), so the
 * shell commands are rewritten there:
 *   mount -o bind SRC DST           -> nm rule add DST SRC
 *   nsenter ... mount -o bind       -> no-op (rules are global, not per namespace)
 *   mount (no args, for the check)  -> real output + one bind-style line per Morphe NoMount rule
 *   umount -l DST                   -> nm rule del DST (Morphe's loops never reach umount, since
 *                                      NoMount leaves nothing in mountinfo)
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TARGET_PKG = "app.morphe.manager";
    private static final String NM = "/data/adb/modules/nomount/bin/nm";
    private static final Pattern UMOUNT_TARGET =
            Pattern.compile("umount -l ('[^']*'|[^\\s;&|'\"]+)");

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        final int uid = android.os.Process.myUid();
        Class<?> cont = XposedHelpers.findClass("kotlin.coroutines.Continuation", lpparam.classLoader);
        XposedHelpers.findAndHookMethod(
                "app.morphe.manager.domain.installer.RootInstaller", lpparam.classLoader,
                "execute", String[].class, cont,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String[] in = (String[]) param.args[0];
                            if (in == null) return;
                            String[] out = new String[in.length];
                            for (int i = 0; i < in.length; i++) {
                                out[i] = rewrite(in[i], uid);
                                if (in[i] != null && !in[i].equals(out[i])) {
                                    String head = in[i].length() > 160 ? in[i].substring(0, 160) : in[i];
                                    XposedBridge.log("MorpheNoMount: rewrote cmd len=" + in[i].length()
                                            + " :: " + head.replace("\n", "\\n"));
                                } else if (in[i] != null && in[i].startsWith("mkdir -p '") && in[i].contains("-morphe")
                                        && in[i].contains("service.sh")) {
                                    XposedBridge.log("MorpheNoMount: WARNING module script write not rewritten");
                                }
                            }
                            param.args[0] = out;
                        } catch (Throwable t) {
                            XposedBridge.log("MorpheNoMount: rewrite failed, running original: " + t);
                        }
                    }
                });
        XposedBridge.log("MorpheNoMount: hooked RootInstaller.execute");
    }

    // Boot scripts Morphe generates run with Morphe closed, so the shim is injected as they are
    // written (RootInstaller.writeModuleFiles: printf '%s' '<content>' > <module>/service.sh).
    private static final String SHIM_BEGIN = "# >>> morphe-nomount >>>";
    private static final String SHEBANG = "'#!/system/bin/sh\n";
    private static final String SHIM =
            SHIM_BEGIN + "\n"
            + "# Injected by the Morphe NoMount hook: bind mounts become NoMount rules (kernel level,\n"
            + "# valid in every mount namespace). Falls back to the real binaries without NoMount.\n"
            + "MNM_NM=" + NM + "\n"
            + "if [ -x \"$MNM_NM\" ] && \"$MNM_NM\" version >/dev/null 2>&1; then\n"
            + "  mount() {\n"
            + "    if [ \"$1\" = \"-o\" ] && [ \"$2\" = \"bind\" ] && [ -n \"$4\" ]; then\n"
            + "      \"$MNM_NM\" rule add \"$4\" \"$3\"\n"
            + "    else\n"
            + "      command mount \"$@\"\n"
            + "    fi\n"
            + "  }\n"
            + "  umount() {\n"
            + "    command umount \"$@\"\n"
            + "    mnm_rc=$?\n"
            + "    for mnm_a in \"$@\"; do\n"
            + "      case \"$mnm_a\" in /*) \"$MNM_NM\" rule del \"$mnm_a\" >/dev/null 2>&1 ;; esac\n"
            + "    done\n"
            + "    return $mnm_rc\n"
            + "  }\n"
            + "  nsenter() {\n"
            + "    case \" $* \" in *\" -- mount -o bind \"*) return 0 ;; esac\n"
            + "    command nsenter \"$@\"\n"
            + "  }\n"
            + "fi\n"
            + "# <<< morphe-nomount <<<\n";

    // Stock system stubs that must not exist, so the package manager has no stub to compare the
    // patched version against at boot. Written into the app's Morphe module as a NoMount
    // whiteout (.replace) and removed with the module.
    private static final String[][] STUB_WHITEOUTS = {
            {"com.google.android.youtube-morphe", "system/product/app/YouTube"},
    };
    private static final Pattern MODULE_MKDIR = Pattern.compile("^mkdir -p '([^']*/([^'/]+-morphe))'");

    private static String shellEscape(String s) {
        return s.replace("'", "'\"'\"'");
    }

    /** Rewrites Morphe's module-file write command; returns it unchanged when it is not one. */
    static String rewriteModuleWrite(String cmd) {
        Matcher m = MODULE_MKDIR.matcher(cmd);
        if (!m.find()) return cmd;
        String moduleDir = m.group(1), moduleId = m.group(2);
        String out = cmd;
        if (cmd.contains("/service.sh") || cmd.contains("/post-fs-data.sh")) {
            if (!cmd.contains(SHIM_BEGIN)) out = out.replace(SHEBANG, SHEBANG + shellEscape(SHIM));
        }
        for (String[] w : STUB_WHITEOUTS) {
            if (!w[0].equals(moduleId)) continue;
            String dir = moduleDir + "/" + w[1];
            out += " && mkdir -p '" + dir + "' && : > '" + dir + "/.replace'";
        }
        return out;
    }

    static String rewrite(String cmd, int managerUid) {
        if (cmd == null) return null;
        if (cmd.startsWith("mkdir -p '")) return rewriteModuleWrite(cmd);
        boolean bind = cmd.contains("mount -o bind ");
        boolean umount = cmd.contains("umount -l ");
        boolean check = cmd.startsWith("mount | grep -F");
        if (!bind && !umount && !check) return cmd;

        StringBuilder delRules = new StringBuilder();
        if (umount) {
            LinkedHashSet<String> targets = new LinkedHashSet<>();
            Matcher m = UMOUNT_TARGET.matcher(cmd);
            while (m.find()) targets.add(m.group(1));
            for (String t : targets) {
                delRules.append("  \"$MNM\" rule del ").append(t).append(" >/dev/null 2>&1\n");
            }
        }

        String prelude =
                "MNM=" + NM + "\n"
                + "if [ -x \"$MNM\" ] && \"$MNM\" version >/dev/null 2>&1; then\n"
                + "  mount() {\n"
                + "    if [ \"$1\" = -o ] && [ \"$2\" = bind ] && [ -n \"$4\" ]; then \"$MNM\" rule add \"$4\" \"$3\"\n"
                + "    elif [ $# -eq 0 ]; then command mount; \"$MNM\" rule list 2>/dev/null"
                + " | grep -E -- '-> /data/adb/modules/[^ ]+-morphe/'"
                + " | sed 's/^\\(.*\\) -> \\(.*\\)$/\\2 on \\1 type none (rw,bind)/'\n"
                + "    else command mount \"$@\"; fi\n"
                + "  }\n"
                + "  nsenter() { case \" $* \" in *\" -- mount -o bind \"*) return 0;; esac; command nsenter \"$@\"; }\n"
                + delRules
                + "fi\n";
        return prelude + cmd + launcherRefresh(cmd);
    }

    private static final Pattern FORCE_STOP = Pattern.compile("am force-stop '?([A-Za-z][A-Za-z0-9_.]*)'?");

    /**
     * The launcher caches an icon when the package changes and never looks at the APK again, so
     * the stock icon cached while Morphe reinstalls the stock app outlives the mount that swaps
     * the patched APK back in (and the reverse after an unmount). Toggling the app's launcher
     * activity sends a package-changed broadcast, which makes the launcher rebuild the icon.
     * Runs detached after the original command and keeps its exit status.
     */
    private static String launcherRefresh(String cmd) {
        if (!cmd.contains("mount -o bind ") && !cmd.contains("umount -l ")) return "";
        Matcher m = FORCE_STOP.matcher(cmd);
        String pkg = null;
        while (m.find()) pkg = m.group(1);
        if (pkg == null) return "";
        return "\nmnm_rc=$?\n"
                + "(\n"
                + "  mnm_c=$(cmd package resolve-activity --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER " + pkg + " 2>/dev/null | tail -1)\n"
                + "  case \"$mnm_c\" in */*)\n"
                + "    sleep 3\n"
                + "    trap 'pm enable --user 0 \"$mnm_c\" >/dev/null 2>&1' EXIT\n"
                + "    pm disable --user 0 \"$mnm_c\" >/dev/null 2>&1\n"
                + "    sleep 1 ;;\n"
                + "  esac\n"
                + ") >/dev/null 2>&1 &\n"
                + "(exit $mnm_rc)\n";
    }
}
