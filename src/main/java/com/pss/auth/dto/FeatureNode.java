package com.pss.auth.dto;

import java.util.List;

public record FeatureNode(
    String code,
    String name,
    List<String> capabilities
) {}
