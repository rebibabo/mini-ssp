package com.example.ssp.model.enums;

import lombok.Getter;

@Getter
public enum EventType {
    IMPRESSION(1, "曝光"),
    CLICK(2, "点击");

    private final int code;
    private final String desc;

    EventType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static EventType fromCode(int code) {
        for (EventType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown EventType code: " + code);
    }
}
