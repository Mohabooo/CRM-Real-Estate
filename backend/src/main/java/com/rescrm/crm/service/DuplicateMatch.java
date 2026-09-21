package com.rescrm.crm.service;

import java.util.UUID;

/**
 * One existing record that shares a phone number with what is being created.
 *
 * <p>A warning, never a block. Doc 07 A5 and doc 19 are both explicit: the system warns and
 * proceeds on confirmation, and never auto-merges. Two family members really do share a
 * number, and a system that refuses the second one teaches people to enter it wrong.
 */
public record DuplicateMatch(String recordType, UUID id, String name, String phoneNormalized) {

    public static final String LEAD = "Lead";
    public static final String CUSTOMER = "Customer";
}
