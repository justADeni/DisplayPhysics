package com.github.justadeni.displayphysics.sim;

/** Quick single-process timing of one kernel: Run variant scene count kernel(terrain|forces|integrate) seconds. */
public class Run {
    public static void main(String[] a) {
        var s = Check.make(a[1], Integer.parseInt(a[2]), a[0]);
        int kernel = switch (a[3]) { case "forces" -> 0; case "terrain" -> 1; default -> 2; };
        double seconds = Double.parseDouble(a[4]);
        int n = s.bodies().capacity;
        long end = System.nanoTime() + (long) (seconds * 1e9);
        double best = Double.MAX_VALUE;
        long tt = System.nanoTime();
        int reps = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            int inner = Math.max(1, 200000 / n);
            for (int r = 0; r < inner; r++)
                Check.run(kernel, s);
            double ns = (System.nanoTime() - t0) / (double) inner / n;
            best = Math.min(best, ns);
            if (++reps % 20 == 0)
                System.out.printf("%.1f%n", ns);
        }
        try {
            long[] tm = (long[]) Class.forName("com.github.justadeni.displayphysics.sim.VectorKernels").getField("TIME").get(null);
            if (tm[7] > 0) System.out.printf("per candidate: cull+probe split cull %.1f probe %.1f | per solved piece: load %.1f contacts %.1f iterate %.1f finish %.1f | candidates %d solved %d slots/vec %.2f%n",
                    tm[0] / (double) tm[5], tm[1] / (double) tm[5], tm[2] / (double) tm[7], tm[3] / (double) tm[7], tm[4] / (double) tm[7], tm[6] / (double) tm[7], tm[5], tm[7], tm[8] / (tm[7] / 16.0));
        } catch (ReflectiveOperationException e) { }
        System.out.printf("%s %s %d %s: best %.1f ns/piece%n", a[0], a[1], n, a[3], best);
    }
}
