package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {
    @Test
    void wireStringsMatchTheSpec() {
        assertEquals("invalid_json", ErrorCode.INVALID_JSON.wire());
        assertEquals("unknown_type", ErrorCode.UNKNOWN_TYPE.wire());
        assertEquals("invalid_field", ErrorCode.INVALID_FIELD.wire());
        assertEquals("unexpected_hello", ErrorCode.UNEXPECTED_HELLO.wire());
        assertEquals("hello_required", ErrorCode.HELLO_REQUIRED.wire());
        assertEquals("unsupported_version", ErrorCode.UNSUPPORTED_VERSION.wire());
        assertEquals("unsupported_role", ErrorCode.UNSUPPORTED_ROLE.wire());
        assertEquals("controller_attached", ErrorCode.CONTROLLER_ATTACHED.wire());
    }

    @Test
    void fatalityAndCloseCodesMatchTheSpec() {
        assertFalse(ErrorCode.INVALID_JSON.fatal());
        assertFalse(ErrorCode.UNKNOWN_TYPE.fatal());
        assertFalse(ErrorCode.INVALID_FIELD.fatal());
        assertFalse(ErrorCode.UNEXPECTED_HELLO.fatal());
        assertTrue(ErrorCode.HELLO_REQUIRED.fatal());
        assertEquals(1002, ErrorCode.HELLO_REQUIRED.closeCode());
        assertEquals(1002, ErrorCode.UNSUPPORTED_VERSION.closeCode());
        assertEquals(1002, ErrorCode.UNSUPPORTED_ROLE.closeCode());
        assertEquals(1013, ErrorCode.CONTROLLER_ATTACHED.closeCode());
    }
}
