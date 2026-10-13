package local.morphe.nomount;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Builds Morphe's mount / unmount / check commands (as in RootInstaller.kt on dev) and runs them through the hook. */
public class T {
    static String q(String s) { return "'" + s + "'"; }

    public static void main(String[] a) throws Exception {
        String sp = q(a[0]), pp = q(a[1]), pkg = a[2], which = a[3];
        String check = "mount | grep -F " + sp;
        String unmountLoops =
                "for pid in $(pidof zygote64) $(pidof zygote) 99999; do\n"
                + "  while grep -qF \" " + sp + " \" \"/proc/$pid/mountinfo\" &&\n"
                + "    nsenter -t \"$pid\" -m -- umount -l " + sp + " 2>/dev/null; do :; done;\n"
                + "done;\n"
                + "while grep -qF \" " + sp + " \" /proc/self/mountinfo &&\n"
                + "    umount -l " + sp + " 2>/dev/null; do :; done;\n"
                + "true";
        String unmount = unmountLoops + "; am force-stop " + q(pkg);
        String mount = "chcon u:object_r:apk_data_file:s0 " + pp + "; " + unmountLoops + "; "
                + "mount -o bind " + pp + " " + sp + "; "
                + "for zpid in $(pidof zygote64) $(pidof zygote); do\n"
                + " nsenter -t \"$zpid\" -m -- mount -o bind " + pp + " " + sp + " 2>/dev/null || true;\n"
                + "done; am force-stop " + q(pkg);
        String c = which.equals("check") ? check : which.equals("unmount") ? unmount : mount;
        Files.write(Paths.get(a[4]), Hook.rewrite(c, 0).getBytes("UTF-8"));
    }
}
