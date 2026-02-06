package org.alexdev.unlimitednametags.nametags;

import me.clip.placeholderapi.PlaceholderAPI;
import net.jodah.expiringmap.ExpiringMap;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.config.Settings;
import org.apache.commons.jexl3.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConditionalManager {

    private final UnlimitedNameTags plugin;
    private final BlockingQueue<JexlEngine> jexlEnginePool;
    private final JexlContext jexlContext;
    private final Map<String, Object> cachedExpressions;
    private final Set<String> loggedFailedExpressions;

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d[\\d.,]*");

    public ConditionalManager(UnlimitedNameTags plugin) {
        this.plugin = plugin;
        this.jexlEnginePool = createJexlEnginePool();
        this.jexlContext = createJexlContext();
        this.cachedExpressions = ExpiringMap.builder()
                .expiration(5, TimeUnit.MINUTES)
                .build();
        this.loggedFailedExpressions = ConcurrentHashMap.newKeySet();
    }

    @NotNull
    private BlockingQueue<JexlEngine> createJexlEnginePool() {
        final BlockingQueue<JexlEngine> pool = new LinkedBlockingQueue<>(10);
        for (int i = 0; i < 10; i++) {
            pool.add(new JexlBuilder().debug(false).create());
        }
        return pool;
    }

    @NotNull
    private JexlContext createJexlContext() {
        return new MapContext();
    }

    public boolean evaluateExpression(@NotNull Settings.ConditionalModifier modifier, @NotNull Player player) {
        final String resolvedExpression = plugin.getPlaceholderManager().getPapiManager().isPapiEnabled() ?
                PlaceholderAPI.setPlaceholders(player, modifier.getExpression()) :
                modifier.getExpression();

        final String sanitizedExpression = normalizeNumberLiterals(resolvedExpression);

        final Boolean cached = cachedExpressions.getOrDefault(sanitizedExpression, null) instanceof Boolean b ? b : null;
        if (cached != null) {
            return cached;
        }

        JexlEngine jexlEngine = null;
        try {
            jexlEngine = jexlEnginePool.poll(1, TimeUnit.SECONDS);
            if (jexlEngine == null) {
                jexlEngine = new JexlBuilder().debug(false).create();
            }

            final Object result = jexlEngine.createExpression(sanitizedExpression).evaluate(jexlContext);
            final boolean boolResult = result instanceof Boolean bb && bb;

            cachedExpressions.put(sanitizedExpression, boolResult);

            return boolResult;
        } catch (Exception e) {
            if (loggedFailedExpressions.add(modifier.getExpression())) {
                plugin.getLogger().warning("Failed to evaluate expression '" + modifier.getExpression() +
                        "' (resolved to '" + sanitizedExpression + "'): " + e.getMessage());
            }
            return false;
        } finally {
            if (jexlEngine != null && !jexlEnginePool.offer(jexlEngine)) {
                plugin.getLogger().warning("JexlEngine pool is full. Discarding engine.");
            }
        }
    }

    private String normalizeNumberLiterals(String expression) {
        final Matcher matcher = NUMBER_PATTERN.matcher(expression);
        final StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            final String token = matcher.group();
            final String normalized = containsLocaleSeparators(token) ? normalizeNumberToken(token) : token;
            matcher.appendReplacement(buffer, normalized);
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean containsLocaleSeparators(String token) {
        return token.indexOf(',') >= 0 || token.indexOf('.') != token.lastIndexOf('.');
    }

    private String normalizeNumberToken(String token) {
        final boolean hasComma = token.indexOf(',') >= 0;
        final boolean hasDot = token.indexOf('.') >= 0;

        // Both separators present; decide decimal by the last occurrence.
        if (hasComma && hasDot) {
            final int lastComma = token.lastIndexOf(',');
            final int lastDot = token.lastIndexOf('.');
            final char decimalSeparator = lastComma > lastDot ? ',' : '.';

            final StringBuilder builder = new StringBuilder(token.length());
            for (char ch : token.toCharArray()) {
                if (Character.isDigit(ch) || ch == decimalSeparator) {
                    builder.append(ch == ',' ? '.' : ch);
                }
            }
            return builder.toString();
        }

        // Only commas -> normalize decimal or grouping.
        if (hasComma) {
            final String[] parts = token.split(",");
            if (parts.length == 1) {
                return token;
            }

            final StringBuilder integerPart = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                integerPart.append(parts[i]);
            }

            final String last = parts[parts.length - 1];
            if (last.length() == 3) {
                // Treat as grouping separators (e.g. 1,234,567)
                return integerPart.append(last).toString();
            }
            // Treat final comma as decimal separator (e.g. 319,46)
            return integerPart + "." + last;
        }

        // Only dots; clean up multiple thousand separators while keeping valid decimals intact.
        if (!hasDot) {
            return token;
        }

        final String[] parts = token.split("\\.");
        if (parts.length <= 2) {
            // Already valid (single decimal point or none)
            return token;
        }

        final StringBuilder integerPart = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            integerPart.append(parts[i]);
        }

        final String last = parts[parts.length - 1];
        if (last.length() == 3) {
            // Likely thousand separators only
            return integerPart.append(last).toString();
        }

        // Assume last dot is decimal separator
        return integerPart + "." + last;
    }
}
