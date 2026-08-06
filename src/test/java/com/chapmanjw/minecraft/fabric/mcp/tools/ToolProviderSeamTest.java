package com.chapmanjw.minecraft.fabric.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.chapmanjw.minecraft.fabric.mcp.compat.McEnvironment;
import com.chapmanjw.minecraft.fabric.mcp.compat.ToolCompatibilityFilter;
import com.chapmanjw.minecraft.fabric.mcp.protocol.Schemas;
import com.chapmanjw.minecraft.fabric.mcp.protocol.Tool;
import com.chapmanjw.minecraft.fabric.mcp.protocol.ToolContext;
import com.chapmanjw.minecraft.fabric.mcp.protocol.ToolRegistry;
import com.chapmanjw.minecraft.fabric.mcp.protocol.ToolResult;
import com.chapmanjw.minecraft.fabric.mcp.tools.annotations.McpTool;
import com.chapmanjw.minecraft.fabric.mcp.tools.level.LevelListDimensionsTool;

/**
 * Tests for the {@link ToolProvider} extension seam (the {@code "mcp-tools"}
 * Fabric entrypoint). Exercises the three provider-contract branches:
 * successful contribution, throwing provider (fail-closed for itself), and
 * name collision (skipped).
 */
class ToolProviderSeamTest {

    /** Provider domains land in ToolCategory's static registry — reset per test. */
    @org.junit.jupiter.api.AfterEach
    void resetProviderDomains() {
        com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory.clearProviderDomains();
    }
    // ---- fixtures ----------------------------------------------------------------

    private static final McEnvironment ENV =
            new McEnvironment("1.21.8", "0.19.3", Map.of());

    private static ToolCompatibilityFilter filter() {
        return new ToolCompatibilityFilter(ENV);
    }

    @McpTool(
            name = "server_seam_echo",
            description = "Test-only tool contributed by a fake provider")
    static final class EchoTool implements Tool {

        private static final JsonNode SCHEMA =
                Schemas.object().description("No arguments.").build();

        @Override
        public JsonNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            return ToolResult.ofText("echo");
        }
    }

    /** A built-in tool known to pass the default filter with minimal env. */
    private static final List<Class<? extends Tool>> BUILTIN_FIXTURE =
            List.of(LevelListDimensionsTool.class);

    /**
     * A tool whose {@code @McpTool} name has no domain-prefix category mapping —
     * {@link com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory} throws for it
     * inside the compatibility filter, simulating a malformed provider class.
     */
    @McpTool(
            name = "provider_unmapped_domain",
            description = "Test-only tool with an unmapped category domain")
    static final class UnmappedDomainTool implements Tool {

        private static final JsonNode SCHEMA =
                Schemas.object().description("No arguments.").build();

        @Override
        public JsonNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            return ToolResult.ofText("unmapped");
        }
    }

    // ---- tests ------------------------------------------------------------------

    /**
     * A provider that contributes one valid tool class → that tool is present in the
     * registry alongside the built-in entry.
     */
    @Test
    void providerContributesToolClass() {
        ToolRegistry registry =
                ToolRegistration.buildRegistry(
                        BUILTIN_FIXTURE,
                        filter(),
                        List.of(() -> List.of(EchoTool.class)));

        // built-in still registered
        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        // provider tool registered
        assertTrue(registry.lookup("server_seam_echo").isPresent());
    }

    /**
     * A provider whose {@code toolClasses()} throws a runtime exception: the provider
     * is failed closed (its tools are lost), but the registry survives and built-in
     * tools remain fully intact (fail-open for built-ins).
     */
    @Test
    void throwingProviderFailsClosedForItself() {
        ToolRegistry registry =
                ToolRegistration.buildRegistry(
                        BUILTIN_FIXTURE,
                        filter(),
                        List.of(
                                () -> {
                                    throw new IllegalStateException("simulated provider failure");
                                }));

        // built-in intact
        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        // the throwing provider contributed nothing
        assertEquals(1, registry.size());
    }

    /**
     * A provider that returns a tool class whose {@code @McpTool} name collides with
     * an already-registered built-in: the collision is logged and skipped, the
     * built-in remains, and the registry size does not grow.
     */
    @Test
    void duplicateProviderToolNameIsSkipped() {
        ToolRegistry registry =
                ToolRegistration.buildRegistry(
                        BUILTIN_FIXTURE,
                        filter(),
                        List.of(() -> List.of(LevelListDimensionsTool.class)));

        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        assertEquals(1, registry.size());
    }

    /**
     * A provider returning {@code null} from {@code toolClasses()} is silently
     * ignored — built-ins are untouched.
     */
    @Test
    void nullReturningProviderIsIgnored() {
        ToolRegistry registry =
                ToolRegistration.buildRegistry(
                        BUILTIN_FIXTURE,
                        filter(),
                        List.of(() -> null));

        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        assertEquals(1, registry.size());
    }

    /**
     * A provider returning a list that contains a {@code null} element: the null is
     * logged and skipped without aborting the registration of the valid element or
     * the built-ins (fail-open).
     */
    @Test
    void nullElementInProviderListIsSkipped() {
        ToolRegistry registry =
                ToolRegistration.buildRegistry(
                        BUILTIN_FIXTURE,
                        filter(),
                        List.of(() -> Arrays.asList(EchoTool.class, null)));

        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        assertTrue(registry.lookup("server_seam_echo").isPresent());
        assertEquals(2, registry.size());
    }

    /**
     * A provider whose tool lives in a domain unknown to the built-in category map,
     * declared through {@link ToolProvider#domainCategories()}: the domain resolves and
     * the tool registers (the exact failure mode the first live OMP run exposed for
     * {@code jujutsu_mod_status}). Domain state is reset in {@code resetProviderDomains}.
     */
    @Test
    void providerDeclaredDomainRegistersItsTool() {
        ToolProvider provider = new ToolProvider() {
            @Override
            public List<Class<? extends Tool>> toolClasses() {
                return List.of(SeamDomainTool.class);
            }

            @Override
            public Map<String, com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory> domainCategories() {
                return Map.of("jujutsuseam", com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory.SERVER);
            }
        };
        ToolRegistry registry =
                ToolRegistration.buildRegistry(BUILTIN_FIXTURE, filter(), List.of(provider));

        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        assertTrue(registry.lookup("jujutsuseam_probe").isPresent());
        assertEquals(2, registry.size());
    }

    @McpTool(
            name = "jujutsuseam_probe",
            description = "Test-only tool in a provider-declared domain")
    static final class SeamDomainTool implements Tool {

        private static final JsonNode SCHEMA =
                Schemas.object().description("No arguments.").build();

        @Override
        public JsonNode inputSchema() {
            return SCHEMA;
        }

        @Override
        public ToolResult execute(JsonNode arguments, ToolContext context) {
            return ToolResult.ofText("probe");
        }
    }

    /**
     * A provider contributing a class the compatibility filter rejects with a
     * runtime exception (unmapped category domain): the bad element is failed
     * closed for that provider only — the registry survives and built-ins remain
     * fully intact.
     */
    @Test
    void unmappableProviderToolClassFailsClosedForProvider() {
        ToolRegistry registry =
                ToolRegistration.buildRegistry(
                        BUILTIN_FIXTURE,
                        filter(),
                        List.of(() -> List.of(UnmappedDomainTool.class)));

        assertTrue(registry.lookup("level_list_dimensions").isPresent());
        assertEquals(1, registry.size());
    }
}
