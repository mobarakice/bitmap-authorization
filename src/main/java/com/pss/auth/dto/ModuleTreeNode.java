package com.pss.auth.dto;

import java.util.List;

public record ModuleTreeNode(
    String code,
    String name,
    List<FeatureNode> features,
    List<ModuleTreeNode> children
) {}
