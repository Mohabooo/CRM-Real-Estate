package com.rescrm.platform.api;

/**
 * An enum that travels over the API as a stable lowercase code rather than its Java name.
 *
 * <p>Every lifecycle enum in this system already has one — {@code available}, {@code draft},
 * {@code pending} — because doc 22 stores them as check-constrained text so that adding a
 * state is an ordinary migration. Responses have always sent the code. This interface is what
 * lets requests ACCEPT it too.
 *
 * <p>Without it the API is asymmetric in a way nobody notices until a client is written
 * against it: {@code GET /units} returns {@code "status":"available"} but its own
 * {@code ?status=} parameter only accepts {@code AVAILABLE}, because Spring binds enums by
 * {@link Enum#valueOf}. A client that round-trips a value it was given gets a 400.
 *
 * <p>It lives in {@code platform} and is implemented by the business modules, rather than
 * {@code platform} knowing about any of them — which is the direction the module rules
 * require.
 */
public interface CodedEnum {

    /** The value this constant takes in the database and on the wire. */
    String code();
}
