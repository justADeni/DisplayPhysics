package com.github.justadeni.displayphysics.sim;

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
        System.out.printf("%s %s %d %s: best %.1f ns/piece%n", a[0], a[1], n, a[3], best);
    }
}
