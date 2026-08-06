package com.chapmanjw.minecraft.fabric.mcp.tools;

import java.util.List;

import com.chapmanjw.minecraft.fabric.mcp.protocol.Tool;

/**
 * Dev-only extension seam for contributing MCP tools from outside the mod itself.
 *
 * <p>Any Fabric mod may declare the custom entrypoint {@code "mcp-tools"} in its
 * {@code fabric.mod.json} pointing at an implementation of this interface. During
 * server startup {@link ToolRegistration} collects every such provider via
 * {@code FabricLoader.getInstance().getEntrypoints("mcp-tools", ToolProvider.class)}
 * and registers the returned tool classes <strong>after</strong> the built-in
 * {@link ToolRegistration#ALL_TOOL_CLASSES} list, through the exact same
 * compatibility, category, and access gating as built-in tools &mdash; providers
 * gain no special powers and pass through the same
 * {@link com.chapmanjw.minecraft.fabric.mcp.compat.ToolCompatibilityFilter}
 * evaluation path.
 *
 * <p>This seam is exclusive to the <strong>server</strong> registry. The client
 * inspection registry ({@link ClientToolRegistration}) is immutable and scans no
 * entrypoints, so companion tools cannot appear in the client-inspection endpoint.
 *
 * <p>Provider tools share the same name-uniqueness constraint: if a name collides
 * with an already-registered tool (built-in or from an earlier provider) the
 * colliding class is skipped and a warning is logged.
 *
 * @since mc-fabric-mcp v1.2.0 (spike)
 */
public interface ToolProvider {

    /**
     * Supply the tool classes this provider contributes.
     *
     * <p>Each class must carry an {@link com.chapmanjw.minecraft.fabric.mcp.tools.annotations.McpTool}
     * annotation and have a public no-argument constructor. If the method throws or
     * returns {@code null} the provider is silently skipped (one logged warning per
     * provider), allowing other providers and built-in tools to continue loading.
     *
     * @return the list of tool classes to register; never {@code null} in normal operation
     */
    List<Class<? extends Tool>> toolClasses();

    /**
     * Category mappings for this provider's tool-name domain prefixes, e.g.
     * {@code Map.of("jujutsu", ToolCategory.SERVER)} for tools named {@code jujutsu_*}.
     *
     * <p>{@link com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory#forToolName} resolves
     * categories from a closed built-in domain map and rejects unknown domains, so a provider
     * whose tool names live outside the built-in domains MUST declare them here or its tools
     * are skipped by the compatibility filter. Built-in domains cannot be overridden: a
     * conflicting key is ignored with a warning. Declared categories flow through the same
     * enable/access gating as built-in tools.
     *
     * @return domain-prefix to category mappings; empty when every contributed tool already
     *     lives in a built-in domain
     */
    default java.util.Map<String, com.chapmanjw.minecraft.fabric.mcp.compat.ToolCategory> domainCategories() {
        return java.util.Map.of();
    }
}
