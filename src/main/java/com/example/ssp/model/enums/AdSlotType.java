package com.example.ssp.model.enums;

import lombok.Getter;

@Getter
public enum AdSlotType {
    BANNER(1, "横幅"),
    INTERSTITIAL(2, "插屏"),
    SPLASH(3, "开屏"),
    NATIVE_FEED(4, "信息流");

    private final int code;
    private final String desc;

    AdSlotType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AdSlotType fromCode(int code) {
        for (AdSlotType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown AdSlotType code: " + code);
    }
}
