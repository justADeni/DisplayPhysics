package com.github.justadeni.displayphysics.sim;

/**
 * The state of every piece in structure-of-arrays form: one {@code float[]} per component, so a kernel
 * streams through memory and a SIMD load fills a register with the same component of neighbouring pieces.
 *
 * <p>Pieces live in <i>slots</i>. The awake ones occupy slots {@code [0, awake)}, so kernels run over one
 * dense range and the cost of a tick tracks how much is moving, not how much exists. Pieces that fall
 * asleep are swapped out of that range; {@link #id} and {@link #slot} map between a slot and the piece's
 * stable index.
 *
 * <p>Every cube is isotropic (inertia {@code m/6} about any axis), so inertia is one scalar per piece.
 */
public final class Bodies {

    public final int capacity;
    /** Slots {@code [0, awake)} hold the pieces that are still moving. */
    public int awake;

    public final float[] px, py, pz, vx, vy, vz, wx, wy, wz, qx, qy, qz, qw;
    /** Pseudo velocities: the split-impulse push out of the terrain, which adds no energy. */
    public final float[] vpx, vpy, vpz, wpx, wpy, wpz;
    public final float[] invMass, invInertia;
    /** Linear drag coefficient, friction coefficient and bounciness: hard and soft pieces differ. */
    public final float[] drag, mu, restitution;
    /** 1 if the piece touched something this tick (it then resists rolling), else 0. */
    public final float[] touch;
    public final boolean[] hard;
    /** Consecutive calm ticks while touching something. */
    public final int[] calm;
    /** The piece living in each slot, and the slot each piece lives in. */
    public final int[] id, slot;

    private final float[][] floats;
    private final boolean[][] booleans;
    private final int[][] ints;

    public Bodies(int capacity) {
        this.capacity = capacity;
        this.awake = capacity;
        px = new float[capacity]; py = new float[capacity]; pz = new float[capacity];
        vx = new float[capacity]; vy = new float[capacity]; vz = new float[capacity];
        wx = new float[capacity]; wy = new float[capacity]; wz = new float[capacity];
        qx = new float[capacity]; qy = new float[capacity]; qz = new float[capacity]; qw = new float[capacity];
        vpx = new float[capacity]; vpy = new float[capacity]; vpz = new float[capacity];
        wpx = new float[capacity]; wpy = new float[capacity]; wpz = new float[capacity];
        invMass = new float[capacity]; invInertia = new float[capacity];
        drag = new float[capacity]; mu = new float[capacity]; restitution = new float[capacity];
        touch = new float[capacity];
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

    /**
     * Rearranges slots {@code [0, n)} so that slot {@code k} holds what slot {@code order[k]} held, which must be
     * a permutation of {@code [0, n)}. Sorting the pieces into spatial order this way puts neighbours next to each
     * other in memory, which is what the pair search and pair solver then read.
     */
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

    /** Exchanges two slots completely, keeping the id/slot maps consistent. */
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
