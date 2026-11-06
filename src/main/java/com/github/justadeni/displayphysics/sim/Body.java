package com.github.justadeni.displayphysics.sim;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class Body {
    final Vector3f x = new Vector3f();
    final Vector3f v = new Vector3f();
    final Vector3f w = new Vector3f();
    final Vector3f vp = new Vector3f();
    final Vector3f wp = new Vector3f();
    final Quaternionf q = new Quaternionf();

    float invMass;
    final Matrix3f invInertiaBody = new Matrix3f();
    final Matrix3f invInertia = new Matrix3f();

    private final Matrix3f rot = new Matrix3f();
    private final Matrix3f rotT = new Matrix3f();
    private final Matrix3f tmp = new Matrix3f();
    private final Vector3f scratch = new Vector3f();
    private final Vector3f scratch2 = new Vector3f();
    private final Quaternionf dq = new Quaternionf();

    void updateInertia() {
        q.get(rot);
        rot.mul(invInertiaBody, tmp);
        rot.transpose(rotT);
        tmp.mul(rotT, invInertia);
    }

    Vector3f velocityAt(Vector3f r, Vector3f out) {
        return w.cross(r, out).add(v);
    }

    Vector3f pseudoVelocityAt(Vector3f r, Vector3f out) {
        return wp.cross(r, out).add(vp);
    }

    void applyImpulse(Vector3f r, float px, float py, float pz) {
        v.add(px * invMass, py * invMass, pz * invMass);
        r.cross(px, py, pz, scratch);
        invInertia.transform(scratch);
        w.add(scratch);
    }

    void applyPseudoImpulse(Vector3f r, float px, float py, float pz) {
        vp.add(px * invMass, py * invMass, pz * invMass);
        r.cross(px, py, pz, scratch);
        invInertia.transform(scratch);
        wp.add(scratch);
    }

    float inverseEffectiveMass(Vector3f r, Vector3f d) {
        r.cross(d, scratch);
        invInertia.transform(scratch);
        scratch.cross(r, scratch2);
        return invMass + scratch2.dot(d);
    }

    private static final float MAX_SPEED = 40f, MAX_SPIN = 25f;

    void integrate(float dt) {
        if (v.lengthSquared() > MAX_SPEED * MAX_SPEED)
            v.normalize(MAX_SPEED);
        if (w.lengthSquared() > MAX_SPIN * MAX_SPIN)
            w.normalize(MAX_SPIN);

        x.add((v.x + vp.x) * dt, (v.y + vp.y) * dt, (v.z + vp.z) * dt);

        float ax = w.x + wp.x, ay = w.y + wp.y, az = w.z + wp.z;
        float speed = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (speed > 1e-6f) {
            dq.fromAxisAngleRad(ax / speed, ay / speed, az / speed, speed * dt);
            q.premul(dq).normalize();
        }

        vp.zero();
        wp.zero();
    }
}
