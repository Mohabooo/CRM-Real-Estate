package com.rescrm.crm.domain;

/**
 * What an activity is about.
 *
 * <p>Polymorphic by type name and id rather than by a foreign key per subject, because the
 * same note applies to a lead before conversion and a customer after it, and later epics add
 * deals. The database constrains the set, so an activity cannot attach itself to something
 * that has no meaning.
 */
public enum ActivitySubject {

    LEAD("Lead"),
    CUSTOMER("Customer");

    private final String code;

    ActivitySubject(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ActivitySubject fromCode(String code) {
        for (ActivitySubject subject : values()) {
            if (subject.code.equals(code)) {
                return subject;
            }
        }
        throw new IllegalArgumentException("Unknown activity subject '" + code + "'");
    }
}
