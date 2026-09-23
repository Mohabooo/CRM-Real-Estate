package com.rescrm.platform.money.persistence;

/**
 * The SQL type names of the money and rate domains, for use in {@code @Column}.
 *
 * <p>V1 created {@code money_amount} and {@code rate_percentage} as PostgreSQL DOMAINs so
 * that doc 22 section 8's {@code NUMERIC(18,2)} convention has one definition and cannot
 * drift column by column. That part works. What V1 also assumed — that a domain is
 * "transparent to JDBC, so no driver or mapping configuration depends on them" — is not true,
 * and it is why these constants exist.
 *
 * <p>{@code DatabaseMetaData.getColumns()} reports a domain-typed column as
 * {@code Types.DISTINCT} carrying the domain's name, not as {@code Types.NUMERIC}. Hibernate's
 * schema validator compares that against the type it infers from the mapping and rejects the
 * mismatch. Declaring precision and scale does not help: the validator then expects
 * {@code numeric(18,2)} and still finds {@code money_amount (Types#DISTINCT)}. The only thing
 * that satisfies it is naming the column's actual SQL type, which is what these constants are
 * for:
 *
 * <pre>
 * &#64;Convert(converter = MoneyAmountConverter.class)
 * &#64;Column(name = "list_price", nullable = false, columnDefinition = MoneyColumns.AMOUNT)
 * private Money listPrice;
 * </pre>
 *
 * <p>Constants rather than a repeated string literal because a typo then fails the compiler
 * instead of the application context, and because the money-bearing tables of Epics 5 to 8
 * will add a few dozen more of these.
 *
 * <p>The architecture test enforces it: a persistent {@link com.rescrm.platform.money.Money}
 * or {@link com.rescrm.platform.money.Percentage} field that omits the right
 * {@code columnDefinition} fails the build rather than the next startup.
 */
public final class MoneyColumns {

    /** {@code NUMERIC(18,2)} — every monetary amount. */
    public static final String AMOUNT = "money_amount";

    /** {@code NUMERIC(18,4)} — every rate, unrounded until the calculation says otherwise. */
    public static final String RATE = "rate_percentage";

    private MoneyColumns() {
    }
}
