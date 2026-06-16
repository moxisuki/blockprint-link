package io.github.moxisuki.blockprint.link;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin wrapper around {@link java.util.logging.Logger}. FML redirects JUL
 * output to the Minecraft log file, so using this instead of
 * {@code System.out} means bridge messages actually appear in
 * {@code logs/latest.log}.
 */
public final class LogUtil {
    private static final Logger LOG = Logger.getLogger("blockprintlink");

    private LogUtil() {}

    public static void info(String msg) { LOG.info(msg); }
    public static void warn(String msg) { LOG.warning(msg); }
    public static void error(String msg, Throwable t) { LOG.log(Level.SEVERE, msg, t); }
    public static void error(String msg) { LOG.severe(msg); }
}
