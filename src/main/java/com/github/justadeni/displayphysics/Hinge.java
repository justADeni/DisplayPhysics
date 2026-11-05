package com.github.justadeni.displayphysics;

import org.joml.Vector3f;

/**
 * Makes the assembly start by tipping over an edge like a felled tree, before it is
 * released as a free body.
 *
 * @param pivotX       world position of the edge it tips over
 * @param pivotY       also the height of the assembly's base: blocks within 1.5 blocks of it never end the tip
 * @param pivotZ       see {@code pivotX}
 * @param axis         horizontal axis the assembly rotates about (it need not be normalised)
 * @param height       length of the assembly, which sets how fast it tips
 * @param initialAngle starting tilt in radians
 * @param releaseAngle tilt in radians at which it lets go and becomes a free body
 */
public record Hinge(double pivotX, double pivotY, double pivotZ, Vector3f axis, float height, float initialAngle, float releaseAngle) {
}
