package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ChatPolicyTest {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final ChatPolicy policy = new ChatPolicy(now::get);

    private ChatPolicy.Decision text(String text) {
        return policy.check(text, null, true, false, 5);
    }

    private ChatPolicy.Decision command(String command) {
        return policy.check(null, command, true, true, 5);
    }

    @Test
    void ordinaryChatIsNormalizedLikeTheChatScreen() {
        var decision = text("  hello \n\t world  ");
        assertTrue(decision.accepted());
        assertEquals("chat", decision.action());
        assertEquals("hello world", decision.content());
        assertNull(decision.retryAfterMs());
    }

    @Test
    void commandsAreDeniedByDefaultAndChatCanBeDisabled() {
        assertEquals("commands_disabled", policy.check(null, "time set day", true, false, 5).reason());
        assertEquals("chat_disabled", policy.check("hi", null, false, true, 5).reason());
        assertTrue(policy.check(null, "time set day", false, true, 5).accepted(), "switches are independent");
    }

    @Test
    void commandLosesOneOptionalSlash() {
        var plain = command("time set day");
        assertEquals("command", plain.action());
        assertEquals("time set day", plain.content());
        assertEquals("time set day", command("/time   set day").content());
        assertEquals("/say x", command("//say x").content(), "only one slash is removed");
        assertEquals("empty", command("/").reason());
    }

    @Test
    void textMustNotLookLikeACommand() {
        assertEquals("slash_prefix", text("/kill").reason());
        assertEquals("slash_prefix", text("   /kill").reason(), "checked after normalization");
        assertTrue(text("a /kill").accepted());
    }

    @Test
    void emptyAndOverlongContentIsRefusedNeverTruncated() {
        assertEquals("empty", text(" \n ").reason());
        assertTrue(text("x".repeat(256)).accepted());
        assertEquals("too_long", text("x".repeat(257)).reason());
        assertTrue(command("x".repeat(255)).accepted(), "the slash counts toward 256");
        assertEquals("too_long", command("x".repeat(256)).reason());
        assertTrue(text("a" + " ".repeat(400) + "b").accepted(), "length is measured after normalization");
    }

    @Test
    void charactersVanillaRejectsAreRefused() {
        assertEquals("illegal_character", text("§cred").reason());
        assertEquals("illegal_character", text("bell\u0007").reason());
        assertEquals("illegal_character", text("del\u007f").reason());
        assertEquals("illegal_character", command("say §x").reason());
        assertTrue(text("café ☃").accepted(), "non-ASCII text is allowed");
        assertTrue(ChatPolicy.allowedCharacter(' '));
        assertFalse(ChatPolicy.allowedCharacter('\u001f'));
    }

    @Test
    void rateWindowCountsChatAndCommandsTogether() {
        for (int i = 0; i < 3; i++) assertTrue(text("m" + i).accepted());
        assertTrue(command("say a").accepted());
        assertTrue(text("m4").accepted());
        var limited = text("m5");
        assertEquals("rate_limited", limited.reason());
        assertEquals(10_000L, limited.retryAfterMs());
        now.addAndGet(4_000_000_000L);
        assertEquals(6_000L, command("say b").retryAfterMs());
        now.addAndGet(6_000_000_000L);
        for (int i = 0; i < 5; i++) assertTrue(text("n" + i).accepted(), "all five left the 10 s window");
        assertEquals("rate_limited", text("n5").reason());
    }

    @Test
    void refusalsDoNotCountTowardTheRate() {
        for (int i = 0; i < 20; i++) {
            assertEquals("too_long", text("x".repeat(300)).reason());
            assertEquals("commands_disabled", policy.check(null, "x", true, false, 5).reason());
        }
        for (int i = 0; i < 5; i++) assertTrue(text("ok" + i).accepted());
    }

    @Test
    void loweredLimitAppliesLiveWithAnAccurateRetry() {
        for (int i = 0; i < 5; i++) {
            assertTrue(text("m" + i).accepted());
            now.addAndGet(1_000_000_000L);
        }
        // Five accepted at t=0..4 s, now t=5 s; a limit of 2 needs the four oldest gone
        // (the one at t=3 s leaves at t=13 s).
        var limited = policy.check("x", null, true, false, 2);
        assertEquals("rate_limited", limited.reason());
        assertEquals(8_000L, limited.retryAfterMs());
        now.addAndGet(7_999_000_000L);
        assertEquals("rate_limited", policy.check("x", null, true, false, 2).reason());
        now.addAndGet(1_000_000L);
        assertTrue(policy.check("x", null, true, false, 2).accepted());
    }

    @Test
    void retryIsNeverZero() {
        for (int i = 0; i < 5; i++) assertTrue(text("m" + i).accepted());
        now.addAndGet(9_999_999_999L);
        assertEquals(1L, text("late").retryAfterMs());
    }
}
