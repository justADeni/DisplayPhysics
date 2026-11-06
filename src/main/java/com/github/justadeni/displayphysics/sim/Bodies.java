package com.github.justadeni.displayphysics.sim;

public final class Bodies {
    public final int capacity;
    public int awake;

    public final float[] px, py, pz, vx, vy, vz, wx, wy, wz, qx, qy, qz, qw;
    public final float[] vpx, vpy, vpz, wpx, wpy, wpz;
    public final float[] invMass, invInertia;
    public final float[] drag, mu, restitution;
    public final float[] touch;
    public final boolean[] hard;
    public final int[] calm;
    public final int[] id, slot;

    private final float[][] floats;
    private final boolean[][] booleans;
    private final int[][] ints;

    public Bodies(int capacity) {
        this.capacity = capacity;
        this.awake = capacity;
        px = row(); py = row(); pz = row();
        vx = row(); vy = row(); vz = row();
        wx = row(); wy = row(); wz = row();
        qx = row(); qy = row(); qz = row(); qw = row();
        vpx = row(); vpy = row(); vpz = row();
        wpx = row(); wpy = row(); wpz = row();
        invMass = row(); invInertia = row();
        drag = row(); mu = row(); restitution = row();
        touch = row();
        hard = new boolean[capacity];
        calm = new int[capacity];
        id = new int[capacity];
        slot = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            id[i] = i;
            slot[i] = i;
        }
        floats = new float[][]{px, py, pz, vx, vy, vz, wx, wy, wz, qx, qy, qz, qw, vpx, vpy, vpz, wpx, wpy, wpz,
                invMass, invInertia, drag, mu, restitution, touch};
        booleans = new boolean[][]{hard};
        ints = new int[][]{calm, id};
    }

    private float[] scratchFloats;
    private boolean[] scratchBooleans;
    private int[] scratchInts;

    public void permute(int[] order, int n) {
        if (scratchFloats == null) {
            scratchFloats = new float[capacity];
            scratchBooleans = new boolean[capacity];
            scratchInts = new int[capacity];
        }
        for (float[] array : floats) {
            for (int k = 0; k < n; k++)
                scratchFloats[k] = array[order[k]];
            System.arraycopy(scratchFloats, 0, array, 0, n);
        }
        for (boolean[] array : booleans) {
            for (int k = 0; k < n; k++)
                scratchBooleans[k] = array[order[k]];
            System.arraycopy(scratchBooleans, 0, array, 0, n);
        }
        for (int[] array : ints) {
            for (int k = 0; k < n; k++)
                scratchInts[k] = array[order[k]];
            System.arraycopy(scratchInts, 0, array, 0, n);
        }
        for (int k = 0; k < n; k++)
            slot[id[k]] = k;
    }

    private int rows;

    private float[] row() {
        return new float[capacity + 16 * (rows++ % 16)];
    }

    public void swap(int a, int b) {
        if (a == b)
            return;
        for (float[] array : floats) {
            float t = array[a];
            array[a] = array[b];
            array[b] = t;
        }
        for (boolean[] array : booleans) {
            boolean t = array[a];
            array[a] = array[b];
            array[b] = t;
        }
        for (int[] array : ints) {
            int t = array[a];
            array[a] = array[b];
            array[b] = t;
        }
        slot[id[a]] = a;
        slot[id[b]] = b;
    }

    public float speedSquared(int s) {
        return vx[s] * vx[s] + vy[s] * vy[s] + vz[s] * vz[s];
    }
}
