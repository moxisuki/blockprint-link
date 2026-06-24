package io.github.moxisuki.blockprint.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around {@link org.slf4j.Logger}.
 *
 * SLF4J is the standard logging facade on every modern Minecraft loader
 * (NeoForge 1.21+, Forge 1.20.1+, Fabric 1.21+). Its output routes to
 * the loader's log4j2 config which writes to {@code logs/latest.log}.
 *
 * Earlier revisions used {@link java.util.logging.Logger}, which the
 * old Forge 1.20.1 FML happily redirected but NeoForge 1.21+ does
 * not — those messages silently vanished from {@code latest.log}.
 */
public final class LogUtil {
    private static final Logger LOG = LoggerFactory.getLogger("blockprintlink");

    private LogUtil() {}

    public static void info(String msg) { LOG.info(msg); }
    public static void warn(String msg) { LOG.warn(msg); }
    public static void error(String msg, Throwable t) { LOG.error(msg, t); }
    public static void error(String msg) { LOG.error(msg); }
}
