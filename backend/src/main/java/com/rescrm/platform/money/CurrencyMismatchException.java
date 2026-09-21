package com.rescrm.platform.money;

/**
 * Thrown when an arithmetic or comparison operation mixes two currencies.
 *
 * <p>Unchecked by design: mixing currencies is a programming error, not a recoverable
 * condition. It is surfaced to API callers as {@code CURRENCY_MISMATCH} (doc 23, §4).
 */
public class CurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient CurrencyCode left;
    private final transient CurrencyCode right;

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("Cannot combine amounts in different currencies: " + left + " and " + right);
        this.left = left;
        this.right = right;
    }

    public CurrencyCode left() {
        return left;
    }

    public CurrencyCode right() {
        return right;
    }
}
