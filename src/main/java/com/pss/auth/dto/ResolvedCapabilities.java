package com.pss.auth.dto;

import java.util.List;

public record ResolvedCapabilities(
    List<String> capabilities,
    List<ModuleTreeNode> modules
) {}
