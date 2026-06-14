package com.example.ssp.model.enums;

import lombok.Getter;

@Getter
public enum BidStatus {
    TIMEOUT(0, "超时"),
    VALID_BID(1, "有效出价"),
    NO_BID(2, "无出价"),
    ERROR(3, "异常"),
    RATE_LIMITED(4, "限流");

    private final int code;
    private final String desc;

    BidStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static BidStatus fromCode(int code) {
        for (BidStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown BidStatus code: " + code);
    }
}
