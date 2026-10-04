package com.prattlemob.marionette.control;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.LongSupplier;

/**
 * The {@code chat} request limits (protocol/v1.md, chat): config switches,
 * vanilla chat-screen normalization, content rules and a client-wide sliding
 * rate window. World and client chat restrictions are checked by the caller,
 * which owns Minecraft access. Pure Java so the limits stay unit-testable;
 * client-thread only.
 */
public final class ChatPolicy {
    /** The vanilla chat-box limit, in UTF-16 characters (a command counts its slash). */
    public static final int MAX_LENGTH = 256;
    public static final int WINDOW_SECONDS = 10;
    private static final long WINDOW_NANOS = WINDOW_SECONDS * 1_000_000_000L;

    /**
     * Outcome of one request. Accepted when {@code reason} is null: {@code action}
     * is "chat" or "command" and {@code content} what to send (a command without
     * its slash). {@code retryAfterMs} is set only for {@code rate_limited}.
     */
    public record Decision(String reason, String message, String action, String content, Long retryAfterMs) {
        public boolean accepted() {
            return reason == null;
        }

        static Decision refuse(String reason, String message) {
            return new Decision(reason, message, null, null, null);
        }
    }

    private final LongSupplier nanoTime;
    /** nanoTime of each accepted request still inside the window, oldest first. */
    private final ArrayDeque<Long> accepted = new ArrayDeque<>();

    public ChatPolicy(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Check one request against the limits, in protocol order, and count it if
     * accepted. Exactly one of {@code text} and {@code command} is non-null.
     */
    public Decision check(String text, String command, boolean allowChat, boolean allowCommands, int maxMessages) {
        boolean isCommand = command != null;
        if (!isCommand && !allowChat) {
            return Decision.refuse("chat_disabled", "chat is disabled by chat.allowChat");
        }
        if (isCommand && !allowCommands) {
            return Decision.refuse("commands_disabled", "commands are disabled by chat.allowCommands");
        }
        String content = normalize(isCommand ? command : text);
        if (isCommand && content.startsWith("/")) {
            content = content.substring(1);
        }
        if (content.isEmpty()) {
            return Decision.refuse("empty", "nothing left to send after normalization");
        }
        if (content.length() + (isCommand ? 1 : 0) > MAX_LENGTH) {
            return Decision.refuse("too_long", "longer than " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < content.length(); i++) {
            if (!allowedCharacter(content.charAt(i))) {
                return Decision.refuse("illegal_character", "contains a character chat does not allow");
            }
        }
        if (!isCommand && content.startsWith("/")) {
            return Decision.refuse("slash_prefix", "text must not start with '/'; send commands as \"command\"");
        }
        long now = nanoTime.getAsLong();
        while (!accepted.isEmpty() && now - accepted.peekFirst() >= WINDOW_NANOS) {
            accepted.pollFirst();
        }
        if (accepted.size() >= maxMessages) {
            // The request admitted next is the one after the (size - max + 1) oldest leave.
            Iterator<Long> oldest = accepted.iterator();
            long leaving = oldest.next();
            for (int i = 0; i < accepted.size() - maxMessages; i++) leaving = oldest.next();
            long waitNanos = leaving + WINDOW_NANOS - now;
            return new Decision("rate_limited", "more than " + maxMessages + " messages in " + WINDOW_SECONDS
                    + " seconds", null, null, Math.max(1L, (waitNanos + 999_999L) / 1_000_000L));
        }
        accepted.addLast(now);
        return new Decision(null, null, isCommand ? "command" : "chat", content, null);
    }

    /** The vanilla chat screen's normalization: trim, then one space per whitespace run. */
    public static String normalize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
            } else {
                if (pendingSpace) out.append(' ');
                pendingSpace = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Vanilla {@code StringUtil.isAllowedChatCharacter}: no section sign, control characters or DEL. */
    public static boolean allowedCharacter(char c) {
        return c != '§' && c >= ' ' && c != '\u007f';
    }
}
