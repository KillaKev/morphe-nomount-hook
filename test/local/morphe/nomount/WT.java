package local.morphe.nomount;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Builds Morphe's stageModuleFiles command from the real script templates and runs it through the hook. */
public class WT {
    static String q(String s) { return "'" + s.replace("'", "'\"'\"'") + "'"; }

    public static void main(String[] a) throws Exception {
        String mod = a[0], tpl = a[1];
        String[] names = {"service.sh", "post-fs-data.sh", "module.prop"};
        StringBuilder sb = new StringBuilder("mkdir -p " + q(mod));
        for (String n : names) {
            String c = new String(Files.readAllBytes(Paths.get(tpl, n)), "UTF-8").replace("\r\n", "\n")
                    .replace("__PKG_NAME__", "com.google.android.youtube").replace("__VERSION__", "21.20.405")
                    .replace("__MANAGER_PKG__", "app.morphe.manager").replace("__MODULE_ID__", "x")
                    .replace("__LABEL__", "YouTube");
            sb.append(" && printf '%s' ").append(q(c)).append(" > ").append(q(mod + "/" + n + ".tmp"));
        }
        sb.append(" && printf '%s' ").append(q("/data/app/x/base.apk\n")).append(" > ").append(q(mod + "/stock-paths.txt.tmp"));
        String orig = sb.toString();
        Files.write(Paths.get(a[2]), orig.getBytes("UTF-8"));
        Files.write(Paths.get(a[3]), Hook.rewrite(orig, 10382).getBytes("UTF-8"));
    }
}
