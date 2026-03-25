package com.hmdp.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class CacheInvalidationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String businessType;
    private String businessId;
    private String cacheKey;
    private String operation;
    private Integer retryCount;
    private Long createdAt;
    private Long nextRetryTime;
    private String lastError;
}
