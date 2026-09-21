package com.rescrm.crm.domain;

/**
 * The kinds of contact E2-S3 names: call, meeting, WhatsApp summary, note.
 *
 * <p>The story says types "include" these, so the set is expected to grow. Growing it takes a
 * migration as well as a value here, which is deliberate: an activity type that exists in code
 * but not in the check constraint would fail at insert, and one that exists in neither should
 * not appear because somebody passed a new string.
 */
public enum ActivityType {

    CALL("call"),
    MEETING("meeting"),
    WHATSAPP("whatsapp"),
    NOTE("note");

    private final String code;

    ActivityType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ActivityType fromCode(String code) {
        for (ActivityType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown activity type '" + code + "'");
    }
}
