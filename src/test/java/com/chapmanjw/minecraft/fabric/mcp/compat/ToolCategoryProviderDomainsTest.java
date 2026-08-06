package com.chapmanjw.minecraft.fabric.mcp.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the provider-declared domain registry behind
 * {@link ToolCategory#registerProviderDomains(Map)} — the seam extension that lets an
 * external {@code ToolProvider} name tool domains outside the closed built-in map
 * (added after the live spike showed the compatibility filter hard-rejecting the
 * {@code jujutsu} domain).
 */
class ToolCategoryProviderDomainsTest {

    @AfterEach
    void resetProviderDomains() {
        ToolCategory.clearProviderDomains();
    }

    /** A registered provider domain resolves through both lookup passes. */
    @Test
    void providerDomainResolvesForToolNames() {
        List<String> rejected =
                ToolCategory.registerProviderDomains(
                        Map.of(
                                "acme", ToolCategory.SERVER,
                                "acme_multi", ToolCategory.WORLD));

        assertTrue(rejected.isEmpty(), "no built-in collision expected");
        // first-segment pass
        assertEquals(ToolCategory.SERVER, ToolCategory.forToolName("acme_status"));
        // multi-segment pass wins over the bare first segment
        assertEquals(ToolCategory.WORLD, ToolCategory.forToolName("acme_multi_probe"));
    }

    /** Built-in domains cannot be remapped; the collision is reported, not applied. */
    @Test
    void builtinDomainCannotBeRemapped() {
        List<String> rejected =
                ToolCategory.registerProviderDomains(Map.of("level", ToolCategory.SERVER));

        assertEquals(List.of("level"), rejected);
        assertEquals(ToolCategory.WORLD, ToolCategory.forToolName("level_get_time"));
    }

    /** Unknown domains still throw after the provider registry is consulted. */
    @Test
    void unknownDomainStillThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolCategory.forToolName("nonexistent_domain_tool"));
    }

    /** The test hook clears provider domains so suites stay order-independent. */
    @Test
    void clearRemovesProviderDomains() {
        ToolCategory.registerProviderDomains(Map.of("acme", ToolCategory.SERVER));
        assertEquals(ToolCategory.SERVER, ToolCategory.forToolName("acme_status"));

        ToolCategory.clearProviderDomains();

        assertThrows(
                IllegalArgumentException.class, () -> ToolCategory.forToolName("acme_status"));
    }
}
