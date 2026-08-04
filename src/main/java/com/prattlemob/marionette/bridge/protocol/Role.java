package com.prattlemob.marionette.bridge.protocol;

/**
 * Connection roles (see protocol/v1.md, M2.4). "director" is a reserved
 * wire name with no constant here: fromWire returns null for it, so it is
 * rejected as unsupported_role until it is designed.
 */
public enum Role {
    CONTROLLER("controller"),
    OBSERVER("observer");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    /** The string that goes on the wire in the hello role field. */
    public String wire() {
        return wire;
    }

    /** The role for a wire string, or null when unknown (unsupported_role). */
    public static Role fromWire(String wire) {
        for (Role role : values()) {
            if (role.wire.equals(wire)) {
                return role;
            }
        }
        return null;
    }
}
